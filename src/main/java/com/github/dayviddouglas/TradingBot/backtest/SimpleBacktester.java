package com.github.dayviddouglas.TradingBot.backtest;
import com.github.dayviddouglas.TradingBot.backtest.BacktestMode;
import com.github.dayviddouglas.TradingBot.engine.DecisionMode;
import com.github.dayviddouglas.TradingBot.engine.StrategyEngine;
import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.model.Signal;
import com.github.dayviddouglas.TradingBot.strategy.TradingStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Backtester simples com suporte a:
 * - CONFLUENCE
 * - SINGLE_STRATEGY
 */
public class SimpleBacktester {

    private final String symbol;
    private final List<TradingStrategy> strategies;
    private final int maxBars;
    private final double profitPayout;
    private final int tradeDurationBars;
    private final BacktestMode mode;

    public SimpleBacktester(String symbol,
                            List<TradingStrategy> strategies,
                            int maxBars,
                            double profitPayout,
                            int tradeDurationBars,
                            BacktestMode mode) {
        this.symbol = symbol;
        this.strategies = strategies;
        this.maxBars = maxBars;
        this.profitPayout = profitPayout;
        this.tradeDurationBars = tradeDurationBars;
        this.mode = mode;
    }

    public BacktestReport run(List<Bar> allBars) {
        if (allBars == null || allBars.size() < maxBars + tradeDurationBars) {
            return BacktestReport.empty(symbol);
        }

        return switch (mode) {
            case CONFLUENCE -> runConfluence(allBars);
            case SINGLE_STRATEGY -> runSingleStrategy(allBars);
        };
    }

    private BacktestReport runConfluence(List<Bar> allBars) {
        StrategyEngine engine = new StrategyEngine(symbol, maxBars, strategies, DecisionMode.CONFLUENCE);
        List<TradeResult> results = new ArrayList<>();
        AtomicReference<Signal> latestSignal = new AtomicReference<>();

        engine.onFinalSignal(latestSignal::set);

        for (int i = 0; i < maxBars; i++) {
            engine.onBar(allBars.get(i));
        }

        for (int i = maxBars; i < allBars.size() - tradeDurationBars; i++) {
            latestSignal.set(null);

            Bar currentBar = allBars.get(i);
            engine.onBar(currentBar);

            Signal signal = latestSignal.get();
            if (signal == null || signal.getType() == Signal.Type.NONE) {
                continue;
            }

            Bar exitBar = allBars.get(i + tradeDurationBars);

            double entryPrice = currentBar.close();
            double exitPrice = exitBar.close();

            boolean won = signal.getType() == Signal.Type.BUY
                    ? exitPrice > entryPrice
                    : exitPrice < entryPrice;

            double pnl = won ? profitPayout : -1.0;

            results.add(new TradeResult(
                    currentBar.timestamp().toString(),
                    signal.getStrategy(),
                    signal.getType().name(),
                    entryPrice,
                    exitPrice,
                    won,
                    pnl
            ));
        }

        return buildReport(symbol, results);
    }

    private BacktestReport runSingleStrategy(List<Bar> allBars) {
        List<TradeResult> results = new ArrayList<>();

        // nesse modo faz sentido usar só uma estratégia por vez
        if (strategies == null || strategies.isEmpty()) {
            return BacktestReport.empty(symbol);
        }

        TradingStrategy strategy = strategies.get(0);

        for (int i = maxBars; i < allBars.size() - tradeDurationBars; i++) {
            List<Bar> window = allBars.subList(i - maxBars, i + 1);

            Signal signal = strategy.checkSignal(window);
            if (signal == null || signal.getType() == Signal.Type.NONE) {
                continue;
            }

            Bar currentBar = allBars.get(i);
            Bar exitBar = allBars.get(i + tradeDurationBars);

            double entryPrice = currentBar.close();
            double exitPrice = exitBar.close();

            boolean won = signal.getType() == Signal.Type.BUY
                    ? exitPrice > entryPrice
                    : exitPrice < entryPrice;

            double pnl = won ? profitPayout : -1.0;

            results.add(new TradeResult(
                    currentBar.timestamp().toString(),
                    strategy.name(),
                    signal.getType().name(),
                    entryPrice,
                    exitPrice,
                    won,
                    pnl
            ));
        }

        return buildReport(symbol, results);
    }

    private BacktestReport buildReport(String symbol, List<TradeResult> results) {
        if (results.isEmpty()) return BacktestReport.empty(symbol);

        int totalTrades = results.size();
        int wins = (int) results.stream().filter(TradeResult::won).count();
        int losses = totalTrades - wins;

        double winRate = totalTrades == 0 ? 0.0 : (wins * 100.0 / totalTrades);
        double totalPnl = results.stream().mapToDouble(TradeResult::pnl).sum();

        double avgWin = results.stream()
                .filter(TradeResult::won)
                .mapToDouble(TradeResult::pnl)
                .average().orElse(0.0);

        double avgLoss = results.stream()
                .filter(r -> !r.won)
                .mapToDouble(r -> Math.abs(r.pnl))
                .average().orElse(0.0);

        double payoffRatio = avgLoss == 0 ? 0.0 : avgWin / avgLoss;
        double expectancy = ((wins / (double) totalTrades) * avgWin) - ((losses / (double) totalTrades) * avgLoss);

        double grossProfit = results.stream().filter(TradeResult::won).mapToDouble(TradeResult::pnl).sum();
        double grossLoss = results.stream().filter(r -> !r.won).mapToDouble(r -> Math.abs(r.pnl)).sum();
        double profitFactor = grossLoss == 0 ? 0.0 : grossProfit / grossLoss;

        double maxDrawdown = calculateMaxDrawdown(results);
        int maxConsecutiveLosses = calculateMaxConsecutiveLosses(results);

        return new BacktestReport(
                symbol,
                totalTrades,
                wins,
                losses,
                winRate,
                totalPnl,
                avgWin,
                avgLoss,
                payoffRatio,
                expectancy,
                profitFactor,
                maxDrawdown,
                maxConsecutiveLosses,
                results
        );
    }

    private double calculateMaxDrawdown(List<TradeResult> results) {
        double peak = 0.0;
        double equity = 0.0;
        double maxDrawdown = 0.0;

        for (TradeResult r : results) {
            equity += r.pnl;
            peak = Math.max(peak, equity);
            maxDrawdown = Math.max(maxDrawdown, peak - equity);
        }

        return maxDrawdown;
    }

    private int calculateMaxConsecutiveLosses(List<TradeResult> results) {
        int max = 0;
        int current = 0;

        for (TradeResult r : results) {
            if (!r.won) {
                current++;
                max = Math.max(max, current);
            } else {
                current = 0;
            }
        }

        return max;
    }

    public record TradeResult(
            String timestamp,
            String strategyName,
            String signalType,
            double entryPrice,
            double exitPrice,
            boolean won,
            double pnl
    ) {
    }

    public record BacktestReport(
            String symbol,
            int totalTrades,
            int wins,
            int losses,
            double winRate,
            double totalPnl,
            double avgWin,
            double avgLoss,
            double payoffRatio,
            double expectancy,
            double profitFactor,
            double maxDrawdown,
            int maxConsecutiveLosses,
            List<TradeResult> results
    ) {
        public static BacktestReport empty(String symbol) {
            return new BacktestReport(
                    symbol, 0, 0, 0, 0.0, 0.0, 0.0, 0.0,
                    0.0, 0.0, 0.0, 0.0, 0, List.of()
            );
        }

        public boolean hasEdge() {
            return totalTrades >= 30
                    && winRate > 52.0
                    && expectancy > 0.05
                    && profitFactor > 1.1;
        }

        public String classification() {
            if (totalTrades < 30) return "NO_DATA";
            if (hasEdge()) return "APPROVED";
            if (expectancy > 0.0) return "WEAK";
            return "REJECTED";
        }

        public String toPrettyReport() {
            return """
                    ╔══════════════════════════════════════════════════╗
                    ║         BACKTEST REPORT - %s             ║
                    ╠══════════════════════════════════════════════════╣
                    ║  Total trades:         %d                     ║
                    ║  Wins:                 %d                     ║
                    ║  Losses:               %d                     ║
                    ║  Win Rate:             %.1f                   %%║
                    ║                                                  ║
                    ║  Avg Win:              %.2f                   R║
                    ║  Avg Loss:             %.2f                   R║
                    ║  Payoff Ratio:         %.2f                    ║
                    ║                                                  ║
                    ║  Expectancy:           %.4f                R║
                    ║  Profit Factor:        %.2f                    ║
                    ║  Total P&L:            %.2f                 R║
                    ║                                                  ║
                    ║  Max Drawdown:         %.2f                  R║
                    ║  Max Consec. Losses:   %d                      ║
                    ║                                                  ║
                    ║  RESULT:               %s                ║
                    ║  HAS EDGE:             %s                    ║
                    ╚══════════════════════════════════════════════════╝
                    """.formatted(
                    symbol,
                    totalTrades,
                    wins,
                    losses,
                    winRate,
                    avgWin,
                    avgLoss,
                    payoffRatio,
                    expectancy,
                    profitFactor,
                    totalPnl,
                    maxDrawdown,
                    maxConsecutiveLosses,
                    classification(),
                    hasEdge() ? "YES ✅" : "NO ❌"
            );
        }
    }
}