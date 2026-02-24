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
import java.util.function.Consumer;

public class DerivMarketDataService {
    private static final Logger log = LoggerFactory.getLogger(DerivMarketDataService.class);

    private final DerivWsClient ws;
    private final AtomicLong reqIdSeq = new AtomicLong(1000);

    // ---- callbacks ----
    private volatile TickHandler onTick;                 // (symbol, epochSeconds, quote)
    private volatile Consumer<List<Bar>> onCandleHistory; // history response (one-shot)

    @FunctionalInterface
    public interface TickHandler {
        void onTick(String symbol, long epochSeconds, double quote);
    }

    public DerivMarketDataService(DerivWsClient ws) {
        this.ws = ws;
        this.ws.setMessageHandler(this::onWsMessage);
    }

    public void onTick(TickHandler handler) { this.onTick = handler; }
    public void onCandleHistory(Consumer<List<Bar>> handler) { this.onCandleHistory = handler; }

    // ---- requests ----

    public long authorize(String token) {
        long reqId = reqIdSeq.getAndIncrement();
        JsonObject payload = Json.createObjectBuilder()
                .add("authorize", token)
                .add("req_id", reqId)
                .build();
        ws.send(payload.toString());
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

    /**
     * Best-effort seed (optional). Can fail sometimes with Deriv WrongResponse.
     */
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

    // ---- WS routing ----

    private void onWsMessage(String raw) {
        try (JsonReader reader = Json.createReader(new StringReader(raw))) {
            JsonObject msg = reader.readObject();

            if (msg.containsKey("error")) {
                log.warn("Deriv error full msg: {}", msg);
                return;
            }

            String msgType = msg.getString("msg_type", "");
            switch (msgType) {
                case "tick" -> handleTick(msg);
                case "history" -> handleHistory(msg);
                default -> { }
            }
        } catch (Exception e) {
            log.warn("Failed to handle WS message raw={}", raw, e);
        }
    }

    private void handleTick(JsonObject msg) {
        JsonObject tick = msg.getJsonObject("tick");
        if (tick == null) return;

        String symbol = tick.getString("symbol", "");
        Long epoch = getLong(tick, "epoch");
        Double quote = getDouble(tick, "quote");

        TickHandler handler = onTick;
        if (handler != null && !symbol.isBlank() && epoch != null && quote != null) {
            handler.onTick(symbol, epoch, quote);
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