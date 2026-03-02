package com.github.dayviddouglas.TradingBot.deriv;

import com.github.dayviddouglas.TradingBot.model.Bar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class DerivMarketDataService {
    private static final Logger log = LoggerFactory.getLogger(DerivMarketDataService.class);

    private final DerivWsClient ws;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong reqIdSeq = new AtomicLong(1000);

    // callbacks
    private volatile TickHandler onTick;
    private volatile Consumer<List<Bar>> onCandleHistory;

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

    // ---- requests (JSON) ----

    public long authorize(String token) {
        long reqId = reqIdSeq.getAndIncrement();
        ObjectNode payload = mapper.createObjectNode();
        payload.put("authorize", token);
        payload.put("req_id", reqId);
        ws.send(payload.toString());
        return reqId;
    }

    public long subscribeTicks(String symbol) {
        long reqId = reqIdSeq.getAndIncrement();
        ObjectNode payload = mapper.createObjectNode();
        payload.put("ticks", symbol);
        payload.put("subscribe", 1);
        payload.put("req_id", reqId);
        ws.send(payload.toString());
        log.info("Subscribed ticks symbol={} req_id={}", symbol, reqId);
        return reqId;
    }

    public long fetchCandleHistory(String symbol, int granularitySeconds, int count) {
        long reqId = reqIdSeq.getAndIncrement();
        ObjectNode payload = mapper.createObjectNode();
        payload.put("ticks_history", symbol);
        payload.put("adjust_start_time", 1);
        payload.put("count", count);
        payload.put("end", "latest");
        payload.put("style", "candles");
        payload.put("granularity", granularitySeconds);
        payload.put("req_id", reqId);
        ws.send(payload.toString());
        log.info("Requested candle history symbol={} granularity={} count={} req_id={}",
                symbol, granularitySeconds, count, reqId);
        return reqId;
    }

    // ---- WS routing ----

    private void onWsMessage(String raw) {
        try {
            JsonNode msg = mapper.readTree(raw);

            if (msg.has("error")) {
                log.warn("Deriv error full msg: {}", msg.toString());
                return;
            }

            String msgType = msg.path("msg_type").asText("");
            switch (msgType) {
                case "tick" -> handleTick(msg);
                case "history" -> handleHistory(msg);
                default -> { }
            }
        } catch (Exception e) {
            log.warn("Failed to handle WS message raw={}", raw, e);
        }
    }

    private void handleTick(JsonNode msg) {
        JsonNode tick = msg.get("tick");
        if (tick == null || !tick.isObject()) return;

        String symbol = tick.path("symbol").asText("");
        long epoch = tick.path("epoch").asLong(-1);
        double quote = tick.path("quote").asDouble(Double.NaN);

        TickHandler handler = onTick;
        if (handler != null && !symbol.isBlank() && epoch > 0 && Double.isFinite(quote)) {
            handler.onTick(symbol, epoch, quote);
        }
    }

    private void handleHistory(JsonNode msg) {
        JsonNode candles = msg.get("candles");
        if (candles == null || !candles.isArray()) return;

        List<Bar> bars = new ArrayList<>(candles.size());
        for (JsonNode c : candles) {
            Bar bar = toBar(c);
            if (bar != null) bars.add(bar);
        }

        bars.sort(Comparator.comparing(Bar::timestamp));

        Consumer<List<Bar>> handler = onCandleHistory;
        if (handler != null) handler.accept(List.copyOf(bars));
    }

    private Bar toBar(JsonNode node) {
        long epoch = node.path("epoch").asLong(-1);
        if (epoch <= 0) return null;

        double open = node.path("open").asDouble(Double.NaN);
        double high = node.path("high").asDouble(Double.NaN);
        double low = node.path("low").asDouble(Double.NaN);
        double close = node.path("close").asDouble(Double.NaN);
        double volume = node.path("volume").asDouble(0.0);

        if (!Double.isFinite(open) || !Double.isFinite(high) || !Double.isFinite(low) || !Double.isFinite(close)) {
            return null;
        }

        return new Bar(Instant.ofEpochSecond(epoch), open, high, low, close, volume);
    }
}