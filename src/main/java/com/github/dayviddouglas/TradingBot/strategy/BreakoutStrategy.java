package com.github.dayviddouglas.TradingBot.strategy;
import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.model.Signal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BreakoutStrategy implements TradingStrategy {

    private final int lookback;
    private final double bufferPct; // e.g. 0.0005 = 0.05%

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
        if (bars == null || bars.size() < lookback + 1) return Signal.none(name());

        Bar last = bars.get(bars.size() - 1);
        double lastClose = last.close();

        double prevHigh = Double.NEGATIVE_INFINITY;
        double prevLow = Double.POSITIVE_INFINITY;

        // compute over previous lookback bars excluding the last bar
        int start = bars.size() - 1 - lookback;
        int end = bars.size() - 2;

        for (int i = start; i <= end; i++) {
            Bar b = bars.get(i);
            prevHigh = Math.max(prevHigh, b.high());
            prevLow = Math.min(prevLow, b.low());
        }

        double buyTrigger = prevHigh * (1.0 + bufferPct);
        double sellTrigger = prevLow * (1.0 - bufferPct);

        Map<String, Object> meta = new HashMap<>();
        meta.put("lookback", lookback);
        meta.put("bufferPct", bufferPct);
        meta.put("prevHigh", prevHigh);
        meta.put("prevLow", prevLow);
        meta.put("buyTrigger", buyTrigger);
        meta.put("sellTrigger", sellTrigger);
        meta.put("close", lastClose);

        if (lastClose > buyTrigger) {
            return Signal.buy(name(), last.timestamp(), lastClose, meta);
        }
        if (lastClose < sellTrigger) {
            return Signal.sell(name(), last.timestamp(), lastClose, meta);
        }
        return Signal.none(name());
    }
}
