package com.github.dayviddouglas.TradingBot.model;

import java.time.Instant;

/**
 * Market bar/candle (OHLCV) for a given timeframe.
 *
 * Contract:
 * - timestamp: epoch second representing the candle open time (UTC).
 * - prices: double for MVP (in production consider BigDecimal).
 */
public record Bar(
        Instant timestamp,
        double open,
        double high,
        double low,
        double close,
        double volume
) { 
    
}