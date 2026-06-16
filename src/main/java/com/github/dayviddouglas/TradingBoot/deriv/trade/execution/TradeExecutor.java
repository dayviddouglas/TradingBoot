package com.github.dayviddouglas.TradingBoot.deriv.trade.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.dayviddouglas.TradingBoot.deriv.DerivMarketDataService;
import com.github.dayviddouglas.TradingBoot.deriv.trade.context.TradeContext;
import com.github.dayviddouglas.TradingBoot.exceptions.DerivErrorException;
import com.github.dayviddouglas.TradingBoot.exceptions.TradeExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * Responsável pela execução do ciclo completo de abertura de contrato:
 * proposal → validação de ROI → buy.
 *
 * Solicita o proposal à API Deriv, valida se o ROI retornado atende ao limiar
 * mínimo configurado por ativo no {@link TradeContext}, e em caso positivo
 * executa a compra do contrato. O ROI mínimo é lido diretamente do
 * {@link TradeContext}, que o carrega do {@code TradeConfig} do ativo,
 * eliminando qualquer valor fixo nesta classe.
 *
 * Implementa retry automático para erros do tipo {@code PriceMoved}:
 * na primeira ocorrência, aguarda {@value PRICE_MOVED_RETRY_DELAY_MS}ms
 * e tenta novamente; na segunda falha ou em qualquer outro tipo de erro,
 * propaga {@link TradeExecutionException}.
 *
 * Esta classe é stateless e segura para uso compartilhado entre threads.
 */
@Component
public class TradeExecutor {

    private static final Logger log =
            LoggerFactory.getLogger(TradeExecutor.class);

    /** Número máximo de retentativas permitidas para erros PriceMoved. */
    private static final int  MAX_PRICE_MOVED_RETRIES    = 1;

    /** Tempo de espera em milissegundos antes de retentar após PriceMoved. */
    private static final long PRICE_MOVED_RETRY_DELAY_MS = 300;

    /** Timeout máximo aguardado para resposta do endpoint proposal. */
    private static final Duration PROPOSAL_TIMEOUT = Duration.ofSeconds(12);

    /** Timeout máximo aguardado para resposta do endpoint buy. */
    private static final Duration BUY_TIMEOUT      = Duration.ofSeconds(12);

    private final DerivMarketDataService marketDataService;

    /**
     * @param marketDataService utilizado para enviar os requests de proposal e buy à API Deriv
     */
    public TradeExecutor(DerivMarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    /**
     * Executa o ciclo completo: proposal → validação de ROI → buy.
     *
     * O ROI mínimo aceitável é lido do {@link TradeContext}, que carrega o valor
     * configurado por ativo no strategies.json. Quando o ROI retornado pelo proposal
     * estiver abaixo do limiar, retorna {@link TradeExecutionResult#skippedByRoi()}
     * sem lançar exceção.
     *
     * Em caso de erro {@code PriceMoved}, aguarda {@value PRICE_MOVED_RETRY_DELAY_MS}ms
     * e realiza até {@value MAX_PRICE_MOVED_RETRIES} retentativa antes de propagar a exceção.
     *
     * @param context contexto completo da operação, incluindo símbolo, stake, ROI mínimo e duração
     * @return resultado da execução contendo {@code contractId} e {@code buyPrice} em caso de sucesso
     * @throws TradeExecutionException se a execução falhar definitivamente após as retentativas
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

    /**
     * Realiza uma única tentativa do ciclo proposal → ROI check → buy.
     * Retorna {@link TradeExecutionResult#skippedByRoi()} se o ROI for insuficiente,
     * ou o resultado do buy em caso de aprovação.
     */
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

    /**
     * Envia o request de proposal à API Deriv e retorna o resultado parseado.
     * Aguarda a resposta com timeout de {@link #PROPOSAL_TIMEOUT}.
     *
     * @param context contexto da operação com os parâmetros do contrato
     * @return resultado do proposal com proposalId, askPrice, payout e ROI esperado
     * @throws TradeExecutionException se a resposta não contiver o objeto {@code proposal}
     */
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

    /**
     * Extrai os campos do nó {@code proposal} e calcula o ROI esperado.
     * O ROI é calculado como {@code (payout - askPrice) / askPrice * 100}.
     * Quando {@code payout} ou {@code askPrice} forem inválidos, o ROI resultante é {@code NaN}.
     *
     * @param context  contexto da operação, utilizado apenas para o log
     * @param proposal nó JSON do objeto {@code proposal} retornado pela API
     * @return {@link ProposalResult} com os dados extraídos
     * @throws TradeExecutionException se {@code id} ou {@code ask_price} estiverem ausentes ou inválidos
     */
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

        // ROI calculado sobre o askPrice; NaN quando payout ou askPrice forem inválidos
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
     * Verifica se o ROI retornado pelo proposal atende ao limiar mínimo configurado para o ativo.
     * Quando o ROI não é calculável ({@code NaN}), a validação é considerada aprovada
     * para não bloquear execuções com payout indisponível no momento do proposal.
     *
     * @param proposal resultado do proposal com o ROI calculado
     * @param context  contexto da operação com o limiar mínimo por ativo
     * @return {@code true} se o ROI for válido e maior ou igual ao mínimo, ou se for NaN
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

    /**
     * Envia o request de buy à API Deriv usando o {@code proposalId} aprovado na etapa anterior.
     * Aguarda a resposta com timeout de {@link #BUY_TIMEOUT}.
     *
     * @param context  contexto da operação, utilizado para o amount e logs
     * @param proposal resultado do proposal aprovado pelo filtro de ROI
     * @return {@link TradeExecutionResult} com o contractId e buyPrice confirmados pela API
     * @throws TradeExecutionException se a resposta não contiver o objeto {@code buy}
     */
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

    /**
     * Extrai {@code contract_id} e {@code buy_price} do nó {@code buy} retornado pela API.
     *
     * @param context contexto da operação, utilizado apenas para o log
     * @param buy     nó JSON do objeto {@code buy} retornado pela API
     * @return {@link TradeExecutionResult} com status SUCCESS
     * @throws TradeExecutionException se {@code contract_id} estiver ausente ou inválido
     */
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
     * Registra em log a rejeição da operação por ROI insuficiente,
     * exibindo o ROI retornado e o mínimo configurado para o ativo.
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

    /**
     * Verifica se a mensagem de erro indica que o preço de mercado se moveu
     * além do tolerado pelo proposal original, tornando o contrato inválido.
     *
     * @param errorMessage mensagem de erro normalizada em lowercase
     * @return {@code true} se a mensagem corresponder a um erro PriceMoved
     */
    private boolean isPriceMovedError(String errorMessage) {
        if (errorMessage == null) return false;
        return errorMessage.contains("underlying market has moved too much")
                || errorMessage.contains("contract payout has changed")
                || errorMessage.contains("pricemoved");
    }

    /**
     * Extrai a mensagem de erro normalizada em lowercase da exceção capturada.
     * Quando a exceção for {@link CompletionException}, extrai a causa raiz.
     * Quando a causa for {@link DerivErrorException}, usa sua mensagem diretamente.
     *
     * @param e exceção capturada no loop de execução
     * @return mensagem de erro em lowercase, ou {@code "unknown error"} se ausente
     */
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

    /**
     * Aguarda o intervalo configurado antes de retentar após um erro PriceMoved.
     * Restaura o flag de interrupção da thread caso o sleep seja interrompido.
     */
    private void waitBeforeRetry() {
        try {
            Thread.sleep(PRICE_MOVED_RETRY_DELAY_MS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}