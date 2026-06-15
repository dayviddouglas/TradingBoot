package com.github.dayviddouglas.TradingBot.deriv;

import com.github.dayviddouglas.TradingBot.config.strategy.StrategiesProfile;
import com.github.dayviddouglas.TradingBot.deriv.trade.context.TradeContext;
import com.github.dayviddouglas.TradingBot.deriv.trade.context.TradeContextFactory;
import com.github.dayviddouglas.TradingBot.deriv.trade.context.TradeErrorHandler;
import com.github.dayviddouglas.TradingBot.deriv.trade.execution.TradeExecutionResult;
import com.github.dayviddouglas.TradingBot.deriv.trade.execution.TradeExecutor;
import com.github.dayviddouglas.TradingBot.deriv.trade.monitor.TradeMonitor;
import com.github.dayviddouglas.TradingBot.deriv.trade.monitor.TradeState;
import com.github.dayviddouglas.TradingBot.deriv.trade.monitor.TradeStateRegistry;
import com.github.dayviddouglas.TradingBot.deriv.trade.validation.TradeValidator;
import com.github.dayviddouglas.TradingBot.deriv.trade.validation.ValidationResult;
import com.github.dayviddouglas.TradingBot.engine.regime.RegimeRegistry;
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
 * Coordena os componentes especializados para transformar um sinal emitido pelo
 * {@code StrategyEngine} em um contrato executado e monitorado até o fechamento.
 *
 * Fluxo orquestrado em {@link #onFinalSignal}:
 * <ol>
 *   <li>{@link TradeValidator} — valida se o sinal pode prosseguir para execução</li>
 *   <li>{@link AtrRiskManager} — avalia o risco pelo ATR e retorna o stake ajustado</li>
 *   <li>{@link RegimeRegistry} — consulta o regime de mercado confirmado do ativo</li>
 *   <li>{@link TradeContextFactory} — constrói o contexto imutável da operação</li>
 *   <li>{@link TradeExecutor} — executa o ciclo proposal → ROI check → buy em virtual thread</li>
 *   <li>{@link TradeStateRegistry} — registra o contrato aberto para monitoramento</li>
 *   <li>{@link TradeMonitor} — monitora o contrato via stream WebSocket e watchdog</li>
 *   <li>{@link TradeErrorHandler} — classifica e trata erros ocorridos na execução</li>
 * </ol>
 *
 * A autenticação ocorre na URL do WebSocket via OTP antes da conexão ser estabelecida.
 * Não há verificação de estado de autorização neste serviço — o canal já está autenticado.
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

    /** Consultado em cada sinal para obter o regime confirmado pelo filtro de persistência. */
    private final RegimeRegistry         regimeRegistry;

    /**
     * Inicializa o serviço e registra o {@link TradeMonitor} como handler de atualizações
     * de contratos abertos no {@link DerivMarketDataService}.
     *
     * @param marketDataService abstração da API Deriv; fornece proposal, buy e stream de contratos
     * @param atrRiskManager    avalia risco por ATR e decide se o trade pode ser executado
     * @param validator         valida as pré-condições antes de cada operação
     * @param contextFactory    constrói o {@link TradeContext} imutável da operação
     * @param executor          executa o ciclo proposal → ROI check → buy
     * @param monitor           monitora contratos abertos via stream e watchdog
     * @param errorHandler      classifica e trata erros ocorridos durante a execução
     * @param registry          gerencia os estados operacionais por ativo e por contrato
     * @param regimeRegistry    fornece o regime de mercado confirmado por ativo
     */
    public DerivTradeService(
            DerivMarketDataService marketDataService,
            AtrRiskManager atrRiskManager,
            TradeValidator validator,
            TradeContextFactory contextFactory,
            TradeExecutor executor,
            TradeMonitor monitor,
            TradeErrorHandler errorHandler,
            TradeStateRegistry registry,
            RegimeRegistry regimeRegistry
    ) {
        this.marketDataService = marketDataService;
        this.atrRiskManager    = atrRiskManager;
        this.validator         = validator;
        this.contextFactory    = contextFactory;
        this.executor          = executor;
        this.monitor           = monitor;
        this.errorHandler      = errorHandler;
        this.registry          = registry;
        this.regimeRegistry    = regimeRegistry;

        // Registra o TradeMonitor para receber todas as atualizações de contratos abertos
        this.marketDataService.onOpenContract(monitor::handleStreamUpdate);
    }

    // ═══════════════════════════════════════════════════════════════
    // Ponto de entrada principal
    // ═══════════════════════════════════════════════════════════════

    /**
     * Processa um sinal final emitido pelo {@code StrategyEngine}.
     *
     * As validações iniciais e a avaliação de risco são executadas na thread do chamador.
     * A execução do contrato (I/O bound) é despachada para uma virtual thread separada,
     * liberando a thread do WebSocket imediatamente após o despacho.
     *
     * O regime confirmado é consultado do {@link RegimeRegistry} antes da criação do
     * {@link TradeContext}, garantindo que o campo {@code regime} seja preenchido
     * corretamente em qualquer {@code DecisionMode}. O fallback é {@code CHOPPY},
     * retornado pelo registry quando ainda não há regime confirmado para o ativo.
     *
     * @param profile    configuração do ativo lida do strategies.json
     * @param signal     sinal final com tipo BUY ou SELL
     * @param recentBars barras recentes utilizadas pelo {@link AtrRiskManager} para cálculo de ATR
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

        // Consulta o regime confirmado; retorna CHOPPY como padrão conservador se não confirmado
        String confirmedRegime = regimeRegistry
                .getRegime(profile.getSymbol())
                .name();

        TradeContext context = contextFactory.create(
                profile, signal,
                riskDecision.adjustedAmount(),
                confirmedRegime);

        // Stake inválido após normalização indica problema na configuração do ativo
        if (context.amount() <= 0.0) {
            log.warn("TRADE SKIP | symbol={} | reason=stake invalid "
                            + "after normalization",
                    profile.getSymbol());
            state.resetToIdle();
            return;
        }

        state.applyContext(context);

        logTradeStart(context);

        // Execução em virtual thread para não bloquear a thread do WebSocket
        Thread.startVirtualThread(() -> executeAsync(state, context));
    }

    // ═══════════════════════════════════════════════════════════════
    // Execução assíncrona
    // ═══════════════════════════════════════════════════════════════

    /**
     * Executa o ciclo completo de abertura do contrato em virtual thread.
     * Delega ao {@link TradeExecutor} e, em caso de sucesso, registra o contrato
     * no {@link TradeStateRegistry} e inicia o monitoramento pelo {@link TradeMonitor}.
     * Erros são classificados e tratados pelo {@link TradeErrorHandler}.
     *
     * @param state   estado do ativo com contexto da operação já aplicado
     * @param context contexto imutável da operação construído pelo {@link TradeContextFactory}
     */
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

            // Registra o contrato para que stream e watchdog possam localizar o estado pelo contractId
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

    /**
     * Extrai as estratégias decisoras do metadata do sinal e delega a avaliação de risco
     * ao {@link AtrRiskManager}, que retorna uma decisão binária: executa ou bloqueia.
     *
     * @param profile    configuração do ativo com o stake configurado
     * @param signal     sinal com metadata contendo as estratégias decisoras
     * @param recentBars barras recentes para cálculo do ATR
     * @return decisão de risco com stake ajustado e indicador de bloqueio
     */
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

    /**
     * Extrai a lista de estratégias que participaram da decisão do metadata do sinal.
     * Retorna lista vazia quando o metadata for nulo ou o campo estiver ausente.
     *
     * @param signal sinal com metadata populado pelo {@code SignalEmitter}
     * @return lista de nomes das estratégias decisoras ou lista vazia
     */
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
     * Verifica se um ativo suporta o tipo de contrato e os parâmetros configurados,
     * enviando um proposal de teste à API Deriv.
     * Utilizado pelo {@code BotInitializer} durante o bootstrap para filtrar ativos
     * com configurações incompatíveis antes de iniciar a operação.
     *
     * @param symbol       símbolo do ativo a ser verificado
     * @param contractType tipo de contrato: {@code CALL} ou {@code PUT}
     * @param amount       valor do stake do proposal de teste
     * @param currency     moeda do stake
     * @param duration     duração numérica do contrato
     * @param durationUnit unidade de duração do contrato
     * @return {@code true} se a API retornar um proposal válido com {@code id} preenchido
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

    /**
     * Registra em log o bloqueio da operação pelo {@link AtrRiskManager},
     * incluindo os valores de ATR e o motivo do bloqueio.
     */
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

    /**
     * Registra em log o resultado da avaliação de risco quando a operação é liberada.
     */
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

    /**
     * Registra em log o início de uma operação com todos os parâmetros relevantes,
     * incluindo o regime confirmado para rastreabilidade da decisão de entrada.
     * Exibe {@code "UNKNOWN"} quando o regime não estiver disponível no contexto.
     */
    private void logTradeStart(TradeContext context) {
        log.info("TRADE START | symbol={} | signal={} | contractType={} "
                        + "| amount={} {} | duration={}{} | regime={}",
                context.symbol(),
                context.signalType(),
                context.contractType(),
                context.amount(),
                context.currency(),
                context.duration(),
                context.durationUnit(),
                context.regime().isBlank() ? "UNKNOWN" : context.regime());
    }

    /**
     * Registra em log a falha na verificação de disponibilidade de um ativo.
     * Diferencia erros da API Deriv ({@link DerivErrorException}) de erros genéricos
     * para facilitar o diagnóstico durante o bootstrap.
     */
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

    /**
     * Extrai a mensagem de erro normalizada em lowercase da exceção capturada.
     * Quando a exceção for {@link CompletionException}, extrai a causa raiz.
     * Quando a causa for {@link DerivErrorException}, usa sua mensagem diretamente.
     *
     * @param e exceção capturada no fluxo de execução assíncrona
     * @return mensagem de erro em lowercase, ou {@code "unknown error"} se ausente
     */
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