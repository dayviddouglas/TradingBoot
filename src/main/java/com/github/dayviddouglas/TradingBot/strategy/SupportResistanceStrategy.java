package com.github.dayviddouglas.TradingBot.strategy;
import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.model.Signal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Simple Support/Resistance strategy (MVP):
 * - Computes previous lookback high/low and emits signals on rejection near these levels.
 */
public class SupportResistanceStrategy implements TradingStrategy {

    private final int lookback;
    private final double tolerancePct; // e.g. 0.001 = 0.1%

    public SupportResistanceStrategy(int lookback, double tolerancePct) {
        if (lookback < 5) throw new IllegalArgumentException("lookback must be >= 5");
        if (tolerancePct < 0) throw new IllegalArgumentException("tolerancePct must be >= 0");
        this.lookback = lookback;
        this.tolerancePct = tolerancePct;
    }

    @Override
    public String name() {
        return "SupportResistanceStrategy";
    }

    @Override
    public Signal checkSignal(List<Bar> bars) {
        if (bars == null || bars.size() < lookback + 1) return Signal.none(name());

        Bar last = bars.get(bars.size() - 1);

        double prevHigh = Double.NEGATIVE_INFINITY;
        double prevLow = Double.POSITIVE_INFINITY;

        int start = bars.size() - 1 - lookback;
        int end = bars.size() - 2;

        for (int i = start; i <= end; i++) {
            Bar b = bars.get(i);
            prevHigh = Math.max(prevHigh, b.high());
            prevLow = Math.min(prevLow, b.low());
        }

        double tolHigh = prevHigh * tolerancePct;
        double tolLow = prevLow * tolerancePct;

        Map<String, Object> meta = new HashMap<>();
        meta.put("lookback", lookback);
        meta.put("tolerancePct", tolerancePct);
        meta.put("prevHigh", prevHigh);
        meta.put("prevLow", prevLow);

        boolean nearResistance = Math.abs(last.high() - prevHigh) <= tolHigh;
        if (nearResistance && last.close() < last.open()) {
            meta.put("pattern", "rejection_resistance");
            return Signal.sell(name(), last.timestamp(), last.close(), meta);
        }

        boolean nearSupport = Math.abs(last.low() - prevLow) <= tolLow;
        if (nearSupport && last.close() > last.open()) {
            meta.put("pattern", "rejection_support");
            return Signal.buy(name(), last.timestamp(), last.close(), meta);
        }

        return Signal.none(name());
    }
}