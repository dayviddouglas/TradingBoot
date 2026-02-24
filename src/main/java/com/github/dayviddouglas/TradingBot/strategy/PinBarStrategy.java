package com.github.dayviddouglas.TradingBot.strategy;

import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.model.Signal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PinBarStrategy implements TradingStrategy {

    private final double wickToBodyRatio;
    private final double maxOppositeWickToBody;

    // (4) context: require proximity to simple S/R (prevHigh/prevLow)
    private final int srLookback;
    private final double tolerancePct;

    public PinBarStrategy(double wickToBodyRatio, double maxOppositeWickToBody) {
        this(wickToBodyRatio, maxOppositeWickToBody, 50, 0.001);
    }

    public PinBarStrategy(double wickToBodyRatio,
                          double maxOppositeWickToBody,
                          int srLookback,
                          double tolerancePct) {
        if (wickToBodyRatio <= 0) throw new IllegalArgumentException("wickToBodyRatio must be > 0");
        if (maxOppositeWickToBody < 0) throw new IllegalArgumentException("maxOppositeWickToBody must be >= 0");
        if (srLookback < 5) throw new IllegalArgumentException("srLookback must be >= 5");
        if (tolerancePct < 0) throw new IllegalArgumentException("tolerancePct must be >= 0");

        this.wickToBodyRatio = wickToBodyRatio;
        this.maxOppositeWickToBody = maxOppositeWickToBody;
        this.srLookback = srLookback;
        this.tolerancePct = tolerancePct;
    }

    @Override
    public String name() {
        return "PinBarStrategy";
    }

    @Override
    public Signal checkSignal(List<Bar> bars) {
        if (bars == null) return Signal.none(name());
        if (bars.size() < srLookback + 1) return Signal.none(name());

        Bar b = bars.get(bars.size() - 1);

        double body = Math.abs(b.close() - b.open());
        if (body == 0.0) return Signal.none(name());

        double upperWick = b.high() - Math.max(b.open(), b.close());
        double lowerWick = Math.min(b.open(), b.close()) - b.low();

        double lowerRatio = lowerWick / body;
        double upperRatio = upperWick / body;

        // (4) compute simple S/R levels from lookback excluding current bar
        double prevHigh = Double.NEGATIVE_INFINITY;
        double prevLow = Double.POSITIVE_INFINITY;

        int start = bars.size() - 1 - srLookback;
        int end = bars.size() - 2;

        for (int i = start; i <= end; i++) {
            Bar x = bars.get(i);
            prevHigh = Math.max(prevHigh, x.high());
            prevLow = Math.min(prevLow, x.low());
        }

        double tolHigh = prevHigh * tolerancePct;
        double tolLow = prevLow * tolerancePct;

        boolean nearResistance = Math.abs(b.high() - prevHigh) <= tolHigh;
        boolean nearSupport = Math.abs(b.low() - prevLow) <= tolLow;

        Map<String, Object> meta = new HashMap<>();
        meta.put("wickToBodyRatio", wickToBodyRatio);
        meta.put("maxOppositeWickToBody", maxOppositeWickToBody);
        meta.put("upperWick", upperWick);
        meta.put("lowerWick", lowerWick);
        meta.put("body", body);
        meta.put("upperRatio", upperRatio);
        meta.put("lowerRatio", lowerRatio);
        meta.put("srLookback", srLookback);
        meta.put("tolerancePct", tolerancePct);
        meta.put("prevHigh", prevHigh);
        meta.put("prevLow", prevLow);
        meta.put("nearResistance", nearResistance);
        meta.put("nearSupport", nearSupport);

        // Bullish pin bar only if near support
        if (nearSupport && lowerRatio >= wickToBodyRatio && upperRatio <= maxOppositeWickToBody) {
            return Signal.buy(name(), b.timestamp(), b.close(), meta);
        }

        // Bearish pin bar only if near resistance
        if (nearResistance && upperRatio >= wickToBodyRatio && lowerRatio <= maxOppositeWickToBody) {
            return Signal.sell(name(), b.timestamp(), b.close(), meta);
        }

        return Signal.none(name());
    }
}