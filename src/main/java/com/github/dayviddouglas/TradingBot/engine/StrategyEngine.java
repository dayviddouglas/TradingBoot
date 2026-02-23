package com.github.dayviddouglas.TradingBot.engine;
import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.model.Signal;
import com.github.dayviddouglas.TradingBot.strategy.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Maintains an in-memory bar history and evaluates strategies on new bars.
 *
 * Behavior:
 * - Keeps bars in ascending chronological order.
 * - Deriv can send updates for the current candle (same timestamp). We "upsert" the last bar.
 * - Evaluates strategies only when a NEW bar timestamp arrives (to reduce intrabar spam).
 */
public class StrategyEngine {
    private static final Logger log = LoggerFactory.getLogger(StrategyEngine.class);

    private final int maxBars;
    private final List<Bar> bars = new ArrayList<>();
    private final List<TradingStrategy> strategies;

    private Instant lastProcessedBarTime;

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

    /**
     * Upsert bar by timestamp:
     * - if empty -> add
     * - if same timestamp as last -> replace last (candle update)
     * - if newer timestamp -> append (new candle)
     * - if older -> ignore (MVP)
     */
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

        for (TradingStrategy s : strategies) {
            Signal signal = s.checkSignal(snapshot);
            if (signal.getType() != Signal.Type.NONE) {
                log.info("SIGNAL {} @ {} close={}: {}",
                        signal.getType(), last.timestamp(), last.close(), signal);
            }
        }
    }

    private void trim() {
        while (bars.size() > maxBars) bars.remove(0);
    }
}