package com.github.dayviddouglas.TradingBot.deriv;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Deriv API request builder without Jackson (uses JSON-P).
 */
public class DerivApi {
    private static final Logger log = LoggerFactory.getLogger(DerivApi.class);

    private final DerivWsClient ws;
    private final AtomicLong reqIdSeq = new AtomicLong(1);

    public DerivApi(DerivWsClient ws) {
        this.ws = ws;
    }

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

    public long ping() {
        long reqId = reqIdSeq.getAndIncrement();

        JsonObject payload = Json.createObjectBuilder()
                .add("ping", 1)
                .add("req_id", reqId)
                .build();

        ws.send(payload.toString());
        return reqId;
    }
}
