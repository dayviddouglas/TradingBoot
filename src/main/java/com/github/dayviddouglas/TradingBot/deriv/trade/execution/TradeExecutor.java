package com.github.dayviddouglas.TradingBot.deriv.trade.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.dayviddouglas.TradingBot.deriv.DerivMarketDataService;
import com.github.dayviddouglas.TradingBot.deriv.trade.context.TradeContext;
import com.github.dayviddouglas.TradingBot.exceptions.DerivErrorException;
import com.github.dayviddouglas.TradingBot.exceptions.TradeExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * Responsável pela execução do ciclo proposal → ROI check → buy.
 *
 * Responsabilidades:
 * - Solicitar proposal à API Deriv
 * - Calcular e validar ROI esperado
 * - Executar a compra do contrato
 * - Implementar retry para erros PriceMoved
 *
 * Atualização v5.4:
 * MIN_ROI_PERCENT removido como constante hardcoded.
 * O valor mínimo de ROI agora é lido do TradeContext, que carrega
 * o valor configurado por ativo no strategies.json via TradeConfig.
 * Isso implementa o SUG-02 — ROI mínimo configurável por ativo.
 *
 * Correção v5.3:
 * - Removidas chamadas a ensureAuthorized() — autenticação via OTP.
 * - Campo "symbol" atualizado para "underlying_symbol" no proposal.
 *
 * Thread-safety: stateless — pode ser compartilhado entre threads.
 */
@Component
public class TradeExecutor {

    private static final Logger log =
            LoggerFactory.getLogger(TradeExecutor.class);

    private static final int  MAX_PRICE_MOVED_RETRIES    = 1;
    private static final long PRICE_MOVED_RETRY_DELAY_MS = 300;

    private static final Duration PROPOSAL_TIMEOUT = Duration.ofSeconds(12);
    private static final Duration BUY_TIMEOUT      = Duration.ofSeconds(12);

    private final DerivMarketDataService marketDataService;

    public TradeExecutor(DerivMarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    /**
     * Executa o ciclo completo: proposal → ROI check → buy.
     *
     * O ROI mínimo aceitável é lido do TradeContext, que carrega
     * o valor configurado por ativo no strategies.json.
     *
     * Implementa retry para PriceMoved:
     * - Na primeira falha, aguarda 300ms e tenta novamente
     * - Na segunda falha ou outro erro, propaga a exceção
     *
     * @param context contexto completo da operação
     * @return resultado da execução com contractId e buyPrice
     * @throws TradeExecutionException se a execução falhar definitivamente
     */
    public TradeExecutionResult execute(TradeContext context) {
        int priceMovedAttempt = 0;

        while (true) {
            try {
                return attemptExecution(context);
            } catch (Exception e) {
                String errorMessage = extractErrorMessage(e);

                if (isPriceMovedError(errorMessage)
                        && priceMovedAttempt < MAX_PRICE_MOVED_RETRIES) {
                    priceMovedAttempt++;
                    log.warn("TRADE RETRY | symbol={} | reason=PriceMoved "
                                    + "| retry={}/{}",
                            context.symbol(),
                            priceMovedAttempt,
                            MAX_PRICE_MOVED_RETRIES);
                    waitBeforeRetry();
                    continue;
                }

                throw new TradeExecutionException(errorMessage, e);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Execução individual
    // ═══════════════════════════════════════════════════════════════

    private TradeExecutionResult attemptExecution(TradeContext context) {
        ProposalResult proposal = requestProposal(context);

        if (!isRoiAcceptable(proposal, context)) {
            logRoiRejection(context, proposal);
            return TradeExecutionResult.skippedByRoi();
        }

        return executeBuy(context, proposal);
    }

    // ═══════════════════════════════════════════════════════════════
    // Proposal
    // ═══════════════════════════════════════════════════════════════

    private ProposalResult requestProposal(TradeContext context) {
        JsonNode proposalMsg = marketDataService.requestProposal(
                        context.symbol(),
                        context.contractType(),
                        context.amount(),
                        context.currency(),
                        context.duration(),
                        context.durationUnit())
                .orTimeout(PROPOSAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .join();

        JsonNode proposal = proposalMsg.get("proposal");

        if (proposal == null || !proposal.isObject()) {
            throw new TradeExecutionException(
                    "Invalid proposal response: " + proposalMsg);
        }

        return parseProposal(context, proposal);
    }

    private ProposalResult parseProposal(
            TradeContext context,
            JsonNode proposal
    ) {
        String proposalId = proposal.path("id").asText("");
        double askPrice   = proposal.path("ask_price").asDouble(Double.NaN);
        double payout     = proposal.path("payout").asDouble(Double.NaN);

        if (proposalId.isBlank() || !Double.isFinite(askPrice)) {
            throw new TradeExecutionException(
                    "Proposal missing id or ask_price: " + proposal);
        }

        double expectedProfit = Double.isFinite(payout)
                ? (payout - askPrice)
                : Double.NaN;

        double expectedRoiPct =
                (Double.isFinite(expectedProfit) && askPrice > 0.0)
                        ? (expectedProfit / askPrice) * 100.0
                        : Double.NaN;

        ProposalResult result = new ProposalResult(
                proposalId, askPrice, payout, expectedRoiPct);

        log.info("TRADE PROPOSAL OK | symbol={} | contractType={} | {}",
                context.symbol(),
                context.contractType(),
                result.toLogString());

        return result;
    }

    /**
     * Valida se o ROI do proposal atende ao mínimo configurado por ativo.
     *
     * Atualização v5.4:
     * O valor mínimo vem do TradeContext (configurado no strategies.json)
     * em vez de uma constante hardcoded. Isso permite filtros diferentes
     * por ativo — ex: 70% para pares voláteis, 65% para pares estáveis.
     */
    private boolean isRoiAcceptable(
            ProposalResult proposal,
            TradeContext context
    ) {
        if (!proposal.hasValidRoi()) return true;
        return proposal.expectedRoiPct() >= context.minRoiPercent();
    }

    // ═══════════════════════════════════════════════════════════════
    // Buy
    // ═══════════════════════════════════════════════════════════════

    private TradeExecutionResult executeBuy(
            TradeContext context,
            ProposalResult proposal
    ) {
        JsonNode buyMsg = marketDataService
                .buy(proposal.proposalId(), context.amount())
                .orTimeout(BUY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .join();

        JsonNode buy = buyMsg.get("buy");

        if (buy == null || !buy.isObject()) {
            throw new TradeExecutionException(
                    "Invalid buy response: " + buyMsg);
        }

        return parseBuyResponse(context, buy);
    }

    private TradeExecutionResult parseBuyResponse(
            TradeContext context,
            JsonNode buy
    ) {
        long   contractId = buy.path("contract_id").asLong(-1);
        double buyPrice   = buy.path("buy_price").asDouble(Double.NaN);

        if (contractId <= 0) {
            throw new TradeExecutionException(
                    "Buy response missing contract_id: " + buy);
        }

        log.info("TRADE BUY OK | symbol={} | contract_id={} | buy_price={}",
                context.symbol(),
                contractId,
                Double.isFinite(buyPrice) ? buyPrice : "N/A");

        return TradeExecutionResult.success(contractId, buyPrice);
    }

    // ═══════════════════════════════════════════════════════════════
    // Logs
    // ═══════════════════════════════════════════════════════════════

    /**
     * Atualização v5.4:
     * Log agora exibe o minRoiPercent configurado para o ativo
     * em vez do valor hardcoded anterior.
     */
    private void logRoiRejection(
            TradeContext context,
            ProposalResult proposal
    ) {
        log.info("TRADE SKIPPED | symbol={} | reason=ROI below minimum "
                        + "| roiPct={} | minRoiPercent={}",
                context.symbol(),
                proposal.hasValidRoi()
                        ? String.format("%.2f", proposal.expectedRoiPct())
                        : "N/A",
                String.format("%.2f", context.minRoiPercent()));
    }

    // ═══════════════════════════════════════════════════════════════
    // Utilitários
    // ═══════════════════════════════════════════════════════════════

    private boolean isPriceMovedError(String errorMessage) {
        if (errorMessage == null) return false;
        return errorMessage.contains("underlying market has moved too much")
                || errorMessage.contains("contract payout has changed")
                || errorMessage.contains("pricemoved");
    }

    private String extractErrorMessage(Exception e) {
        Throwable cause =
                (e instanceof CompletionException && e.getCause() != null)
                        ? e.getCause()
                        : e;

        if (cause instanceof DerivErrorException) {
            return cause.getMessage().toLowerCase();
        }

        String msg = cause.getMessage();
        return msg != null ? msg.toLowerCase() : "unknown error";
    }

    private void waitBeforeRetry() {
        try {
            Thread.sleep(PRICE_MOVED_RETRY_DELAY_MS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}