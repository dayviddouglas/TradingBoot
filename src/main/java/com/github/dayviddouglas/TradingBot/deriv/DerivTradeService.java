package com.github.dayviddouglas.TradingBot.deriv;

import com.github.dayviddouglas.TradingBot.config.StrategiesProfile;
import com.github.dayviddouglas.TradingBot.deriv.trade.*;
import com.github.dayviddouglas.TradingBot.exceptions.DerivErrorException;
import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.model.Signal;
import com.github.dayviddouglas.TradingBot.risk.AtrRiskDecision;
import com.github.dayviddouglas.TradingBot.risk.AtrRiskManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletionException;

/**
 * Orquestrador principal do ciclo de vida de um trade na plataforma Deriv.
 *
 * Responsabilidade única: coordenar os componentes especializados
 * para transformar um sinal do StrategyEngine em um contrato executado.
 *
 * Fluxo orquestrado:
 * 1. TradeValidator      → valida se pode operar
 * 2. AtrRiskManager      → avalia risco e ajusta stake
 * 3. TradeContextFactory → constrói o contexto da operação
 * 4. TradeExecutor       → executa proposal → ROI check → buy
 * 5. TradeStateRegistry  → registra contrato aberto
 * 6. TradeMonitor        → monitora via stream + watchdog
 * 7. TradeErrorHandler   → classifica e trata erros
 *
 * Correção v5.3:
 * - Removidas todas as chamadas a ensureAuthorized() e AUTH_TIMEOUT.
 *   Com o novo modelo OTP da Deriv, a sessão WebSocket já está
 *   autenticada desde a conexão — não há estado de autorização
 *   para verificar ou renovar em nenhum ponto do fluxo de trade.
 * - checkTradeAvailability() não chama mais ensureAuthorized()
 *   antes do proposal.
 */
@Service
public class DerivTradeService {

    private static final Logger log =
            LoggerFactory.getLogger(DerivTradeService.class);

    private final DerivMarketDataService marketDataService;
    private final AtrRiskManager         atrRiskManager;
    private final TradeValidator         validator;
    private final TradeContextFactory    contextFactory;
    private final TradeExecutor          executor;
    private final TradeMonitor           monitor;
    private final TradeErrorHandler      errorHandler;
    private final TradeStateRegistry     registry;

    public DerivTradeService(
            DerivMarketDataService marketDataService,
            AtrRiskManager atrRiskManager,
            TradeValidator validator,
            TradeContextFactory contextFactory,
            TradeExecutor executor,
            TradeMonitor monitor,
            TradeErrorHandler errorHandler,
            TradeStateRegistry registry
    ) {
        this.marketDataService = marketDataService;
        this.atrRiskManager    = atrRiskManager;
        this.validator         = validator;
        this.contextFactory    = contextFactory;
        this.executor          = executor;
        this.monitor           = monitor;
        this.errorHandler      = errorHandler;
        this.registry          = registry;

        this.marketDataService.onOpenContract(monitor::handleStreamUpdate);
    }

    // ═══════════════════════════════════════════════════════════════
    // Ponto de entrada principal
    // ═══════════════════════════════════════════════════════════════

    /**
     * Processa um sinal final emitido pelo StrategyEngine.
     *
     * Executa as validações iniciais na thread do WebSocket (rápido)
     * e despacha a execução para uma virtual thread (I/O bound).
     *
     * @param profile    configuração do ativo
     * @param signal     sinal final (BUY/SELL)
     * @param recentBars barras recentes para avaliação de risco ATR
     */
    public void onFinalSignal(
            StrategiesProfile profile,
            Signal signal,
            List<Bar> recentBars
    ) {
        TradeState state = registry.getOrCreate(
                profile != null ? profile.getSymbol() : null);

        ValidationResult validation =
                validator.validate(profile, signal, state);

        if (!validation.shouldProceed()) return;

        state.markPending();

        AtrRiskDecision riskDecision =
                evaluateRisk(profile, signal, recentBars);

        if (!riskDecision.allowTrade()) {
            logRiskBlock(profile.getSymbol(), riskDecision);
            state.resetToIdle();
            return;
        }

        logRiskResult(profile.getSymbol(), riskDecision);

        TradeContext context = contextFactory.create(
                profile, signal, riskDecision.adjustedAmount());

        if (context.amount() <= 0.0) {
            log.warn("TRADE SKIP | symbol={} | reason=stake invalid "
                            + "after normalization",
                    profile.getSymbol());
            state.resetToIdle();
            return;
        }

        state.applyContext(context);

        logTradeStart(context);

        Thread.startVirtualThread(() -> executeAsync(state, context));
    }

    // ═══════════════════════════════════════════════════════════════
    // Execução assíncrona
    // ═══════════════════════════════════════════════════════════════

    private void executeAsync(TradeState state, TradeContext context) {
        try {
            TradeExecutionResult result = executor.execute(context);

            if (result.isSkippedByRoi()) {
                state.resetToIdle();
                return;
            }

            if (!result.isSuccess()) {
                state.resetToIdle();
                return;
            }

            long contractId = result.contractId();
            state.markOpen(contractId);
            registry.registerContract(contractId, state);

            monitor.startMonitoring(state, context);

        } catch (Exception e) {
            String errorMessage = extractErrorMessage(e);
            errorHandler.handle(state, errorMessage, e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Avaliação de risco
    // ═══════════════════════════════════════════════════════════════

    private AtrRiskDecision evaluateRisk(
            StrategiesProfile profile,
            Signal signal,
            List<Bar> recentBars
    ) {
        List<String> decisionStrategies =
                extractDecisionStrategies(signal);

        return atrRiskManager.evaluate(
                profile.getSymbol(),
                recentBars,
                profile.getTrade().getAmount(),
                decisionStrategies
        );
    }

    @SuppressWarnings("unchecked")
    private List<String> extractDecisionStrategies(Signal signal) {
        if (signal.getMetadata() == null) return List.of();

        Object raw = signal.getMetadata().get("decisionStrategies");
        if (raw instanceof List<?> list) {
            return list.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(Object::toString)
                    .toList();
        }

        return List.of();
    }

    // ═══════════════════════════════════════════════════════════════
    // Validação de disponibilidade (bootstrap)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Verifica se um ativo suporta o tipo de contrato e parâmetros
     * configurados. Usado pelo BotInitializer durante o bootstrap.
     *
     * Correção v5.3:
     * Removida a chamada a ensureAuthorized() que existia antes do
     * proposal. Com o modelo OTP, a sessão já está autenticada desde
     * a conexão WebSocket.
     *
     * @param symbol       símbolo do ativo
     * @param contractType tipo de contrato (CALL ou PUT)
     * @param amount       valor do stake
     * @param currency     moeda
     * @param duration     duração do contrato
     * @param durationUnit unidade de duração
     * @return true se o ativo suporta os parâmetros
     */
    public boolean checkTradeAvailability(
            String symbol,
            String contractType,
            double amount,
            String currency,
            int duration,
            String durationUnit
    ) {
        try {
            var proposalMsg = marketDataService
                    .requestProposal(
                            symbol,
                            contractType,
                            amount,
                            currency,
                            duration,
                            durationUnit)
                    .orTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .join();

            var proposal = proposalMsg.get("proposal");
            return proposal != null
                    && proposal.isObject()
                    && !proposal.path("id").asText("").isBlank();

        } catch (Exception e) {
            logAvailabilityCheckFailure(
                    symbol, contractType, duration, durationUnit, e);
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Logs
    // ═══════════════════════════════════════════════════════════════

    private void logRiskBlock(String symbol, AtrRiskDecision risk) {
        log.info("ATR RISK BLOCK | symbol={} | type={} | atrFast={} "
                        + "| atrBaseline={} | atrRatio={} | reason={}",
                symbol,
                risk.confluenceType(),
                risk.atrFast(),
                risk.atrBaseline(),
                risk.atrRatio(),
                risk.reason());
    }

    private void logRiskResult(String symbol, AtrRiskDecision risk) {
        if (risk.isStakeReduced()) {
            log.info("ATR RISK REDUCE | symbol={} | type={} | atrRatio={} "
                            + "| amount={} | reason={}",
                    symbol,
                    risk.confluenceType(),
                    risk.atrRatio(),
                    risk.adjustedAmount(),
                    risk.reason());
        } else {
            log.info("ATR RISK OK | symbol={} | type={} | atrRatio={} "
                            + "| amount={} | reason={}",
                    symbol,
                    risk.confluenceType(),
                    risk.atrRatio(),
                    risk.adjustedAmount(),
                    risk.reason());
        }
    }

    private void logTradeStart(TradeContext context) {
        log.info("TRADE START | symbol={} | signal={} | contractType={} "
                        + "| amount={} {} | duration={}{}",
                context.symbol(),
                context.signalType(),
                context.contractType(),
                context.amount(),
                context.currency(),
                context.duration(),
                context.durationUnit());
    }

    private void logAvailabilityCheckFailure(
            String symbol,
            String contractType,
            int duration,
            String durationUnit,
            Exception e
    ) {
        Throwable cause =
                (e instanceof CompletionException && e.getCause() != null)
                        ? e.getCause() : e;

        if (cause instanceof DerivErrorException derr) {
            log.warn("TRADE AVAILABILITY CHECK FAILED | symbol={} "
                            + "| contractType={} | duration={}{} | reason={}",
                    symbol, contractType,
                    duration, durationUnit,
                    derr.getMessage());
        } else {
            log.warn("TRADE AVAILABILITY CHECK ERROR | symbol={} "
                            + "| contractType={} | duration={}{} | reason={}",
                    symbol, contractType,
                    duration, durationUnit,
                    cause.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Utilitários
    // ═══════════════════════════════════════════════════════════════

    private String extractErrorMessage(Exception e) {
        Throwable cause =
                (e instanceof CompletionException && e.getCause() != null)
                        ? e.getCause() : e;

        if (cause instanceof DerivErrorException) {
            return cause.getMessage().toLowerCase();
        }

        String msg = cause.getMessage();
        return msg != null ? msg.toLowerCase() : "unknown error";
    }
}