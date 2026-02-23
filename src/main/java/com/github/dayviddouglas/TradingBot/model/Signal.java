package com.github.dayviddouglas.TradingBot.model;

import java.time.Instant;
import java.util.Map;

public class Signal {
    public enum Type { BUY, SELL, NONE }

    private final Type type;
    private final String strategy;
    private final Instant timestamp;
    private final double price;
    private final Map<String, Object> metadata;

    private Signal(Type type, String strategy, Instant timestamp, double price, Map<String, Object> metadata) {
        this.type = type;
        this.strategy = strategy;
        this.timestamp = timestamp;
        this.price = price;
        this.metadata = metadata;
    }

    public static Signal none(String strategy) {
        return new Signal(Type.NONE, strategy, null, Double.NaN, Map.of());
    }

    public static Signal buy(String strategy, Instant timestamp, double price, Map<String, Object> metadata) {
        return new Signal(Type.BUY, strategy, timestamp, price, metadata == null ? Map.of() : Map.copyOf(metadata));
    }

    public static Signal sell(String strategy, Instant timestamp, double price, Map<String, Object> metadata) {
        return new Signal(Type.SELL, strategy, timestamp, price, metadata == null ? Map.of() : Map.copyOf(metadata));
    }

    public Type getType() { return type; }
    public String getStrategy() { return strategy; }
    public Instant getTimestamp() { return timestamp; }
    public double getPrice() { return price; }
    public Map<String, Object> getMetadata() { return metadata; }

    @Override
    public String toString() {
        return "Signal{" +
                "type=" + type +
                ", strategy='" + strategy + '\'' +
                ", timestamp=" + timestamp +
                ", price=" + price +
                ", metadata=" + metadata +
                '}';
    }
}
