package com.github.dayviddouglas.TradingBot.engine.core;

import com.github.dayviddouglas.TradingBot.config.strategy.StrategiesProfile;
import com.github.dayviddouglas.TradingBot.engine.decision.DecisionEvaluator;
import com.github.dayviddouglas.TradingBot.engine.decision.DecisionEvaluatorFactory;
import com.github.dayviddouglas.TradingBot.engine.decision.DecisionMode;
import com.github.dayviddouglas.TradingBot.engine.decision.EvaluationResult;
import com.github.dayviddouglas.TradingBot.engine.filter.VolatilityFilter;
import com.github.dayviddouglas.TradingBot.engine.regime.MarketRegime;
import com.github.dayviddouglas.TradingBot.engine.regime.MarketRegimeMonitor;
import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.model.Signal;
import com.github.dayviddouglas.TradingBot.strategy.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * Motor central de decisão do sistema de trading.
 *
 * Orquestra o pipeline de avaliação para cada candle recebido do
 * {@link com.github.dayviddouglas.TradingBot.market.TickCandleAggregator},
 * delegando para componentes especializados:
 * <ul>
 *   <li>{@link BarHistory} — gerencia o histórico local de candles com deduplicação</li>
 *   <li>{@link VolatilityFilter} — filtra candles sem movimento suficiente</li>
 *   <li>{@link DecisionEvaluator} — avalia as estratégias e produz o {@link EvaluationResult}</li>
 *   <li>{@link SignalEmitter} — constrói e emite o sinal final com anti-repetição</li>
 *   <li>{@link MarketRegimeMonitor} — monitora e atualiza o regime em background (opcional)</li>
 * </ul>
 *
 * Pipeline por candle em {@link #onBar}:
 * <ol>
 *   <li>{@link BarHistory#accept} — aceita ou rejeita o candle</li>
 *   <li>{@link BarHistory#alreadyProcessed} — evita reprocessamento</li>
 *   <li>{@link #notifyRegimeMonitor} — notifica o monitor de regime com o snapshot atualizado</li>
 *   <li>{@link VolatilityFilter#isAcceptable} — filtra mercado parado</li>
 *   <li>{@link DecisionEvaluator#evaluate} — avalia as estratégias habilitadas</li>
 *   <li>{@link SignalEmitter#tryEmit} — emite o sinal se válido e não repetido</li>
 * </ol>
 *
 * O {@link MarketRegimeMonitor} é opcional: quando {@code null}, o engine opera sem
 * monitoramento de regime, mantendo compatibilidade com o {@code SimpleBacktester}
 * que constrói engines sem acesso ao contexto Spring.
 *
 * Todos os métodos de processamento são {@code synchronized} pois {@link #onBar} e
 * {@link #seedHistory} podem ser invocados de threads diferentes.
 */
public class StrategyEngine {

    private static final Logger log = LoggerFactory.getLogger(StrategyEngine.class);

    private final String symbol;
    private final BarHistory barHistory;
    private final VolatilityFilter volatilityFilter;
    private final DecisionEvaluator decisionEvaluator;
    private final SignalEmitter signalEmitter;
    private final List<TradingStrategy> strategies;

    /**
     * Monitor de regime injetado pelo {@code PipelineRegistry} no runtime.
     * {@code null} no backtest — a verificação {@code if (regimeMonitor != null)}
     * garante compatibilidade retroativa sem alterar o comportamento do backtest.
     */
    private final MarketRegimeMonitor regimeMonitor;

    // ═══════════════════════════════════════════════════════════════
    // Construtores
    // ═══════════════════════════════════════════════════════════════

    /**
     * Construtor com {@link VolatilityFilter} padrão e sem monitor de regime.
     * Mantém compatibilidade retroativa com o {@code SimpleBacktester}.
     *
     * @param symbol       símbolo do ativo
     * @param maxBars      tamanho máximo do histórico local
     * @param strategies   estratégias habilitadas para este ativo
     * @param decisionMode modo de decisão configurado no strategies.json
     */
    public StrategyEngine(
            String symbol,
            int maxBars,
            List<TradingStrategy> strategies,
            DecisionMode decisionMode
    ) {
        this(symbol, maxBars, strategies, decisionMode,
                new VolatilityFilter(), null);
    }

    /**
     * Construtor com {@link VolatilityFilter} configurável e sem monitor de regime.
     * Mantém compatibilidade retroativa com o {@code SimpleBacktester}.
     *
     * @param symbol          símbolo do ativo
     * @param maxBars         tamanho máximo do histórico local
     * @param strategies      estratégias habilitadas para este ativo
     * @param decisionMode    modo de decisão configurado no strategies.json
     * @param volatilityFilter filtro de volatilidade com parâmetros customizados
     */
    public StrategyEngine(
            String symbol,
            int maxBars,
            List<TradingStrategy> strategies,
            DecisionMode decisionMode,
            VolatilityFilter volatilityFilter
    ) {
        this(symbol, maxBars, strategies, decisionMode,
                volatilityFilter, null);
    }

    /**
     * Construtor completo com {@link MarketRegimeMonitor}.
     * Utilizado pelo {@code PipelineRegistry} no runtime para injetar o monitor de regime,
     * responsável por notificar o {@code RegimeRegistry} e o {@code RegimeHistoryService}
     * sobre mudanças confirmadas de regime.
     *
     * @param symbol           símbolo do ativo
     * @param maxBars          tamanho máximo do histórico local
     * @param strategies       estratégias habilitadas para este ativo
     * @param decisionMode     modo de decisão: {@code SINGLE_STRATEGY}, {@code VOTING} ou {@code CONFLUENCE}
     * @param volatilityFilter filtro de volatilidade configurado; substituído pelo padrão se {@code null}
     * @param regimeMonitor    monitor de regime; {@code null} desabilita o monitoramento (backtest)
     */
    public StrategyEngine(
            String symbol,
            int maxBars,
            List<TradingStrategy> strategies,
            DecisionMode decisionMode,
            VolatilityFilter volatilityFilter,
            MarketRegimeMonitor regimeMonitor
    ) {
        validateInputs(symbol, strategies, decisionMode);

        this.symbol = symbol;
        this.strategies = List.copyOf(strategies);
        this.volatilityFilter = volatilityFilter != null
                ? volatilityFilter
                : new VolatilityFilter();
        this.regimeMonitor = regimeMonitor;

        this.barHistory = new BarHistory(maxBars);
        this.decisionEvaluator = DecisionEvaluatorFactory.create(decisionMode);
        this.signalEmitter = new SignalEmitter(
                symbol, decisionMode, this.volatilityFilter);
    }

    // ═══════════════════════════════════════════════════════════════
    // Factory methods
    // ═══════════════════════════════════════════════════════════════

    /**
     * Cria um {@link StrategyEngine} a partir do {@link StrategiesProfile} sem monitor de regime.
     * Utilizado pelo {@code BacktestRunner} para construir engines isolados do contexto Spring.
     *
     * @param profile    perfil completo do ativo lido do strategies.json
     * @param strategies estratégias habilitadas para este ativo
     * @return engine configurado sem monitoramento de regime
     */
    public static StrategyEngine fromProfile(
            StrategiesProfile profile,
            List<TradingStrategy> strategies
    ) {
        VolatilityFilter filter = new VolatilityFilter(
                profile.getRangeLookback(),
                profile.getRangeMultiplier()
        );

        return new StrategyEngine(
                profile.getSymbol(),
                profile.getMaxBars(),
                strategies,
                profile.getDecisionMode(),
                filter,
                null
        );
    }

    /**
     * Cria um {@link StrategyEngine} a partir do {@link StrategiesProfile} com monitor de regime.
     * Utilizado pelo {@code PipelineRegistry} no runtime para construir engines com monitoramento completo.
     *
     * @param profile       perfil completo do ativo lido do strategies.json
     * @param strategies    estratégias habilitadas para este ativo
     * @param regimeMonitor monitor de regime injetado pelo Spring
     * @return engine configurado com monitoramento de regime
     */
    public static StrategyEngine fromProfile(
            StrategiesProfile profile,
            List<TradingStrategy> strategies,
            MarketRegimeMonitor regimeMonitor
    ) {
        VolatilityFilter filter = new VolatilityFilter(
                profile.getRangeLookback(),
                profile.getRangeMultiplier()
        );

        return new StrategyEngine(
                profile.getSymbol(),
                profile.getMaxBars(),
                strategies,
                profile.getDecisionMode(),
                filter,
                regimeMonitor
        );
    }

    // ═══════════════════════════════════════════════════════════════
    // API pública
    // ═══════════════════════════════════════════════════════════════

    /**
     * Registra o callback que receberá os sinais finais emitidos pelo {@link SignalEmitter}.
     *
     * @param handler consumer do sinal final; normalmente implementado pelo {@code SignalHandler}
     */
    public void onFinalSignal(Consumer<Signal> handler) {
        signalEmitter.onFinalSignal(handler);
    }

    /**
     * Inicializa o histórico com candles carregados da API.
     * Substitui completamente o histórico existente e registra o tamanho resultante.
     *
     * @param history lista de candles históricos recebidos do {@code DerivHistoryPaginator}
     */
    public synchronized void seedHistory(List<Bar> history) {
        barHistory.seed(history);
        log.info("HISTORY SEEDED | symbol={} | bars={}", symbol, barHistory.size());
    }

    /**
     * Retorna snapshot imutável do histórico atual.
     * Utilizado pelo {@code DerivTradeService} para avaliação de risco pelo {@code AtrRiskManager}.
     *
     * @return lista imutável de candles em ordem cronológica
     */
    public List<Bar> getBarsSnapshot() {
        return barHistory.snapshot();
    }

    /**
     * Retorna a quantidade atual de candles no histórico.
     *
     * @return número de candles armazenados no {@link BarHistory}
     */
    public int getBarCount() {
        return barHistory.size();
    }

    /**
     * Retorna o símbolo do ativo processado por este engine.
     *
     * @return símbolo do ativo
     */
    public String getSymbol() {
        return symbol;
    }

    // ═══════════════════════════════════════════════════════════════
    // Pipeline de processamento
    // ═══════════════════════════════════════════════════════════════

    /**
     * Processa um novo candle recebido do {@link com.github.dayviddouglas.TradingBot.market.TickCandleAggregator}.
     *
     * Executa o pipeline completo: aceita o candle no {@link BarHistory}, notifica o
     * {@link MarketRegimeMonitor} com o snapshot atualizado — antes da avaliação das estratégias,
     * garantindo que o regime esteja atualizado quando o {@code DerivTradeService} consultar
     * o {@code RegimeRegistry} — e então delega para {@link #evaluate}.
     *
     * @param bar candle OHLC a ser processado; ignorado se {@code null}
     */
    public synchronized void onBar(Bar bar) {
        if (bar == null) return;

        boolean isNewCandle = barHistory.accept(bar);

        if (!isNewCandle) return;
        if (barHistory.alreadyProcessed(bar)) return;

        barHistory.markProcessed(bar);

        // Notifica o monitor de regime antes da avaliação das estratégias,
        // garantindo que o regime esteja atualizado no RegimeRegistry
        notifyRegimeMonitor();

        evaluate();
    }

    // ═══════════════════════════════════════════════════════════════
    // Integração com MarketRegimeMonitor
    // ═══════════════════════════════════════════════════════════════

    /**
     * Encaminha o snapshot atual ao {@link MarketRegimeMonitor}.
     * A verificação de nulidade garante compatibilidade retroativa com o backtest,
     * onde o engine é construído sem monitor de regime.
     * O snapshot é capturado após a aceitação do candle para que o monitor
     * veja o estado mais atualizado do histórico.
     */
    private void notifyRegimeMonitor() {
        if (regimeMonitor == null) return;

        List<Bar> snapshot = barHistory.snapshot();
        regimeMonitor.onBar(symbol, snapshot);
    }

    // ═══════════════════════════════════════════════════════════════
    // Avaliação
    // ═══════════════════════════════════════════════════════════════

    /**
     * Executa a avaliação das estratégias sobre o snapshot atual.
     * Aplica o {@link VolatilityFilter} antes da avaliação; quando bloqueado,
     * reseta o controle de anti-repetição do {@link SignalEmitter} para permitir
     * que o próximo sinal válido seja emitido normalmente após a retomada.
     */
    private void evaluate() {
        List<Bar> snapshot = barHistory.snapshot();

        if (snapshot.isEmpty()) return;

        if (!volatilityFilter.isAcceptable(snapshot, symbol)) {
            signalEmitter.resetLastEmitted();
            return;
        }

        EvaluationResult result = decisionEvaluator.evaluate(
                snapshot, strategies, symbol);

        signalEmitter.tryEmit(result, snapshot);
    }

    // ═══════════════════════════════════════════════════════════════
    // Validação de inicialização
    // ═══════════════════════════════════════════════════════════════

    /**
     * Valida os parâmetros de inicialização do engine, aplicando as mesmas regras
     * verificadas pelo {@code StrategiesProfileValidator}:
     * {@code SINGLE_STRATEGY} exige exatamente 1 estratégia;
     * {@code CONFLUENCE} e {@code VOTING} exigem ao menos 2.
     *
     * @param symbol       símbolo do ativo; não pode ser nulo ou vazio
     * @param strategies   lista de estratégias habilitadas
     * @param decisionMode modo de decisão que determina a regra de quantidade
     * @throws IllegalArgumentException se o símbolo for inválido
     * @throws IllegalStateException    se a quantidade de estratégias for incompatível com o modo
     */
    private void validateInputs(
            String symbol,
            List<TradingStrategy> strategies,
            DecisionMode decisionMode
    ) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol cannot be empty");
        }

        if (decisionMode == DecisionMode.SINGLE_STRATEGY
                && strategies.size() != 1) {
            throw new IllegalStateException(String.format(
                    "SINGLE_STRATEGY requires exactly 1 strategy. " +
                            "symbol=%s strategies=%d",
                    symbol, strategies.size()));
        }

        if ((decisionMode == DecisionMode.CONFLUENCE
                || decisionMode == DecisionMode.VOTING)
                && strategies.size() < 2) {
            throw new IllegalStateException(String.format(
                    "%s requires at least 2 strategies. " +
                            "symbol=%s strategies=%d",
                    decisionMode, symbol, strategies.size()));
        }
    }

    /**
     * Avalia o regime de mercado inicial a partir do histórico já carregado via {@link #seedHistory}.
     *
     * Chamado pelo {@code MultiSymbolDerivBotRunner} após {@code seedHistory()} para garantir
     * que o {@code RegimeRegistry} tenha um regime confirmado antes do bot iniciar as operações
     * em tempo real, eliminando o período inicial em que o regime seria {@code CHOPPY} por padrão.
     *
     * Simula o pipeline completo do {@link MarketRegimeMonitor} percorrendo os candles históricos
     * em ordem cronológica a partir do índice 60, com a mesma lógica de decimação e confirmação
     * utilizada em tempo real. Os contadores internos do monitor ficam no estado correto para
     * continuidade imediata no runtime após o warm-up.
     *
     * Não emite sinais operacionais — apenas aciona o pipeline de regime via
     * {@link MarketRegimeMonitor#onBar}.
     */
    public synchronized void evaluateRegimeFromHistory() {
        if (regimeMonitor == null) return;

        List<Bar> snapshot = barHistory.snapshot();
        if (snapshot.isEmpty()) return;

        log.info("REGIME WARM-UP START | symbol={} | bars={}",
                symbol, snapshot.size());

        // Percorre o histórico simulando a chegada de candles um por um a partir do índice 60.
        // O MarketRegimeMonitor aplica sua própria decimação, garantindo que apenas os marcos
        // corretos disparem avaliação e que os contadores fiquem prontos para o runtime.
        int startFrom = 60;
        for (int i = startFrom; i <= snapshot.size(); i++) {
            regimeMonitor.onBar(symbol, snapshot.subList(0, i));
        }

        MarketRegime regimeFinal = regimeMonitor.getConfirmedRegime(symbol);

        log.info("REGIME WARM-UP DONE | symbol={} | bars={}",
                symbol, snapshot.size());
    }
}