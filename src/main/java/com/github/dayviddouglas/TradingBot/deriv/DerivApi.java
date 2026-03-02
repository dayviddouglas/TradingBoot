package com.github.dayviddouglas.TradingBot.deriv;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.concurrent.atomic.AtomicLong;


public class DerivApi {

    private final DerivWsClient ws;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong reqIdSeq = new AtomicLong(1);

    public DerivApi(DerivWsClient ws) {
        this.ws = ws;
    }

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
        return reqId;
    }
}