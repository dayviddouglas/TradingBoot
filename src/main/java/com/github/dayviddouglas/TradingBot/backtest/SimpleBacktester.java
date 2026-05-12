package com.github.dayviddouglas.TradingBot.backtest;

import com.github.dayviddouglas.TradingBot.config.StrategiesProfile;
import com.github.dayviddouglas.TradingBot.engine.DecisionMode;
import com.github.dayviddouglas.TradingBot.engine.StrategyEngine;
import com.github.dayviddouglas.TradingBot.engine.VolatilityFilter;
import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.model.Signal;
import com.github.dayviddouglas.TradingBot.strategy.TradingStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Simulador de backtest quantitativo sobre histórico real de candles.
 *
 * Responsabilidade única: simular operações sobre histórico e
 * coletar resultados para análise estatística.
 *
 * Após refatoração, delega para:
 * - BacktestMetricsCalculator → calcula métricas dos resultados
 * - BacktestReport            → encapsula o relatório final
 * - TradeResult               → encapsula resultado individual
 *
 * Modos de execução (via DecisionMode):
 * - SINGLE_STRATEGY: testa estratégia isolada sem StrategyEngine
 * - VOTING: usa StrategyEngine com unanimidade conservadora
 * - CONFLUENCE: usa StrategyEngine com ponderação por regime
 *
 * Modelo de simulação:
 * - Entrada no close do candle de sinal
 * - Saída no close de tradeDurationBars candles à frente
 * - WIN: preço se moveu na direção do sinal
 * - P&L: +profitPayout para WIN, -1.0 para LOSS
 *
 * ⚠️ Limitação: usa payout fixo, não reflete payout real da Deriv.
 */
public class SimpleBacktester {

    private final String symbol;
    private final List<TradingStrategy> strategies;
    private final int maxBars;
    private final double profitPayout;
    private final int tradeDurationBars;
    private final DecisionMode decisionMode;
    private final StrategiesProfile profile;

    /**
     * Construtor retrocompatível sem profile.
     * Usa valores padrão para o VolatilityFilter.
     */
    public SimpleBacktester(
            String symbol,
            List<TradingStrategy> strategies,
            int maxBars,
            double profitPayout,
            int tradeDurationBars,
            DecisionMode decisionMode
    ) {
        this(symbol, strategies, maxBars, profitPayout,
                tradeDurationBars, decisionMode, null);
    }

    /**
     * Construtor completo com profile para VolatilityFilter.
     *
     * Quando o profile é fornecido, o engine usa os mesmos parâmetros
     * de volatilidade configurados no strategies.json, garantindo
     * consistência entre runtime e backtest.
     *
     * @param symbol           símbolo do ativo
     * @param strategies       estratégias habilitadas
     * @param maxBars          tamanho da janela de histórico inicial
     * @param profitPayout     multiplicador para vitórias
     * @param tradeDurationBars candles à frente para avaliação
     * @param decisionMode     modo de decisão
     * @param profile          profile opcional para VolatilityFilter
     */
    public SimpleBacktester(
            String symbol,
            List<TradingStrategy> strategies,
            int maxBars,
            double profitPayout,
            int tradeDurationBars,
            DecisionMode decisionMode,
            StrategiesProfile profile
    ) {
        this.symbol = symbol;
        this.strategies = strategies;
        this.maxBars = maxBars;
        this.profitPayout = profitPayout;
        this.tradeDurationBars = tradeDurationBars;
        this.decisionMode = decisionMode;
        this.profile = profile;
    }

    /**
     * Executa o backtest sobre o histórico completo de barras.
     *
     * @param allBars histórico completo em ordem cronológica
     * @return relatório com métricas ou relatório vazio se dados insuficientes
     */
    public BacktestReport run(List<Bar> allBars) {
        if (!hasSufficientBars(allBars)) {
            return BacktestReport.empty(symbol);
        }

        List<TradeResult> results = collectResults(allBars);

        return BacktestMetricsCalculator.calculate(symbol, results);
    }

    // ═══════════════════════════════════════════════════════════════
    // Coleta de resultados
    // ═══════════════════════════════════════════════════════════════

    private List<TradeResult> collectResults(List<Bar> allBars) {
        return switch (decisionMode) {
            case SINGLE_STRATEGY -> runSingleStrategy(allBars);
            case VOTING -> runEngineMode(allBars, DecisionMode.VOTING);
            case CONFLUENCE -> runEngineMode(allBars, DecisionMode.CONFLUENCE);
        };
    }

    private List<TradeResult> runEngineMode(
            List<Bar> allBars,
            DecisionMode engineMode
    ) {
        StrategyEngine engine = buildEngine(engineMode);
        List<TradeResult> results = new ArrayList<>();
        AtomicReference<Signal> latestSignal = new AtomicReference<>();

        engine.onFinalSignal(latestSignal::set);

        seedEngine(engine, allBars);

        for (int i = maxBars; i < allBars.size() - tradeDurationBars; i++) {
            latestSignal.set(null);
            engine.onBar(allBars.get(i));

            Signal signal = latestSignal.get();
            if (isValidSignal(signal)) {
                Bar exitBar = allBars.get(i + tradeDurationBars);
                results.add(evaluate(signal, allBars.get(i), exitBar));
            }
        }

        return results;
    }

    private List<TradeResult> runSingleStrategy(List<Bar> allBars) {
        if (strategies == null || strategies.isEmpty()) return List.of();

        TradingStrategy strategy = strategies.get(0);
        List<TradeResult> results = new ArrayList<>();

        for (int i = maxBars; i < allBars.size() - tradeDurationBars; i++) {
            List<Bar> window = allBars.subList(i - maxBars, i + 1);
            Signal signal = strategy.checkSignal(window);

            if (isValidSignal(signal)) {
                Bar exitBar = allBars.get(i + tradeDurationBars);
                results.add(evaluate(signal, allBars.get(i), exitBar));
            }
        }

        return results;
    }

    // ═══════════════════════════════════════════════════════════════
    // Avaliação de sinal
    // ═══════════════════════════════════════════════════════════════

    private TradeResult evaluate(Signal signal, Bar entryBar, Bar exitBar) {
        double entryPrice = entryBar.close();
        double exitPrice = exitBar.close();

        boolean won = signal.getType() == Signal.Type.BUY
                ? exitPrice > entryPrice
                : exitPrice < entryPrice;

        double pnl = won ? profitPayout : -1.0;

        return new TradeResult(
                entryBar.timestamp().toString(),
                signal.getStrategy(),
                signal.getType().name(),
                entryPrice,
                exitPrice,
                won,
                pnl
        );
    }

    // ═══════════════════════════════════════════════════════════════
    // Engine
    // ═══════════════════════════════════════════════════════════════

    private StrategyEngine buildEngine(DecisionMode engineMode) {
        if (profile != null) {
            VolatilityFilter filter = new VolatilityFilter(
                    profile.getRangeLookback(),
                    profile.getRangeMultiplier()
            );
            return new StrategyEngine(
                    symbol, maxBars, strategies, engineMode, filter);
        }

        return new StrategyEngine(symbol, maxBars, strategies, engineMode);
    }

    private void seedEngine(StrategyEngine engine, List<Bar> allBars) {
        for (int i = 0; i < maxBars; i++) {
            engine.onBar(allBars.get(i));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Utilitários
    // ═══════════════════════════════════════════════════════════════

    private boolean hasSufficientBars(List<Bar> allBars) {
        return allBars != null
                && allBars.size() >= maxBars + tradeDurationBars;
    }

    private boolean isValidSignal(Signal signal) {
        return signal != null && signal.getType() != Signal.Type.NONE;
    }
}