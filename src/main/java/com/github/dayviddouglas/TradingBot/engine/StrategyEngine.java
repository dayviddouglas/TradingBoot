package com.github.dayviddouglas.TradingBot.engine;
import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.model.Signal;
import com.github.dayviddouglas.TradingBot.strategy.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Maintains an in-memory bar history and evaluates strategies on new bars.
 *
 * Improvements included:
 * 1) Confluence voting: emit final BUY/SELL only if >=2 strategies agree and none oppose.
 * 2) Volatility filter: only operate if current range >= avgRange * multiplier.
 * 6) Anti-repetition: do not emit the same FINAL signal type twice in a row.
 */
public class StrategyEngine {
    private static final Logger log = LoggerFactory.getLogger(StrategyEngine.class);

    private final int maxBars;
    private final List<Bar> bars = new ArrayList<>();
    private final List<TradingStrategy> strategies;

    private Instant lastProcessedBarTime;

    // Volatility filter params (simple "range average" filter)
    private final int rangeLookback = 14;
    private final double rangeMultiplier = 1.10;

    // Anti-repetition for FINAL signal
    private Signal.Type lastFinalEmitted = Signal.Type.NONE;

    public StrategyEngine(int maxBars, List<TradingStrategy> strategies) {
        this.maxBars = Math.max(50, maxBars);
        this.strategies = List.copyOf(strategies);
    }

    public synchronized void seedHistory(List<Bar> history) {
        bars.clear();
        if (history != null) bars.addAll(history);
        trim();

        if (!bars.isEmpty()) {
            lastProcessedBarTime = bars.get(bars.size() - 1).timestamp();
        }
        log.info("Seeded history. bars={}", bars.size());
    }

    public synchronized void onBar(Bar bar) {
        if (bar == null) return;

        if (bars.isEmpty()) {
            bars.add(bar);
        } else {
            Bar last = bars.get(bars.size() - 1);

            if (bar.timestamp().equals(last.timestamp())) {
                bars.set(bars.size() - 1, bar);
            } else if (bar.timestamp().isAfter(last.timestamp())) {
                bars.add(bar);
            } else {
                return;
            }
        }

        trim();

        // evaluate only on new candle timestamp
        if (lastProcessedBarTime != null && bar.timestamp().equals(lastProcessedBarTime)) {
            return;
        }
        lastProcessedBarTime = bar.timestamp();

        evaluate();
    }

    private void evaluate() {
        List<Bar> snapshot = List.copyOf(bars);
        Bar last = snapshot.get(snapshot.size() - 1);

        // --- (2) Volatility filter (range-based) ---
        double currentRange = last.high() - last.low();
        double avgRange = averageRange(snapshot, rangeLookback);

        boolean volatilityOk = Double.isFinite(avgRange) && currentRange >= avgRange * rangeMultiplier;

        if (!volatilityOk) {
            log.debug("Volatility filter blocked @ {} range={} avgRange={} mult={}",
                    last.timestamp(), currentRange, avgRange, rangeMultiplier);
            // reset anti-repetition when market is filtered (optional; keeps behavior clean)
            lastFinalEmitted = Signal.Type.NONE;
            return;
        }

        // --- collect votes from strategies ---
        int buyCount = 0;
        int sellCount = 0;
        int noneCount = 0;
        List<Signal> votes = new ArrayList<>();

        for (TradingStrategy s : strategies) {
            Signal signal = s.checkSignal(snapshot);

            switch (signal.getType()) {
                case BUY -> {
                    buyCount++;
                    votes.add(signal);
                }
                case SELL -> {
                    sellCount++;
                    votes.add(signal);
                }
                case NONE -> noneCount++;
            }
        }

        // --- (1) Confluence voting ---
        Signal finalSignal = null;

        if (buyCount >= 2 && sellCount == 0) {
            finalSignal = Signal.buy(
                    "Confluence(>=2)",
                    last.timestamp(),
                    last.close(),
                    Map.of(
                            "buyVotes", buyCount,
                            "sellVotes", sellCount,
                            "noneVotes", noneCount,
                            "range", currentRange,
                            "avgRange", avgRange,
                            "rangeMultiplier", rangeMultiplier
                    )
            );
        } else if (sellCount >= 2 && buyCount == 0) {
            finalSignal = Signal.sell(
                    "Confluence(>=2)",
                    last.timestamp(),
                    last.close(),
                    Map.of(
                            "buyVotes", buyCount,
                            "sellVotes", sellCount,
                            "noneVotes", noneCount,
                            "range", currentRange,
                            "avgRange", avgRange,
                            "rangeMultiplier", rangeMultiplier
                    )
            );
        }

        // --- (6) Anti-repetition (final signal) ---
        if (finalSignal != null) {
            if (finalSignal.getType() == lastFinalEmitted) {
                log.debug("Final signal repeated (suppressed): {} @ {}", finalSignal.getType(), last.timestamp());
                return;
            }
            lastFinalEmitted = finalSignal.getType();

            log.info("FINAL SIGNAL {} @ {} close={} votes(BUY={}, SELL={}, NONE={}) details={}",
                    finalSignal.getType(),
                    last.timestamp(),
                    last.close(),
                    buyCount, sellCount, noneCount,
                    votes);
        } else {
            // no confluence => reset so next valid signal is emitted
            lastFinalEmitted = Signal.Type.NONE;
            log.debug("No confluence @ {} close={} votes(BUY={}, SELL={}, NONE={})",
                    last.timestamp(), last.close(), buyCount, sellCount, noneCount);
        }
    }

    private static double averageRange(List<Bar> bars, int lookback) {
        if (bars == null || bars.size() < lookback) return Double.NaN;

        double sum = 0.0;
        for (int i = bars.size() - lookback; i < bars.size(); i++) {
            Bar b = bars.get(i);
            sum += (b.high() - b.low());
        }
        return sum / lookback;
    }

    private void trim() {
        while (bars.size() > maxBars) bars.remove(0);
    }
}