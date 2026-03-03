package com.github.dayviddouglas.TradingBot.deriv;

import com.github.dayviddouglas.TradingBot.exceptions.DerivErrorException;
import com.github.dayviddouglas.TradingBot.model.Bar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class DerivMarketDataService {
    private static final Logger log = LoggerFactory.getLogger(DerivMarketDataService.class);

    private final DerivWsClient ws;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong reqIdSeq = new AtomicLong(1000);

    private volatile TickHandler onTick;
    private volatile BiConsumer<Long, List<Bar>> onCandleHistory;

    // streaming open contract updates (trade) - may be unreliable in your current environment
    private volatile Consumer<JsonNode> onOpenContract;

    private final Map<Long, CompletableFuture<JsonNode>> pendingByReqId = new ConcurrentHashMap<>();

    // optional diagnostics
    private volatile boolean logRawProposalOpenContract = false;

    @FunctionalInterface
    public interface TickHandler {
        void onTick(String symbol, long epochSeconds, double quote);
    }

    public DerivMarketDataService(DerivWsClient ws) {
        this.ws = ws;
        this.ws.setMessageHandler(this::onWsMessage);
    }

    public void onTick(TickHandler handler) { this.onTick = handler; }

    /** history callback routed by req_id */
    public void onCandleHistory(BiConsumer<Long, List<Bar>> handler) { this.onCandleHistory = handler; }

    public void onOpenContract(Consumer<JsonNode> handler) { this.onOpenContract = handler; }

    public void setLogRawProposalOpenContract(boolean enabled) {
        this.logRawProposalOpenContract = enabled;
    }

    // ----------------------------
    // Requests
    // ----------------------------

    public CompletableFuture<JsonNode> authorize(String token) {
        long reqId = nextReqId();
        ObjectNode payload = mapper.createObjectNode();
        payload.put("authorize", token);
        payload.put("req_id", reqId);

        CompletableFuture<JsonNode> fut = register(reqId);
        ws.send(payload.toString());
        return fut;
    }

    public void authorizeAndWaitFailFast(String token, Duration timeout) {
        try {
            authorize(token)
                    .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .join();
            log.info("Authorize OK");
        } catch (Exception e) {
            throw new IllegalStateException("Falha no authorize (token inválido/sem permissão/trade indisponível)", e);
        }
    }

    public long subscribeTicks(String symbol) {
        long reqId = nextReqId();
        ObjectNode payload = mapper.createObjectNode();
        payload.put("ticks", symbol);
        payload.put("subscribe", 1);
        payload.put("req_id", reqId);
        ws.send(payload.toString());
        log.info("Subscribed ticks symbol={} req_id={}", symbol, reqId);
        return reqId;
    }

    public long fetchCandleHistory(String symbol, int granularitySeconds, int count) {
        long reqId = nextReqId();
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

    // ---- TRADE requests ----

    public CompletableFuture<JsonNode> requestProposal(String symbol,
                                                       String contractType,
                                                       double amount,
                                                       String currency,
                                                       int duration,
                                                       String durationUnit) {
        long reqId = nextReqId();

        ObjectNode payload = mapper.createObjectNode();
        payload.put("proposal", 1);
        payload.put("symbol", symbol);
        payload.put("contract_type", contractType);
        payload.put("amount", amount);
        payload.put("basis", "stake");
        payload.put("currency", currency);
        payload.put("duration", duration);
        payload.put("duration_unit", durationUnit);
        payload.put("req_id", reqId);

        CompletableFuture<JsonNode> fut = register(reqId);
        ws.send(payload.toString());
        return fut;
    }

    public CompletableFuture<JsonNode> buy(String proposalId, double price) {
        long reqId = nextReqId();

        ObjectNode payload = mapper.createObjectNode();
        payload.put("buy", proposalId);
        payload.put("price", price);
        payload.put("req_id", reqId);

        CompletableFuture<JsonNode> fut = register(reqId);
        ws.send(payload.toString());
        return fut;
    }

    public CompletableFuture<JsonNode> subscribeOpenContract(long contractId) {
        long reqId = nextReqId();

        ObjectNode payload = mapper.createObjectNode();
        payload.put("proposal_open_contract", 1);
        payload.put("contract_id", contractId);
        payload.put("subscribe", 1);
        payload.put("req_id", reqId);

        CompletableFuture<JsonNode> fut = register(reqId);
        ws.send(payload.toString());
        return fut;
    }

    /**
     * NEW: One-shot query (poll) for an open/closed contract state.
     * This is the fallback when streaming updates don't arrive.
     */
    public CompletableFuture<JsonNode> getOpenContractOnce(long contractId) {
        long reqId = nextReqId();

        ObjectNode payload = mapper.createObjectNode();
        payload.put("proposal_open_contract", 1);
        payload.put("contract_id", contractId);
        // Do NOT set subscribe here (or explicitly subscribe=0)
        payload.put("req_id", reqId);

        CompletableFuture<JsonNode> fut = register(reqId);
        ws.send(payload.toString());
        return fut;
    }

    /**
     * Optional: stop a subscription.
     */
    public CompletableFuture<JsonNode> forget(String subscriptionId) {
        long reqId = nextReqId();

        ObjectNode payload = mapper.createObjectNode();
        payload.put("forget", subscriptionId);
        payload.put("req_id", reqId);

        CompletableFuture<JsonNode> fut = register(reqId);
        ws.send(payload.toString());
        return fut;
    }

    // ----------------------------
    // WS routing
    // ----------------------------

    private void onWsMessage(String raw) {
        try {
            JsonNode msg = mapper.readTree(raw);

            if (msg.has("error")) {
                handleDerivError(msg);
                return;
            }

            String msgType = msg.path("msg_type").asText("");
            switch (msgType) {
                case "tick" -> handleTick(msg);

                // Deriv often returns msg_type="candles" for ticks_history(style=candles)
                case "history", "candles" -> handleHistory(msg);

                case "authorize", "proposal", "buy", "forget", "proposal_open_contract" -> {
                    // complete one-shot futures (req_id bound)
                    completeReq(msg);

                    // stream contract updates
                    if ("proposal_open_contract".equals(msgType)) {
                        if (logRawProposalOpenContract) {
                            log.info("RAW POC: {}", msg.toString());
                        }
                        Consumer<JsonNode> handler = onOpenContract;
                        if (handler != null) handler.accept(msg);
                    }
                }

                default -> { /* ignore */ }
            }
        } catch (Exception e) {
            log.warn("Failed to handle WS message raw={}", raw, e);
        }
    }

    private void handleDerivError(JsonNode msg) {
        String errMessage = msg.path("error").path("message").asText("Deriv error");
        long reqId = msg.path("req_id").asLong(-1);

        log.warn("Deriv error. req_id={} message={} full={}", reqId, errMessage, msg.toString());

        if (reqId > 0) {
            CompletableFuture<JsonNode> fut = pendingByReqId.remove(reqId);
            if (fut != null) fut.completeExceptionally(new DerivErrorException(errMessage));
        }
    }

    private void completeReq(JsonNode msg) {
        long reqId = msg.path("req_id").asLong(-1);
        if (reqId <= 0) return;

        CompletableFuture<JsonNode> fut = pendingByReqId.get(reqId);
        if (fut != null) fut.complete(msg);
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

        long reqId = msg.path("req_id").asLong(-1);
        BiConsumer<Long, List<Bar>> handler = onCandleHistory;
        if (handler != null) handler.accept(reqId, List.copyOf(bars));
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

    private long nextReqId() {
        return reqIdSeq.getAndIncrement();
    }

    private CompletableFuture<JsonNode> register(long reqId) {
        CompletableFuture<JsonNode> fut = new CompletableFuture<>();
        pendingByReqId.put(reqId, fut);
        fut.whenComplete((ok, err) -> pendingByReqId.remove(reqId)); // avoid memory leak
        return fut;
    }

    @SuppressWarnings("unused")
    private static List<String> sortedFieldNames(JsonNode node) {
        if (node == null || !node.isObject()) return List.of();
        List<String> names = new ArrayList<>();
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) names.add(it.next());
        names.sort(String::compareTo);
        return names;
    }
}