package com.github.dayviddouglas.TradingBot.tools;

import com.github.dayviddouglas.TradingBot.deriv.DerivWsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * One-shot tool:
 * - connect to Deriv WS
 * - request active_symbols
 * - save response into ./data/active_symbols.json (overwrite)
 * - exit
 */
public class ListOfFinancialAssets {
    private static final Logger log = LoggerFactory.getLogger(ListOfFinancialAssets.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        int appId = 1089;
        String endpoint = "wss://ws.derivws.com/websockets/v3?app_id=" + appId;

        Path outFile = Path.of("data", "active_symbols.simple.json");
        CountDownLatch latch = new CountDownLatch(1);

        try (DerivWsClient ws = new DerivWsClient(new URI(endpoint))) {

            ws.setMessageHandler(raw -> {
                try {
                    JsonNode msg = mapper.readTree(raw);

                    if (msg.has("error")) {
                        log.error("Deriv error: {}", msg.toString());
                        latch.countDown();
                        return;
                    }

                    String msgType = msg.path("msg_type").asText("");
                    if (!"active_symbols".equals(msgType)) return;

                    JsonNode simplified = simplifyActiveSymbols(msg);
                    String pretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(simplified);

                    writeTextAtomic(outFile, pretty);
                    log.info("Saved simplified active symbols to {}", outFile.toAbsolutePath());

                    latch.countDown();
                } catch (Exception e) {
                    log.error("Failed to parse/process message raw={}", raw, e);
                    latch.countDown();
                }
            });

            ws.connectBlocking(10, TimeUnit.SECONDS);

            ObjectNode payload = mapper.createObjectNode();
            payload.put("active_symbols", "brief");
            payload.put("product_type", "basic");

            log.info("Sending: {}", payload.toString());
            ws.send(payload.toString());

            if (!latch.await(15, TimeUnit.SECONDS)) {
                log.warn("Timeout waiting for active_symbols response");
            }
        }
    }

    private static JsonNode simplifyActiveSymbols(JsonNode fullMsg) {
        var root = mapper.createObjectNode();
        var assetsArr = mapper.createArrayNode();

        JsonNode arr = fullMsg.get("active_symbols");
        if (arr != null && arr.isArray()) {
            for (JsonNode o : arr) {
                ObjectNode item = mapper.createObjectNode();
                item.put("symbol", o.path("symbol").asText(""));
                item.put("name", o.path("display_name").asText(""));
                item.put("market", o.path("market").asText(""));
                item.put("submarket", o.path("submarket").asText(""));
                item.put("open", o.path("exchange_is_open").asInt(0) == 1);
                item.put("suspended", o.path("is_trading_suspended").asInt(0) == 1);
                item.put("pip", o.path("pip").asDouble(0.0));
                assetsArr.add(item);
            }
        }

        root.put("count", assetsArr.size());
        root.set("assets", assetsArr);
        return root;
    }

    private static void writeTextAtomic(Path outFile, String text) throws IOException {
        Files.createDirectories(outFile.getParent());

        Path tmp = outFile.resolveSibling(outFile.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        try {
            Files.move(tmp, outFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, outFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}