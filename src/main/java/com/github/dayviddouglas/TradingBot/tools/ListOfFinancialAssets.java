package com.github.dayviddouglas.TradingBot.tools;

import com.github.dayviddouglas.TradingBot.deriv.DerivWsClient;
import jakarta.json.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static jakarta.json.stream.JsonGenerator.PRETTY_PRINTING;

/**
 * One-shot tool:
 * - connect to Deriv WS
 * - request active_symbols
 * - save response into ./data/active_symbols.json (overwrite)
 * - exit
 */
public class ListOfFinancialAssets {
    private static final Logger log = LoggerFactory.getLogger(ListOfFinancialAssets.class);

    public static void main(String[] args) throws Exception {
        int appId = 1089;
        String endpoint = "wss://ws.derivws.com/websockets/v3?app_id=" + appId;

        Path outFile = Path.of("data", "active_symbols.simple.json");
        CountDownLatch latch = new CountDownLatch(1);

        try (DerivWsClient ws = new DerivWsClient(new URI(endpoint))) {

            ws.setMessageHandler(raw -> {
                try (JsonReader reader = Json.createReader(new StringReader(raw))) {
                    JsonObject msg = reader.readObject();

                    if (msg.containsKey("error")) {
                        log.error("Deriv error: {}", msg);
                        latch.countDown();
                        return;
                    }

                    String msgType = msg.getString("msg_type", "");
                    if (!"active_symbols".equals(msgType)) return;

                    JsonObject simplified = simplifyActiveSymbols(msg);
                    String pretty = prettyPrint(simplified);

                    writeTextAtomic(outFile, pretty);
                    log.info("Saved simplified active symbols to {}", outFile.toAbsolutePath());

                    latch.countDown();
                } catch (Exception e) {
                    log.error("Failed to parse/process message: {}", raw, e);
                    latch.countDown();
                }
            });

            ws.connectBlocking(10, TimeUnit.SECONDS);

            JsonObject payload = Json.createObjectBuilder()
                    .add("active_symbols", "brief")
                    .add("product_type", "basic")
                    .build();

            log.info("Sending: {}", payload);
            ws.send(payload.toString());

            if (!latch.await(15, TimeUnit.SECONDS)) {
                log.warn("Timeout waiting for active_symbols response");
            }
        }
    }

    private static JsonObject simplifyActiveSymbols(JsonObject fullMsg) {
        JsonArray arr = fullMsg.getJsonArray("active_symbols");
        if (arr == null) arr = JsonValue.EMPTY_JSON_ARRAY;

        // Build simplified array
        JsonArrayBuilder assets = Json.createArrayBuilder();

        // Sort by market then display_name (makes file easier to browse)
        arr.stream()
                .filter(v -> v.getValueType() == JsonValue.ValueType.OBJECT)
                .map(JsonValue::asJsonObject)
                .sorted(Comparator
                        .comparing((JsonObject o) -> o.getString("market", ""))
                        .thenComparing(o -> o.getString("display_name", "")))
                .forEach(o -> assets.add(Json.createObjectBuilder()
                        .add("symbol", o.getString("symbol", ""))
                        .add("name", o.getString("display_name", ""))
                        .add("market", o.getString("market", ""))
                        .add("submarket", o.getString("submarket", ""))
                        .add("open", o.getInt("exchange_is_open", 0) == 1)
                        .add("suspended", o.getInt("is_trading_suspended", 0) == 1)
                        .add("pip", getJsonNumberOrZero(o, "pip"))
                ));

        return Json.createObjectBuilder()
                .add("generated_at_epoch", Instant.now().getEpochSecond())
                .add("count", arr.size())
                .add("assets", assets)
                .build();
    }

    private static JsonNumber getJsonNumberOrZero(JsonObject obj, String key) {
        try {
            JsonValue v = obj.get(key);
            if (v != null && v.getValueType() == JsonValue.ValueType.NUMBER) {
                return (JsonNumber) v;
            }
        } catch (Exception ignored) {
        }
        return Json.createValue(0);
    }

    private static String prettyPrint(JsonObject obj) {
        JsonWriterFactory factory = Json.createWriterFactory(
                java.util.Map.of(PRETTY_PRINTING, true)
        );

        java.io.StringWriter sw = new java.io.StringWriter();
        try (JsonWriter w = factory.createWriter(sw)) {
            w.writeObject(obj);
        }
        return sw.toString();
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