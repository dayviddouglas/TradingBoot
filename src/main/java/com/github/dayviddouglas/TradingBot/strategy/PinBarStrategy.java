package com.github.dayviddouglas.TradingBot.strategy;

import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.model.Signal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PinBarStrategy implements TradingStrategy {

    private final double wickToBodyRatio;        // e.g. 2.5
    private final double maxOppositeWickToBody;  // e.g. 0.7

    public PinBarStrategy(double wickToBodyRatio, double maxOppositeWickToBody) {
        if (wickToBodyRatio <= 0) throw new IllegalArgumentException("wickToBodyRatio must be > 0");
        if (maxOppositeWickToBody < 0) throw new IllegalArgumentException("maxOppositeWickToBody must be >= 0");
        this.wickToBodyRatio = wickToBodyRatio;
        this.maxOppositeWickToBody = maxOppositeWickToBody;
    }

    @Override
    public String name() {
        return "PinBarStrategy";
    }

    @Override
    public Signal checkSignal(List<Bar> bars) {
        if (bars == null || bars.isEmpty()) return Signal.none(name());

        Bar b = bars.get(bars.size() - 1);

        double body = Math.abs(b.close() - b.open());
        if (body == 0.0) return Signal.none(name());

        double upperWick = b.high() - Math.max(b.open(), b.close());
        double lowerWick = Math.min(b.open(), b.close()) - b.low();

        double lowerRatio = lowerWick / body;
        double upperRatio = upperWick / body;

        Map<String, Object> meta = new HashMap<>();
        meta.put("wickToBodyRatio", wickToBodyRatio);
        meta.put("maxOppositeWickToBody", maxOppositeWickToBody);
        meta.put("upperWick", upperWick);
        meta.put("lowerWick", lowerWick);
        meta.put("body", body);
        meta.put("upperRatio", upperRatio);
        meta.put("lowerRatio", lowerRatio);

        if (lowerRatio >= wickToBodyRatio && upperRatio <= maxOppositeWickToBody) {
            return Signal.buy(name(), b.timestamp(), b.close(), meta);
        }
        if (upperRatio >= wickToBodyRatio && lowerRatio <= maxOppositeWickToBody) {
            return Signal.sell(name(), b.timestamp(), b.close(), meta);
        }

        return Signal.none(name());
    }
}
