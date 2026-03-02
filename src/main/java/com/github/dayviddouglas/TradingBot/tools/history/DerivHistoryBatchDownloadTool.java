package com.github.dayviddouglas.TradingBot.tools.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dayviddouglas.TradingBot.config.StrategiesConfigLoader;
import com.github.dayviddouglas.TradingBot.config.StrategiesProfile;
import com.github.dayviddouglas.TradingBot.deriv.DerivWsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tool separada (batch):
 * - Lê TODOS os profiles de configs/strategies.json
 * - Para cada (symbol, granularitySeconds), baixa histórico dos últimos N dias
 * - Salva em data/history/<symbol>_<granularity>.json
 *
 * Observação:
 * - Este batch faz 1 request por profile (sem paginação).
 * - Para janelas grandes, recomendo usar o DerivHistoryDownloadTool (com paginação).
 */
public class DerivHistoryBatchDownloadTool {
    private static final Logger log = LoggerFactory.getLogger(DerivHistoryBatchDownloadTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    // ====== CONFIG FIXA ======
    private static final int APP_ID = 1089;
    private static final int DAYS_BACK = 90;
    private static final long DELAY_BETWEEN_REQUESTS_MS = 500;
    // =========================

    private static final Set<String> ACCEPTED_MSG_TYPES = Set.of("history", "ticks_history", "candles");

    public static void main(String[] args) throws Exception {
        String endpoint = "wss://ws.derivws.com/websockets/v3?app_id=" + APP_ID;

        StrategiesConfigLoader loader = new StrategiesConfigLoader();
        List<StrategiesProfile> profiles = loader.getProfiles();

        if (profiles.isEmpty()) {
            throw new IllegalStateException("Nenhum profile encontrado em configs/strategies.json");
        }

        log.info("Batch history download starting. profiles={} daysBack={} endpoint={}",
                profiles.size(), DAYS_BACK, endpoint);

        HistoryFileStore store = new HistoryFileStore();

        long endEpoch = Instant.now().getEpochSecond();
        long startEpoch = Instant.now().minus(DAYS_BACK, ChronoUnit.DAYS).getEpochSecond();

        try (DerivWsClient ws = new DerivWsClient(new URI(endpoint))) {
            ws.connectBlocking(10, TimeUnit.SECONDS);

            for (int i = 0; i < profiles.size(); i++) {
                StrategiesProfile p = profiles.get(i);
                String symbol = p.getSymbol();
                int gran = p.getGranularitySeconds();

                log.info("Requesting history ({}/{}) symbol={} granularity={}s",
                        (i + 1), profiles.size(), symbol, gran);

                CountDownLatch latch = new CountDownLatch(1);

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
                        if (echo != null) {
                            String echoSymbol = echo.path("ticks_history").asText("");
                            int echoGran = echo.path("granularity").asInt(-1);

                            if (!symbol.equals(echoSymbol) || gran != echoGran) {
                                return;
                            }
                        }

                        store.write(symbol, gran, msg);
                        log.info("Saved: {}", store.pathFor(symbol, gran).toAbsolutePath());

                        latch.countDown();
                    } catch (Exception e) {
                        log.error("Failed to parse/process message raw={}", raw, e);
                        latch.countDown();
                    }
                });

                ObjectNode req = mapper.createObjectNode();
                req.put("ticks_history", symbol);
                req.put("style", "candles");
                req.put("granularity", gran);
                req.put("start", startEpoch);
                req.put("end", endEpoch);
                req.put("adjust_start_time", 1);
                req.put("req_id", 1);

                ws.send(req.toString());

                if (!latch.await(30, TimeUnit.SECONDS)) {
                    log.warn("Timeout waiting history for symbol={} granularity={}s", symbol, gran);
                }

                Thread.sleep(DELAY_BETWEEN_REQUESTS_MS);
            }
        }

        log.info("Batch history download finished.");
    }
}