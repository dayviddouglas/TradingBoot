package com.github.dayviddouglas.TradingBoot.deriv.trade.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.dayviddouglas.TradingBoot.deriv.DerivMarketDataService;
import com.github.dayviddouglas.TradingBoot.deriv.trade.context.TradeContext;
import com.github.dayviddouglas.TradingBoot.exceptions.DerivErrorException;
import com.github.dayviddouglas.TradingBoot.report.TradeReportEntry;
import com.github.dayviddouglas.TradingBoot.report.TradeReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Responsável pelo monitoramento de contratos abertos e pelo registro do resultado
 * no {@link TradeReportService}.
 *
 * Após a compra de um contrato pelo {@code TradeExecutor}, este componente:
 * <ol>
 *   <li>Subscreve o stream de atualizações do contrato via {@code proposal_open_contract}</li>
 *   <li>Inicia um watchdog em virtual thread como fallback para casos em que o stream
 *       não entregue o fechamento</li>
 *   <li>Processa o fechamento quando {@code is_sold == 1} é detectado por qualquer um dos dois</li>
 *   <li>Registra o resultado no {@link TradeReportService} e reseta o {@link TradeState} para IDLE</li>
 * </ol>
 *
 * O processamento de fechamento é protegido por {@link TradeStateRegistry#claimClosedContract},
 * que garante atomicidade: apenas o primeiro a chamar claim processa o resultado,
 * evitando duplicidade entre stream e watchdog.
 *
 * A separação entre {@link TradeStateRegistry#isPendingClose} e
 * {@link TradeStateRegistry#claimClosedContract} é intencional:
 * {@code isPendingClose} apenas verifica existência sem remover o contrato do registry,
 * enquanto {@code claimClosedContract} é chamado exclusivamente após confirmar
 * {@code is_sold == 1}, garantindo que o state permaneça acessível para leitura
 * até a confirmação real do fechamento.
 */
@Component
public class TradeMonitor {

    private static final Logger log = LoggerFactory.getLogger(TradeMonitor.class);

    /** Tempo adicional aguardado após a duração esperada do contrato antes de ativar o watchdog. */
    private static final Duration CLOSE_GRACE = Duration.ofSeconds(75);

    /** Intervalo entre cada poll do watchdog ao endpoint de contrato aberto. */
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(10);

    /** Número máximo de polls realizados pelo watchdog antes de encerrar. */
    private static final int POLL_ATTEMPTS = 6;

    private final DerivMarketDataService marketDataService;
    private final TradeReportService tradeReportService;
    private final TradeStateRegistry registry;

    /**
     * @param marketDataService  utilizado para subscrever o stream e consultar o contrato via poll
     * @param tradeReportService utilizado para registrar o resultado da operação
     * @param registry           gerencia os estados de contratos abertos por ativo
     */
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
     * Subscreve o stream WebSocket e inicia o watchdog em paralelo em virtual thread.
     *
     * @param state   estado do ativo com o contractId e contexto da operação
     * @param context contexto completo da operação, utilizado para calcular o tempo de espera do watchdog
     */
    public void startMonitoring(TradeState state, TradeContext context) {
        long contractId = state.getContractId();

        subscribeContractStream(state, contractId);
        startWatchdog(state, contractId, context.duration(), context.durationUnit());
    }

    /**
     * Callback registrado no {@link DerivMarketDataService} para receber atualizações
     * de contratos abertos via stream WebSocket.
     *
     * Fluxo de processamento:
     * <ol>
     *   <li>Extrai o {@code contractId} da mensagem</li>
     *   <li>Verifica se o contrato ainda está pendente no registry via {@code isPendingClose}
     *       sem removê-lo</li>
     *   <li>Captura o {@code subscriptionId} da mensagem para uso no cancelamento posterior</li>
     *   <li>Verifica se {@code is_sold == 1} — apenas então prossegue</li>
     *   <li>Realiza o claim atômico via {@code claimClosedContract}; se retornar null,
     *       o watchdog já processou primeiro e esta chamada é descartada</li>
     *   <li>Processa o fechamento e registra o resultado</li>
     * </ol>
     *
     * @param msg mensagem JSON recebida via stream WebSocket
     */
    public void handleStreamUpdate(JsonNode msg) {
        try {
            JsonNode poc = msg.get("proposal_open_contract");
            if (poc == null || !poc.isObject()) return;

            long contractId = poc.path("contract_id").asLong(-1);
            if (contractId <= 0) return;

            // Verifica existência no registry sem remover — o state permanece acessível
            if (!registry.isPendingClose(contractId)) return;

            // Captura o subscriptionId enquanto o state ainda está no registry
            captureSubscriptionIdFromMsg(contractId, msg);

            // Aguarda is_sold == 1 antes de qualquer remoção do registry
            if (!isContractSold(poc)) return;

            // Claim atômico — garante que somente stream ou watchdog processará o fechamento
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

    /**
     * Envia o request de subscrição do stream {@code proposal_open_contract}
     * para o contrato informado e aguarda o acknowledge da API.
     */
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
     * Extrai o {@code subscriptionId} da mensagem recebida e o registra no {@link TradeState}
     * correspondente, consultando o registry sem realizar claim.
     * O {@code subscriptionId} é necessário para cancelar o stream após o fechamento do contrato.
     *
     * @param contractId identificador do contrato para localizar o state no registry
     * @param msg        mensagem JSON com o campo {@code subscription.id}
     */
    private void captureSubscriptionIdFromMsg(long contractId, JsonNode msg) {
        String subscriptionId = msg.path("subscription").path("id").asText("");
        if (subscriptionId.isBlank()) return;

        // Consulta o state sem removê-lo — getStateByContractId não realiza claim
        TradeState state = registry.getStateByContractId(contractId);
        if (state != null) {
            state.setSubscriptionId(subscriptionId);
        }
    }

    /**
     * Verifica se o contrato foi efetivamente vendido/fechado pela API.
     *
     * @param poc nó JSON do {@code proposal_open_contract}
     * @return {@code true} se {@code is_sold} for igual a {@code 1}
     */
    private boolean isContractSold(JsonNode poc) {
        return poc.path("is_sold").asInt(0) == 1;
    }

    // ═══════════════════════════════════════════════════════════════
    // Watchdog
    // ═══════════════════════════════════════════════════════════════

    /**
     * Inicia o watchdog em uma virtual thread separada.
     * O watchdog aguarda a duração esperada do contrato acrescida de {@link #CLOSE_GRACE}
     * antes de começar a realizar polls ao endpoint de contrato aberto.
     * Quando a duração do contrato não puder ser convertida, utiliza 20 minutos como fallback.
     */
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

    /**
     * Lógica principal do watchdog executada em virtual thread.
     * Após o tempo de espera inicial, verifica se o contrato ainda está pendente
     * e realiza até {@value POLL_ATTEMPTS} polls com intervalo de {@link #POLL_INTERVAL}.
     * Encerra silenciosamente se o stream já tiver processado o fechamento.
     */
    private void runWatchdog(TradeState state, long contractId,
                             Duration waitTime) {
        sleepSilently(waitTime.toMillis());

        // Se o stream já processou o fechamento, o contrato não estará mais no registry
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

    /**
     * Realiza um único poll ao endpoint de contrato aberto e processa o fechamento
     * se {@code is_sold == 1} for detectado. Utiliza claim atômico para garantir
     * que stream e watchdog não processem o mesmo fechamento.
     *
     * @param state      estado do ativo monitorado
     * @param contractId identificador do contrato
     * @param attempt    número da tentativa atual, utilizado apenas para log
     * @return {@code true} se o fechamento foi detectado e processado
     */
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

    /**
     * Processa o fechamento confirmado de um contrato.
     * Calcula lucro, resultado (WIN/LOSS), duração real e delega o registro
     * ao {@link TradeReportService}. Cancela o stream e reseta o estado para IDLE.
     *
     * @param poc    nó JSON do {@code proposal_open_contract} com dados do fechamento
     * @param fullMsg mensagem completa, utilizada para extrair o subscriptionId
     * @param state  state reivindicado atomicamente via claim
     * @param source identificador da origem do fechamento: {@code "STREAM"} ou {@code "WATCHDOG"}
     */
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

    /**
     * Retorna o timestamp de entrada registrado no {@link TradeState},
     * ou o timestamp de saída como fallback quando a entrada não estiver disponível.
     */
    private Instant resolveEntryTimestamp(TradeState state, Instant fallback) {
        return state.getEntryTimestamp() != null
                ? state.getEntryTimestamp()
                : fallback;
    }

    /**
     * Determina o resultado da operação com base no lucro apurado.
     * Considera WIN somente quando o lucro for finito e positivo.
     *
     * @param profit lucro apurado pela API; pode ser negativo ou {@code NaN}
     * @return {@code "WIN"} ou {@code "LOSS"}
     */
    private String resolveResult(double profit) {
        return (Double.isFinite(profit) && profit > 0) ? "WIN" : "LOSS";
    }

    /**
     * Constrói o {@link TradeReportEntry} com todos os dados da operação encerrada
     * e delega o registro ao {@link TradeReportService}.
     * O ROI real é calculado como {@code profit / stake * 100}.
     * A duração real é calculada em minutos a partir dos timestamps de entrada e saída.
     */
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

    /**
     * Cancela o stream {@code proposal_open_contract} via {@code forget}.
     * O {@code subscriptionId} é resolvido primeiro pelo {@link TradeState}
     * e, como fallback, extraído da mensagem completa recebida via stream.
     * Erros no cancelamento são tolerados, pois o contrato já foi fechado.
     */
    private void cancelSubscription(TradeState state, JsonNode fullMsg) {
        String subscriptionId = resolveSubscriptionId(state, fullMsg);
        if (subscriptionId == null || subscriptionId.isBlank()) return;

        try {
            marketDataService.forget(subscriptionId);
        } catch (Exception ignored) {
            // Contrato já fechou — falha no forget não compromete o resultado registrado
        }
    }

    /**
     * Resolve o {@code subscriptionId} prioritariamente pelo {@link TradeState},
     * com fallback para o campo {@code subscription.id} da mensagem recebida.
     */
    private String resolveSubscriptionId(TradeState state, JsonNode fullMsg) {
        String fromState = state.getSubscriptionId();
        if (fromState != null && !fromState.isBlank()) return fromState;
        return fullMsg.path("subscription").path("id").asText("");
    }

    // ═══════════════════════════════════════════════════════════════
    // Utilitários
    // ═══════════════════════════════════════════════════════════════

    /**
     * Converte a duração e unidade configuradas para um {@link Duration}.
     * Retorna {@code null} quando a unidade não for reconhecida.
     *
     * @param duration valor numérico da duração
     * @param unit     unidade de duração: {@code s}, {@code m}, {@code h} ou {@code d}
     * @return {@link Duration} correspondente ou {@code null}
     */
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

    /**
     * Aguarda o tempo informado em milissegundos, restaurando o flag de interrupção
     * da thread caso o sleep seja interrompido.
     *
     * @param millis tempo de espera em milissegundos
     */
    private void sleepSilently(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}