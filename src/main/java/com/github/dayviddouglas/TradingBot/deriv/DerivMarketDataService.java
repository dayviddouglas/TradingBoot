package com.github.dayviddouglas.TradingBot.deriv;

import com.github.dayviddouglas.TradingBot.model.Bar;
import jakarta.json.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class DerivMarketDataService {
    private static final Logger log = LoggerFactory.getLogger(DerivMarketDataService.class);

    private final DerivWsClient ws;
    private final AtomicLong reqIdSeq = new AtomicLong(1000);

    // ---- handlers (callbacks) ----
    private volatile BiConsumer<Long, Double> onTick;     // (epochSeconds, quote)
    private volatile Consumer<Bar> onCandle;
    private volatile Consumer<List<Bar>> onCandleHistory;

    public DerivMarketDataService(DerivWsClient ws) {
        this.ws = ws;
        this.ws.setMessageHandler(this::onWsMessage);
    }

    public void onTick(BiConsumer<Long, Double> handler) { this.onTick = handler; }
    public void onCandle(Consumer<Bar> handler) { this.onCandle = handler; }
    public void onCandleHistory(Consumer<List<Bar>> handler) { this.onCandleHistory = handler; }

    // ---- requests ----

    public long authorize(String token) {
        long reqId = reqIdSeq.getAndIncrement();
        JsonObject payload = Json.createObjectBuilder()
                .add("authorize", token)
                .add("req_id", reqId)
                .build();
        ws.send(payload.toString());
        log.info("Sent authorize req_id={}", reqId);
        return reqId;
    }

    public long subscribeTicks(String symbol) {
        long reqId = reqIdSeq.getAndIncrement();
        JsonObject payload = Json.createObjectBuilder()
                .add("ticks", symbol)
                .add("subscribe", 1)
                .add("req_id", reqId)
                .build();
        ws.send(payload.toString());
        log.info("Subscribed ticks symbol={} req_id={}", symbol, reqId);
        return reqId;
    }

    public long fetchCandleHistory(String symbol, int granularitySeconds, int count) {
        long reqId = reqIdSeq.getAndIncrement();
        JsonObject payload = Json.createObjectBuilder()
                .add("ticks_history", symbol)
                .add("adjust_start_time", 1)
                .add("count", count)
                .add("end", "latest")
                .add("style", "candles")
                .add("granularity", granularitySeconds)
                .add("req_id", reqId)
                .build();
        ws.send(payload.toString());
        log.info("Requested candle history symbol={} granularity={} count={} req_id={}",
                symbol, granularitySeconds, count, reqId);
        return reqId;
    }

    // Keep this method if you want, but we will not use it in the runner anymore.
    public long subscribeCandles(String symbol, int granularitySeconds) {
        long reqId = reqIdSeq.getAndIncrement();
        JsonObject payload = Json.createObjectBuilder()
                .add("candles", symbol)
                .add("subscribe", 1)
                .add("granularity", granularitySeconds)
                .add("req_id", reqId)
                .build();
        ws.send(payload.toString());
        log.info("Subscribed candles symbol={} granularity={} req_id={}", symbol, granularitySeconds, reqId);
        return reqId;
    }

    // ---- incoming message routing ----

    private void onWsMessage(String raw) {
        try (JsonReader reader = Json.createReader(new StringReader(raw))) {
            JsonObject msg = reader.readObject();

            if (msg.containsKey("error")) {
                // Log full message so we can see echo_req / msg_type
                log.warn("Deriv error full msg: {}", msg);
                return;
            }

            String msgType = msg.getString("msg_type", "");
            switch (msgType) {
                case "tick" -> handleTick(msg);
                case "history" -> handleHistory(msg);
                case "candles" -> handleCandles(msg);
                case "ohlc" -> handleOhlc(msg);
                default -> { }
            }
        } catch (Exception e) {
            log.warn("Failed to handle WS message raw={}", raw, e);
        }
    }

    private void handleTick(JsonObject msg) {
        JsonObject tick = msg.getJsonObject("tick");
        if (tick == null) return;

        Double quote = getDouble(tick, "quote");
        Long epoch = getLong(tick, "epoch"); // epoch is present on tick stream

        BiConsumer<Long, Double> handler = onTick;
        if (handler != null && quote != null && epoch != null) {
            handler.accept(epoch, quote);
        }
    }

    private void handleHistory(JsonObject msg) {
        JsonArray candles = msg.getJsonArray("candles");
        if (candles == null) return;

        List<Bar> bars = new ArrayList<>(candles.size());
        for (JsonValue v : candles) {
            if (v.getValueType() != JsonValue.ValueType.OBJECT) continue;
            Bar bar = toBar(v.asJsonObject());
            if (bar != null) bars.add(bar);
        }

        bars.sort(Comparator.comparing(Bar::timestamp));

        Consumer<List<Bar>> handler = onCandleHistory;
        if (handler != null) handler.accept(Collections.unmodifiableList(bars));
    }

    private void handleCandles(JsonObject msg) {
        JsonArray candles = msg.getJsonArray("candles");
        if (candles == null || candles.isEmpty()) return;

        JsonValue last = candles.get(candles.size() - 1);
        if (last.getValueType() != JsonValue.ValueType.OBJECT) return;

        Bar bar = toBar(last.asJsonObject());
        Consumer<Bar> handler = onCandle;
        if (handler != null && bar != null) handler.accept(bar);
    }

    private void handleOhlc(JsonObject msg) {
        JsonObject ohlc = msg.getJsonObject("ohlc");
        if (ohlc == null) return;

        Bar bar = toBar(ohlc);
        Consumer<Bar> handler = onCandle;
        if (handler != null && bar != null) handler.accept(bar);
    }

    private Bar toBar(JsonObject node) {
        Long epoch = getLong(node, "epoch");
        if (epoch == null || epoch <= 0) return null;

        Double open = getDouble(node, "open");
        Double high = getDouble(node, "high");
        Double low = getDouble(node, "low");
        Double close = getDouble(node, "close");
        double volume = getDouble(node, "volume") != null ? getDouble(node, "volume") : 0.0;

        if (open == null || high == null || low == null || close == null) return null;

        return new Bar(Instant.ofEpochSecond(epoch), open, high, low, close, volume);
    }

    private static Double getDouble(JsonObject obj, String key) {
        if (!obj.containsKey(key) || obj.isNull(key)) return null;
        JsonValue v = obj.get(key);

        return switch (v.getValueType()) {
            case NUMBER -> ((JsonNumber) v).doubleValue();
            case STRING -> {
                try { yield Double.parseDouble(((JsonString) v).getString()); }
                catch (Exception e) { yield null; }
            }
            default -> null;
        };
    }

    private static Long getLong(JsonObject obj, String key) {
        if (!obj.containsKey(key) || obj.isNull(key)) return null;
        JsonValue v = obj.get(key);

        return switch (v.getValueType()) {
            case NUMBER -> ((JsonNumber) v).longValue();
            case STRING -> {
                try { yield Long.parseLong(((JsonString) v).getString()); }
                catch (Exception e) { yield null; }
            }
            default -> null;
        };
    }
}