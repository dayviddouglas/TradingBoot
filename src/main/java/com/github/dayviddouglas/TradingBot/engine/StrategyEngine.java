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
import java.util.function.Consumer;

public class StrategyEngine {
    private static final Logger log = LoggerFactory.getLogger(StrategyEngine.class);

    private final String symbol;
    private final int maxBars;
    private final List<Bar> bars = new ArrayList<>();
    private final List<TradingStrategy> strategies;

    private Instant lastProcessedBarTime;

    private final int rangeLookback = 14;
    private final double rangeMultiplier = 1.10;

    private Signal.Type lastFinalEmitted = Signal.Type.NONE;

    // NEW: callback for final signals
    private volatile Consumer<Signal> onFinalSignal;

    public StrategyEngine(String symbol, int maxBars, List<TradingStrategy> strategies) {
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("symbol não pode ser vazio");
        this.symbol = symbol;
        this.maxBars = Math.max(50, maxBars);
        this.strategies = List.copyOf(strategies);
    }

    public String getSymbol() {
        return symbol;
    }

    public void onFinalSignal(Consumer<Signal> handler) {
        this.onFinalSignal = handler;
    }

    public synchronized void seedHistory(List<Bar> history) {
        bars.clear();
        if (history != null) bars.addAll(history);
        trim();

        if (!bars.isEmpty()) {
            lastProcessedBarTime = bars.get(bars.size() - 1).timestamp();
        }

        log.info("Seeded history. symbol={} bars={}", symbol, bars.size());
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

        if (lastProcessedBarTime != null && bar.timestamp().equals(lastProcessedBarTime)) {
            return;
        }
        lastProcessedBarTime = bar.timestamp();

        evaluate();
    }

    private void evaluate() {
        List<Bar> snapshot = List.copyOf(bars);
        Bar last = snapshot.get(snapshot.size() - 1);

        double currentRange = last.high() - last.low();
        double avgRange = averageRange(snapshot, rangeLookback);

        boolean volatilityOk = Double.isFinite(avgRange) && currentRange >= avgRange * rangeMultiplier;

        if (!volatilityOk) {
            log.debug("Volatility filter blocked | symbol={} | time={} | range={} avgRange={} mult={}",
                    symbol, last.timestamp(), currentRange, avgRange, rangeMultiplier);
            lastFinalEmitted = Signal.Type.NONE;
            return;
        }

        int buyCount = 0;
        int sellCount = 0;
        int noneCount = 0;

        List<Signal> votes = new ArrayList<>();

        for (TradingStrategy s : strategies) {
            Signal signal = s.checkSignal(snapshot);

            switch (signal.getType()) {
                case BUY -> { buyCount++; votes.add(signal); }
                case SELL -> { sellCount++; votes.add(signal); }
                case NONE -> noneCount++;
            }
        }

        Signal finalSignal = null;

        if (buyCount >= 2 && sellCount == 0) {
            finalSignal = Signal.buy(
                    "Confluence(>=2)",
                    last.timestamp(),
                    last.close(),
                    Map.of(
                            "symbol", symbol,
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
                            "symbol", symbol,
                            "buyVotes", buyCount,
                            "sellVotes", sellCount,
                            "noneVotes", noneCount,
                            "range", currentRange,
                            "avgRange", avgRange,
                            "rangeMultiplier", rangeMultiplier
                    )
            );
        }

        if (finalSignal != null) {
            if (finalSignal.getType() == lastFinalEmitted) {
                log.debug("Final signal repeated (suppressed) | symbol={} | type={} | time={}",
                        symbol, finalSignal.getType(), last.timestamp());
                return;
            }
            lastFinalEmitted = finalSignal.getType();

            log.info("FINAL SIGNAL {} | symbol={} | time={} | close={} | votes(BUY={}, SELL={}, NONE={}) | details={}",
                    finalSignal.getType(),
                    symbol,
                    last.timestamp(),
                    last.close(),
                    buyCount, sellCount, noneCount,
                    votes
            );

            // NEW: notify
            Consumer<Signal> cb = onFinalSignal;
            if (cb != null) {
                try {
                    cb.accept(finalSignal);
                } catch (Exception e) {
                    log.warn("onFinalSignal callback error | symbol={} | time={}", symbol, last.timestamp(), e);
                }
            }
        } else {
            lastFinalEmitted = Signal.Type.NONE;
            log.debug("No confluence | symbol={} | time={} | close={} | votes(BUY={}, SELL={}, NONE={})",
                    symbol, last.timestamp(), last.close(), buyCount, sellCount, noneCount);
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