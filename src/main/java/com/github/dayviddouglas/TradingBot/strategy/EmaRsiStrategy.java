package com.github.dayviddouglas.TradingBot.strategy;


import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.model.Signal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * EMA + RSI trend confirmation (MVP).
 * EMA and RSI are computed inline (no external libs).
 */
public class EmaRsiStrategy implements TradingStrategy {

    private final int emaFast;
    private final int emaSlow;
    private final int rsiPeriod;
    private final double rsiBuyThreshold;
    private final double rsiSellThreshold;

    // (5) anti-chop filter params
    private final int rangeLookback = 14;
    private final double emaDistanceFactor = 0.20; // abs(emaFast-emaSlow) must exceed avgRange * factor

    public EmaRsiStrategy(int emaFast, int emaSlow, int rsiPeriod, double rsiBuyThreshold, double rsiSellThreshold) {
        if (emaFast < 2 || emaSlow < 2 || rsiPeriod < 2) throw new IllegalArgumentException("Periods must be >= 2");
        if (emaFast >= emaSlow) throw new IllegalArgumentException("emaFast must be < emaSlow");
        this.emaFast = emaFast;
        this.emaSlow = emaSlow;
        this.rsiPeriod = rsiPeriod;
        this.rsiBuyThreshold = rsiBuyThreshold;
        this.rsiSellThreshold = rsiSellThreshold;
    }

    @Override
    public String name() {
        return "EmaRsiStrategy";
    }

    @Override
    public Signal checkSignal(List<Bar> bars) {
        if (bars == null) return Signal.none(name());

        int minBars = Math.max(emaSlow + 10, Math.max(rsiPeriod + 2, rangeLookback));
        if (bars.size() < minBars) return Signal.none(name());

        Bar last = bars.get(bars.size() - 1);

        double emaFastVal = emaClose(bars, emaFast);
        double emaSlowVal = emaClose(bars, emaSlow);
        double rsiVal = rsiClose(bars, rsiPeriod);

        if (!Double.isFinite(emaFastVal) || !Double.isFinite(emaSlowVal) || !Double.isFinite(rsiVal)) {
            return Signal.none(name());
        }

        // (5) compute avg range and require EMA distance
        double avgRange = averageRange(bars, rangeLookback);
        double emaDistance = Math.abs(emaFastVal - emaSlowVal);
        boolean trendStrengthOk = Double.isFinite(avgRange) && emaDistance > (avgRange * emaDistanceFactor);

        if (!trendStrengthOk) {
            return Signal.none(name());
        }

        boolean upTrend = emaFastVal > emaSlowVal;
        boolean downTrend = emaFastVal < emaSlowVal;

        Map<String, Object> meta = new HashMap<>();
        meta.put("emaFast", emaFast);
        meta.put("emaSlow", emaSlow);
        meta.put("rsiPeriod", rsiPeriod);
        meta.put("emaFastVal", emaFastVal);
        meta.put("emaSlowVal", emaSlowVal);
        meta.put("rsiVal", rsiVal);
        meta.put("rsiBuyThreshold", rsiBuyThreshold);
        meta.put("rsiSellThreshold", rsiSellThreshold);
        meta.put("avgRange", avgRange);
        meta.put("emaDistance", emaDistance);
        meta.put("emaDistanceFactor", emaDistanceFactor);

        if (upTrend && rsiVal >= rsiBuyThreshold) {
            return Signal.buy(name(), last.timestamp(), last.close(), meta);
        }
        if (downTrend && rsiVal <= rsiSellThreshold) {
            return Signal.sell(name(), last.timestamp(), last.close(), meta);
        }

        return Signal.none(name());
    }

    private static double emaClose(List<Bar> bars, int period) {
        if (bars.size() < period) return Double.NaN;

        double k = 2.0 / (period + 1.0);

        // seed using SMA of the last 'period' closes
        int start = bars.size() - period;
        double sma = 0.0;
        for (int i = start; i < bars.size(); i++) sma += bars.get(i).close();
        sma /= period;

        double ema = sma;
        for (int i = start + 1; i < bars.size(); i++) {
            ema = bars.get(i).close() * k + ema * (1.0 - k);
        }
        return ema;
    }

    private static double rsiClose(List<Bar> bars, int period) {
        if (bars.size() < period + 1) return Double.NaN;

        double gains = 0.0;
        double losses = 0.0;

        for (int i = bars.size() - period; i < bars.size(); i++) {
            double change = bars.get(i).close() - bars.get(i - 1).close();
            if (change > 0) gains += change;
            else losses += -change;
        }

        if (losses == 0.0) return 100.0;
        double rs = gains / losses;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    private static double averageRange(List<Bar> bars, int lookback) {
        if (bars.size() < lookback) return Double.NaN;

        double sum = 0.0;
        for (int i = bars.size() - lookback; i < bars.size(); i++) {
            Bar b = bars.get(i);
            sum += (b.high() - b.low());
        }
        return sum / lookback;
    }
}
