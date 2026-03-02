package com.github.dayviddouglas.TradingBot.tools.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dayviddouglas.TradingBot.deriv.DerivWsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class DerivHistoryDownloadTool {
    private static final Logger log = LoggerFactory.getLogger(DerivHistoryDownloadTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    // ====== CONFIG FIXA (edite aqui) ======
    private static final int APP_ID = 1089;
    private static final String SYMBOL = "frxUSDJPY";
    private static final int GRANULARITY_SECONDS = 60; // 60=1m, 300=5m
    private static final int DAYS_BACK = 90;
    private static final int COUNT_PER_REQUEST = 1000;
    private static final int TIMEOUT_SECONDS = 60;
    // ======================================

    private static final Set<String> ACCEPTED_MSG_TYPES = Set.of("history", "ticks_history", "candles");

    public static void main(String[] args) throws Exception {
        String endpoint = "wss://ws.derivws.com/websockets/v3?app_id=" + APP_ID;

        long endEpoch = Instant.now().getEpochSecond();
        long startEpochTarget = Instant.now().minus(DAYS_BACK, ChronoUnit.DAYS).getEpochSecond();

        HistoryFileStore store = new HistoryFileStore();

        ArrayNode allCandles = mapper.createArrayNode();
        Set<Long> seenEpochs = new HashSet<>();

        try (DerivWsClient ws = new DerivWsClient(new URI(endpoint))) {
            ws.connectBlocking(10, TimeUnit.SECONDS);

            long currentEnd = endEpoch;
            int page = 0;

            while (true) {
                page++;

                final int pageNo = page;
                CountDownLatch latch = new CountDownLatch(1);
                final ObjectNode[] pageMsgHolder = new ObjectNode[1];

                ws.setMessageHandler(raw -> {
                    try {
                        JsonNode msg = mapper.readTree(raw);

                        if (msg.has("error")) {
                            log.error("Deriv error: {}", msg.toString());
                            latch.countDown();
                            return;
                        }

                        String msgType = msg.path("msg_type").asText("");
                        if (!ACCEPTED_MSG_TYPES.contains(msgType)) return;

                        JsonNode echo = msg.get("echo_req");
                        if (echo != null && !echo.isNull()) {
                            String echoSymbol = echo.path("ticks_history").asText("");
                            int echoGran = echo.path("granularity").asInt(-1);

                            if (!echoSymbol.isBlank() && (!SYMBOL.equals(echoSymbol) || GRANULARITY_SECONDS != echoGran)) {
                                return;
                            }
                        }

                        if (msg.isObject()) pageMsgHolder[0] = (ObjectNode) msg;
                        latch.countDown();
                    } catch (Exception e) {
                        log.error("Falha ao processar mensagem (page={}).", pageNo, e);
                        latch.countDown();
                    }
                });

                ObjectNode req = mapper.createObjectNode();
                req.put("ticks_history", SYMBOL);
                req.put("style", "candles");
                req.put("granularity", GRANULARITY_SECONDS);
                req.put("count", COUNT_PER_REQUEST);
                req.put("end", currentEnd);
                req.put("adjust_start_time", 1);
                req.put("req_id", pageNo);

                ws.send(req.toString());

                if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timeout aguardando página " + pageNo + " da Deriv");
                }

                ObjectNode pageMsg = pageMsgHolder[0];
                if (pageMsg == null) {
                    throw new IllegalStateException("Resposta vazia na página " + pageNo);
                }

                JsonNode candlesNode = pageMsg.get("candles");
                if (candlesNode == null || !candlesNode.isArray() || candlesNode.isEmpty()) {
                    break;
                }

                long oldestEpochInPage = Long.MAX_VALUE;

                for (JsonNode c : candlesNode) {
                    long epoch = c.path("epoch").asLong(-1);
                    if (epoch <= 0) continue;

                    if (seenEpochs.add(epoch)) {
                        allCandles.add(c);
                    }

                    if (epoch < oldestEpochInPage) oldestEpochInPage = epoch;
                }

                if (oldestEpochInPage == Long.MAX_VALUE) break;

                if (oldestEpochInPage <= startEpochTarget) {
                    break;
                }

                long nextEnd = oldestEpochInPage - 1;
                if (nextEnd >= currentEnd) break;

                currentEnd = nextEnd;

                if (pageNo > 5000) {
                    throw new IllegalStateException("Paginação excedeu limite de páginas (5000). Abortando.");
                }
            }

            ObjectNode out = mapper.createObjectNode();
            out.put("symbol", SYMBOL);
            out.put("granularitySeconds", GRANULARITY_SECONDS);
            out.put("daysBackRequested", DAYS_BACK);
            out.put("generatedAtEpoch", Instant.now().getEpochSecond());
            out.put("candlesCount", allCandles.size());
            out.set("candles", allCandles);

            store.write(SYMBOL, GRANULARITY_SECONDS, out);

            log.info("History saved: {}", store.pathFor(SYMBOL, GRANULARITY_SECONDS).toAbsolutePath());
        }
    }
}