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
 * Responsabilidade única: orquestrar o pipeline de avaliação
 * para cada candle recebido do TickCandleAggregator.
 *
 * Após refatoração, delega para componentes especializados:
 * - BarHistory           → gerencia o histórico local de candles
 * - VolatilityFilter     → filtra candles sem movimento suficiente
 * - DecisionEvaluator    → avalia estratégias e produz EvaluationResult
 * - SignalEmitter         → constrói e emite o sinal final
 * - MarketRegimeMonitor  → monitora regime em background (novo v5)
 *
 * Pipeline por candle:
 * 1. BarHistory.accept()              → aceita ou rejeita o candle
 * 2. BarHistory.alreadyProcessed()    → evita reprocessamento
 * 3. MarketRegimeMonitor.onBar()      → notifica monitor de regime (novo v5)
 * 4. VolatilityFilter.isAcceptable()  → filtra mercado parado
 * 5. DecisionEvaluator.evaluate()     → avalia estratégias
 * 6. SignalEmitter.tryEmit()          → emite se sinal válido
 *
 * Integração com MarketRegimeMonitor (v5):
 * O monitor é opcional (pode ser null) para manter compatibilidade com
 * o SimpleBacktester, que constrói engines sem acesso ao Spring context
 * e portanto sem os beans de monitoramento de regime.
 * No runtime, o monitor é sempre injetado via PipelineRegistry.
 *
 * Thread-safety:
 * - BarHistory é synchronized internamente
 * - onBar() e seedHistory() são synchronized nesta classe
 * - SignalEmitter usa volatile para o callback
 * - MarketRegimeMonitor é thread-safe (ConcurrentHashMap interno)
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
     * Monitor de regime injetado pelo PipelineRegistry no runtime.
     *
     * É null no backtest (SimpleBacktester não usa Spring context).
     * A verificação if (regimeMonitor != null) garante compatibilidade
     * retroativa sem alterar o comportamento do backtest.
     */
    private final MarketRegimeMonitor regimeMonitor;

    // ═══════════════════════════════════════════════════════════════
    // Construtores
    // ═══════════════════════════════════════════════════════════════

    /**
     * Construtor com VolatilityFilter padrão e sem monitor de regime.
     * Mantém compatibilidade retroativa com SimpleBacktester.
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
     * Construtor com VolatilityFilter configurável e sem monitor de regime.
     * Mantém compatibilidade retroativa com SimpleBacktester.
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
     * Construtor completo com MarketRegimeMonitor.
     *
     * Usado pelo PipelineRegistry no runtime para injetar o monitor de regime.
     * O monitor é responsável por notificar o RegimeRegistry e o
     * RegimeHistoryService sobre mudanças confirmadas de regime.
     *
     * @param symbol          símbolo do ativo
     * @param maxBars         tamanho máximo do histórico local
     * @param strategies      estratégias habilitadas para este ativo
     * @param decisionMode    modo de decisão (SINGLE, VOTING, CONFLUENCE)
     * @param volatilityFilter filtro de volatilidade configurado
     * @param regimeMonitor   monitor de regime (null = desabilitado no backtest)
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
     * Factory method sem monitor de regime.
     * Mantém compatibilidade retroativa com BacktestRunner.
     *
     * @param profile    perfil completo do ativo
     * @param strategies estratégias habilitadas
     * @return StrategyEngine configurado sem monitoramento de regime
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
     * Factory method com monitor de regime.
     * Usado pelo PipelineRegistry no runtime.
     *
     * @param profile       perfil completo do ativo
     * @param strategies    estratégias habilitadas
     * @param regimeMonitor monitor de regime injetado pelo Spring
     * @return StrategyEngine configurado com monitoramento de regime
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
     * Registra o callback que receberá os sinais finais.
     *
     * @param handler consumidor do sinal final
     */
    public void onFinalSignal(Consumer<Signal> handler) {
        signalEmitter.onFinalSignal(handler);
    }

    /**
     * Inicializa o histórico com candles carregados da API.
     *
     * @param history lista de candles históricos
     */
    public synchronized void seedHistory(List<Bar> history) {
        barHistory.seed(history);
        log.info("HISTORY SEEDED | symbol={} | bars={}", symbol, barHistory.size());
    }

    /**
     * Retorna snapshot imutável do histórico atual.
     * Usado pelo DerivTradeService para avaliação de risco ATR.
     *
     * @return lista imutável de barras
     */
    public List<Bar> getBarsSnapshot() {
        return barHistory.snapshot();
    }

    /**
     * Retorna a quantidade atual de candles no histórico.
     *
     * @return tamanho do histórico
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
     * Processa um novo candle recebido do TickCandleAggregator.
     *
     * Ponto de entrada principal para dados em tempo real.
     *
     * Após aceitar o candle no histórico, notifica o MarketRegimeMonitor
     * antes de avaliar estratégias. Isso garante que o regime seja
     * atualizado com o snapshot mais recente antes de qualquer decisão.
     *
     * O monitor recebe o snapshot APÓS a aceitação do novo candle,
     * garantindo que a janela de 200 candles inclua o candle mais recente.
     *
     * @param bar candle OHLC a ser processado
     */
    public synchronized void onBar(Bar bar) {
        if (bar == null) return;

        boolean isNewCandle = barHistory.accept(bar);

        if (!isNewCandle) return;
        if (barHistory.alreadyProcessed(bar)) return;

        barHistory.markProcessed(bar);

        // Notifica o monitor de regime com o snapshot atual (novo v5)
        // Executado antes da avaliação de estratégias para que o regime
        // esteja atualizado quando o DerivTradeService consultar o registry
        notifyRegimeMonitor();

        evaluate();
    }

    // ═══════════════════════════════════════════════════════════════
    // Integração com MarketRegimeMonitor
    // ═══════════════════════════════════════════════════════════════

    /**
     * Notifica o MarketRegimeMonitor sobre o novo candle aceito.
     *
     * A verificação null garante compatibilidade retroativa com o backtest,
     * onde o engine é construído sem monitor de regime.
     *
     * O snapshot é capturado após a aceitação do candle, garantindo
     * que o monitor veja o estado mais atualizado do histórico.
     */
    private void notifyRegimeMonitor() {
        if (regimeMonitor == null) return;

        List<Bar> snapshot = barHistory.snapshot();
        regimeMonitor.onBar(symbol, snapshot);
    }

    // ═══════════════════════════════════════════════════════════════
    // Avaliação
    // ═══════════════════════════════════════════════════════════════

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
     * Avalia o regime de mercado inicial a partir do histórico já carregado.
     *
     * Chamado pelo MultiSymbolDerivBotRunner após seedHistory() para garantir
     * que o RegimeRegistry tenha um regime confirmado antes do bot começar
     * a operar em tempo real, eliminando o período de warm-up onde o regime
     * seria CHOPPY por padrão.
     *
     * Simula o pipeline completo do MarketRegimeMonitor percorrendo os bars
     * históricos em ordem cronológica com a mesma lógica de decimação e
     * confirmação usada em tempo real. O regime resultante reflete o estado
     * do mercado ao final do histórico — o contexto mais relevante para o
     * início das operações.
     *
     * Não emite sinais operacionais — apenas aciona o pipeline de regime.
     * Os contadores de decimação do MarketRegimeMonitor são mantidos
     * corretamente para continuidade no runtime.
     *
     * @since v5.4.2
     */
    public synchronized void evaluateRegimeFromHistory() {
        if (regimeMonitor == null) return;

        List<Bar> snapshot = barHistory.snapshot();
        if (snapshot.isEmpty()) return;

        log.info("REGIME WARM-UP START | symbol={} | bars={}",
                symbol, snapshot.size());

        // Percorre o histórico simulando a chegada de candles um por um.
        // O MarketRegimeMonitor aplica sua própria decimação internamente,
        // garantindo que apenas os marcos corretos disparem avaliação.
        // Os contadores ficam no estado correto para continuidade no runtime.

        int startFrom = 60;
        for (int i = startFrom; i <= snapshot.size(); i++) {
            regimeMonitor.onBar(symbol, snapshot.subList(0, i));
        }

        MarketRegime regimeFinal = regimeMonitor.getConfirmedRegime(symbol);

        log.info("REGIME WARM-UP DONE | symbol={} | bars={}",
                symbol, snapshot.size());
    }
}