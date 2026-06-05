package com.github.dayviddouglas.TradingBot.deriv.trade.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.dayviddouglas.TradingBot.deriv.DerivMarketDataService;
import com.github.dayviddouglas.TradingBot.deriv.trade.context.TradeContext;
import com.github.dayviddouglas.TradingBot.exceptions.DerivErrorException;
import com.github.dayviddouglas.TradingBot.report.TradeReportEntry;
import com.github.dayviddouglas.TradingBot.report.TradeReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Responsável pelo monitoramento de contratos abertos e registro do resultado.
 *
 * Responsabilidades:
 * - Subscrever stream de atualizações do contrato aberto
 * - Iniciar watchdog como fallback preventivo
 * - Processar fechamento do contrato (via stream ou watchdog)
 * - Registrar resultado no TradeReportService
 * - Cancelar subscription e resetar estado do ativo
 *
 * Correção v5.1:
 * O método findStateByContractId() chamava claimClosedContract() de forma
 * prematura, removendo o contrato do registry antes de confirmar is_sold == 1.
 * Isso fazia com que o fechamento real do contrato nunca fosse processado,
 * pois quando is_sold chegava como 1, o contrato já havia sido removido
 * do registry e findStateByContractId() retornava null.
 *
 * A correção separa a verificação de existência (isPendingClose) do claim:
 * - isPendingClose(): apenas verifica se o contrato existe no registry
 * - claimClosedContract(): chamado SOMENTE após confirmar is_sold == 1
 */
@Component
public class TradeMonitor {

    private static final Logger log = LoggerFactory.getLogger(TradeMonitor.class);

    private static final Duration CLOSE_GRACE = Duration.ofSeconds(75);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(10);
    private static final int POLL_ATTEMPTS = 6;

    private final DerivMarketDataService marketDataService;
    private final TradeReportService tradeReportService;
    private final TradeStateRegistry registry;

    public TradeMonitor(
            DerivMarketDataService marketDataService,
            TradeReportService tradeReportService,
            TradeStateRegistry registry
    ) {
        this.marketDataService = marketDataService;
        this.tradeReportService = tradeReportService;
        this.registry = registry;
    }

    /**
     * Inicia o monitoramento de um contrato recém-comprado.
     *
     * @param state   estado do ativo com contexto da operação
     * @param context contexto completo da operação
     */
    public void startMonitoring(TradeState state, TradeContext context) {
        long contractId = state.getContractId();

        subscribeContractStream(state, contractId);
        startWatchdog(state, contractId, context.duration(), context.durationUnit());
    }

    /**
     * Callback registrado no DerivMarketDataService para receber
     * atualizações de contratos abertos via stream WebSocket.
     *
     * Fluxo corrigido:
     * 1. Extrai contractId da mensagem
     * 2. Verifica se o contrato existe no registry (isPendingClose)
     * 3. Captura subscriptionId para cancelamento futuro
     * 4. Verifica is_sold == 1
     * 5. SOMENTE ENTÃO faz o claim e processa o fechamento
     *
     * A separação entre isPendingClose() e claimClosedContract() garante
     * que o contrato só é removido do registry quando o fechamento é real.
     */
    public void handleStreamUpdate(JsonNode msg) {
        try {
            JsonNode poc = msg.get("proposal_open_contract");
            if (poc == null || !poc.isObject()) return;

            long contractId = poc.path("contract_id").asLong(-1);
            if (contractId <= 0) return;

            // Verifica existência sem remover do registry
            if (!registry.isPendingClose(contractId)) return;

            // Captura subscription ID para uso no cancelamento posterior
            // O state ainda está no registry neste ponto
            captureSubscriptionIdFromMsg(contractId, msg);

            // Verifica se o contrato realmente fechou
            if (!isContractSold(poc)) return;

            // SOMENTE AQUI faz o claim — remove do registry de forma atômica
            // Se retornar null, o watchdog já processou primeiro
            TradeState state = registry.claimClosedContract(contractId);
            if (state == null) return;

            processClosedContract(poc, msg, state, "STREAM");

        } catch (DerivErrorException e) {
            log.warn("TRADE STREAM ERROR | message={}", e.getMessage(), e);
        } catch (Exception e) {
            log.warn("TRADE STREAM | unexpected error | message={}", e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Stream
    // ═══════════════════════════════════════════════════════════════

    private void subscribeContractStream(TradeState state, long contractId) {
        try {
            JsonNode ack = marketDataService.subscribeOpenContract(contractId).join();
            log.info("TRADE MONITOR SUBSCRIBED | symbol={} | contract_id={} | ack_msg_type={}",
                    state.getSymbol(), contractId,
                    ack.path("msg_type").asText(""));
        } catch (Exception e) {
            log.warn("TRADE MONITOR SUBSCRIBE FAILED | symbol={} | contract_id={} | error={}",
                    state.getSymbol(), contractId, e.getMessage());
        }
    }

    /**
     * Captura o subscriptionId da mensagem e registra no TradeState
     * sem precisar extrair o state do registry.
     *
     * Busca o state diretamente pelo contractId no registry sem claim.
     * Isso é seguro porque o state ainda não foi removido neste ponto.
     */
    private void captureSubscriptionIdFromMsg(long contractId, JsonNode msg) {
        String subscriptionId = msg.path("subscription").path("id").asText("");
        if (subscriptionId.isBlank()) return;

        // Acessa o state via registry sem removê-lo
        // getStateByContractId() apenas consulta, não remove
        TradeState state = registry.getStateByContractId(contractId);
        if (state != null) {
            state.setSubscriptionId(subscriptionId);
        }
    }

    private boolean isContractSold(JsonNode poc) {
        return poc.path("is_sold").asInt(0) == 1;
    }

    // ═══════════════════════════════════════════════════════════════
    // Watchdog
    // ═══════════════════════════════════════════════════════════════

    private void startWatchdog(TradeState state, long contractId,
                               int duration, String durationUnit) {
        Duration expectedDuration = toDuration(duration, durationUnit);
        Duration waitTime = (expectedDuration != null
                ? expectedDuration
                : Duration.ofMinutes(20))
                .plus(CLOSE_GRACE);

        Thread.startVirtualThread(() ->
                runWatchdog(state, contractId, waitTime));
    }

    private void runWatchdog(TradeState state, long contractId,
                             Duration waitTime) {
        sleepSilently(waitTime.toMillis());

        if (!registry.isPendingClose(contractId)) return;

        log.info("WATCHDOG ACTIVATED | symbol={} | contract_id={}",
                state.getSymbol(), contractId);

        for (int attempt = 1; attempt <= POLL_ATTEMPTS; attempt++) {
            if (!registry.isPendingClose(contractId)) return;

            boolean resolved = pollContractOnce(state, contractId, attempt);
            if (resolved) return;

            sleepSilently(POLL_INTERVAL.toMillis());
        }

        log.warn("WATCHDOG EXHAUSTED | symbol={} | contract_id={}",
                state.getSymbol(), contractId);
    }

    private boolean pollContractOnce(TradeState state, long contractId,
                                     int attempt) {
        try {

            JsonNode msg = marketDataService.getOpenContractOnce(contractId)
                    .orTimeout(8, TimeUnit.SECONDS)
                    .join();

            JsonNode poc = msg.get("proposal_open_contract");

            if (poc != null && poc.isObject() && isContractSold(poc)) {
                TradeState claimed = registry.claimClosedContract(contractId);
                if (claimed != null) {
                    processClosedContract(poc, msg, claimed, "WATCHDOG");
                }
                return true;
            }

        } catch (Exception e) {
            log.debug("WATCHDOG POLL FAILED | contract_id={} | attempt={} | error={}",
                    contractId, attempt, e.getMessage());
        }

        return false;
    }

    // ═══════════════════════════════════════════════════════════════
    // Processamento de fechamento
    // ═══════════════════════════════════════════════════════════════

    private void processClosedContract(JsonNode poc, JsonNode fullMsg,
                                       TradeState state, String source) {
        long contractId = poc.path("contract_id").asLong(-1);
        if (contractId <= 0) return;

        Instant exitTimestamp = Instant.now();
        Instant entryTimestamp = resolveEntryTimestamp(state, exitTimestamp);

        double profit = poc.path("profit").asDouble(Double.NaN);
        String result = resolveResult(profit);

        log.info("TRADE CLOSED | source={} | symbol={} | contract_id={} " +
                        "| result={} | profit={}",
                source, state.getSymbol(), contractId, result,
                Double.isFinite(profit) ? profit : "N/A");

        recordTradeResult(state, contractId, profit,
                entryTimestamp, exitTimestamp, result);
        cancelSubscription(state, fullMsg);
        state.resetToIdle();
    }

    private Instant resolveEntryTimestamp(TradeState state, Instant fallback) {
        return state.getEntryTimestamp() != null
                ? state.getEntryTimestamp()
                : fallback;
    }

    private String resolveResult(double profit) {
        return (Double.isFinite(profit) && profit > 0) ? "WIN" : "LOSS";
    }

    private void recordTradeResult(TradeState state, long contractId,
                                   double profit, Instant entryTimestamp,
                                   Instant exitTimestamp, String result) {
        double stake = state.getStake();
        double payout = Double.isFinite(profit) ? stake + profit : Double.NaN;
        double roiPct = (Double.isFinite(profit) && stake > 0.0)
                ? (profit / stake) * 100.0
                : Double.NaN;

        double durationMinutesReal = Duration.between(entryTimestamp, exitTimestamp)
                .toMillis() / 60_000.0;

        tradeReportService.record(new TradeReportEntry(
                entryTimestamp,
                exitTimestamp,
                state.getSymbol(),
                state.getDecisionMode(),
                state.getStrategy(),
                state.getDecisionStrategies(),
                state.getRegime(),
                state.getSignalType().name(),
                stake,
                state.getCurrency(),
                state.getDuration(),
                state.getDurationUnit(),
                durationMinutesReal,
                Double.isFinite(profit) ? profit : 0.0,
                Double.isFinite(payout) ? payout : 0.0,
                Double.isFinite(roiPct) ? roiPct : 0.0,
                result,
                contractId
        ));
    }

    private void cancelSubscription(TradeState state, JsonNode fullMsg) {
        String subscriptionId = resolveSubscriptionId(state, fullMsg);
        if (subscriptionId == null || subscriptionId.isBlank()) return;

        try {
            marketDataService.forget(subscriptionId);
        } catch (Exception ignored) {
            // Contrato já fechou, erro no forget é tolerado
        }
    }

    private String resolveSubscriptionId(TradeState state, JsonNode fullMsg) {
        String fromState = state.getSubscriptionId();
        if (fromState != null && !fromState.isBlank()) return fromState;
        return fullMsg.path("subscription").path("id").asText("");
    }

    // ═══════════════════════════════════════════════════════════════
    // Utilitários
    // ═══════════════════════════════════════════════════════════════

    private Duration toDuration(int duration, String unit) {
        if (unit == null) return null;
        return switch (unit.trim()) {
            case "s" -> Duration.ofSeconds(duration);
            case "m" -> Duration.ofMinutes(duration);
            case "h" -> Duration.ofHours(duration);
            case "d" -> Duration.ofDays(duration);
            default -> null;
        };
    }

    private void sleepSilently(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}