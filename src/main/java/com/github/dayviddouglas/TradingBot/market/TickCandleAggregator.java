package com.github.dayviddouglas.TradingBot.market;
import com.github.dayviddouglas.TradingBot.model.Bar;

import java.time.Instant;
import java.util.function.Consumer;

/**
 * Aggregates tick quotes into OHLC candles for a fixed granularity (seconds).
 *
 * Rules:
 * - Each tick belongs to a time bucket: (epochSec / granularity) * granularity
 * - When bucket changes, previous candle is considered "closed" and emitted.
 * - The new bucket starts a new candle with O=H=L=C=tickQuote.
 */
public class TickCandleAggregator {

    private final int granularitySeconds;
    private final Consumer<Bar> onCandleClosed;

    private long currentBucketStartEpoch = -1;

    private double open;
    private double high;
    private double low;
    private double close;
    private double volume; // Deriv ticks usually don't include volume; keep 0.

    public TickCandleAggregator(int granularitySeconds, Consumer<Bar> onCandleClosed) {
        if (granularitySeconds <= 0) throw new IllegalArgumentException("granularitySeconds must be > 0");
        this.granularitySeconds = granularitySeconds;
        this.onCandleClosed = onCandleClosed;
    }

    public void onTick(long tickEpochSeconds, double quote) {
        long bucketStart = (tickEpochSeconds / granularitySeconds) * granularitySeconds;

        if (currentBucketStartEpoch < 0) {
            startNew(bucketStart, quote);
            return;
        }

        if (bucketStart == currentBucketStartEpoch) {
            // update current candle
            close = quote;
            if (quote > high) high = quote;
            if (quote < low) low = quote;
            return;
        }

        if (bucketStart > currentBucketStartEpoch) {
            // close previous candle
            Bar closed = new Bar(
                    Instant.ofEpochSecond(currentBucketStartEpoch),
                    open, high, low, close, volume
            );
            if (onCandleClosed != null) onCandleClosed.accept(closed);

            // start new candle
            startNew(bucketStart, quote);
        }
        // if bucketStart < currentBucketStartEpoch: ignore out-of-order tick (MVP)
    }

    private void startNew(long bucketStart, double quote) {
        currentBucketStartEpoch = bucketStart;
        open = quote;
        high = quote;
        low = quote;
        close = quote;
        volume = 0.0;
    }
}

