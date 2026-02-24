package com.github.dayviddouglas.TradingBot.strategy;
import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.model.Signal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BreakoutStrategy implements TradingStrategy {

    private final int lookback;
    private final double bufferPct;

    // (3) strength confirmation params
    private final int bodyLookback = 14;
    private final double bodyMultiplier = 1.0; // currentBody must be > avgBody * multiplier

    public BreakoutStrategy(int lookback, double bufferPct) {
        if (lookback < 2) throw new IllegalArgumentException("lookback must be >= 2");
        if (bufferPct < 0) throw new IllegalArgumentException("bufferPct must be >= 0");
        this.lookback = lookback;
        this.bufferPct = bufferPct;
    }

    @Override
    public String name() {
        return "BreakoutStrategy";
    }

    @Override
    public Signal checkSignal(List<Bar> bars) {
        if (bars == null) return Signal.none(name());

        int minBars = Math.max(lookback + 1, bodyLookback + 1);
        if (bars.size() < minBars) return Signal.none(name());

        Bar last = bars.get(bars.size() - 1);
        double lastClose = last.close();

        double prevHigh = Double.NEGATIVE_INFINITY;
        double prevLow = Double.POSITIVE_INFINITY;

        int start = bars.size() - 1 - lookback;
        int end = bars.size() - 2;

        for (int i = start; i <= end; i++) {
            Bar b = bars.get(i);
            prevHigh = Math.max(prevHigh, b.high());
            prevLow = Math.min(prevLow, b.low());
        }

        double buyTrigger = prevHigh * (1.0 + bufferPct);
        double sellTrigger = prevLow * (1.0 - bufferPct);

        // (3) Candle strength (body)
        double currentBody = Math.abs(last.close() - last.open());
        double avgBody = averageBody(bars, bodyLookback);
        boolean strongCandle = Double.isFinite(avgBody) && currentBody > (avgBody * bodyMultiplier);

        Map<String, Object> meta = new HashMap<>();
        meta.put("lookback", lookback);
        meta.put("bufferPct", bufferPct);
        meta.put("prevHigh", prevHigh);
        meta.put("prevLow", prevLow);
        meta.put("buyTrigger", buyTrigger);
        meta.put("sellTrigger", sellTrigger);
        meta.put("close", lastClose);
        meta.put("currentBody", currentBody);
        meta.put("avgBody", avgBody);
        meta.put("strongCandle", strongCandle);

        if (!strongCandle) return Signal.none(name());

        if (lastClose > buyTrigger) {
            return Signal.buy(name(), last.timestamp(), lastClose, meta);
        }
        if (lastClose < sellTrigger) {
            return Signal.sell(name(), last.timestamp(), lastClose, meta);
        }
        return Signal.none(name());
    }

    private static double averageBody(List<Bar> bars, int lookback) {
        if (bars.size() < lookback) return Double.NaN;

        double sum = 0.0;
        for (int i = bars.size() - lookback; i < bars.size(); i++) {
            Bar b = bars.get(i);
            sum += Math.abs(b.close() - b.open());
        }
        return sum / lookback;
    }
}