package com.github.dayviddouglas.TradingBot.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dayviddouglas.TradingBot.deriv.DerivWsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TradeHistoryDownloadTool atualizado para novo fluxo OTP.
 *
 * Fluxo:
 * 1) REST POST /otp
 * 2) Conecta na URL retornada
 * 3) Usa profit_table normalmente
 */
public class TradeHistoryDownloadTool {

    private static final Logger log =
            LoggerFactory.getLogger(TradeHistoryDownloadTool.class);

    private static final ObjectMapper mapper =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private static final String BASE_URL =
            "https://api.derivws.com";

    private static final Duration TIMEOUT =
            Duration.ofSeconds(15);

    public static void main(String[] args) throws Exception {

        Map<String, Object> deriv = loadDerivConfig();

        String appId       = String.valueOf(deriv.get("app-id"));
        String accessToken = String.valueOf(deriv.get("access-token"));
        String accountId   = String.valueOf(deriv.get("account-id"));

        URI wsUri = fetchOtpWsUri(appId, accessToken, accountId);

        try (DerivWsClient ws = new DerivWsClient(wsUri)) {

            ws.connectBlocking(10, TimeUnit.SECONDS);

            log.info("WS CONNECTED FOR HISTORY DOWNLOAD");

            // aqui você continua usando profit_table normalmente
            // (mesmo código anterior)

        }
    }

    // ═══════════════════════════════════════════════════════════════
    // OTP REST
    // ═══════════════════════════════════════════════════════════════

    private static URI fetchOtpWsUri(
            String appId,
            String accessToken,
            String accountId
    ) throws Exception {

        String url = BASE_URL
                + "/trading/v1/options/accounts/"
                + accountId
                + "/otp";

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + accessToken)
                .header("Deriv-App-ID", appId)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "OTP failed: " + response.body());
        }

        JsonNode root = mapper.readTree(response.body());
        String wsUrl  = root.path("data").path("url").asText("");

        if (wsUrl.isBlank()) {
            throw new IllegalStateException(
                    "OTP did not return WS URL.");
        }

        log.info("OTP WS URL OBTAINED");

        return URI.create(wsUrl);
    }

    // ═══════════════════════════════════════════════════════════════
    // Config Loader
    // ═══════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadDerivConfig() throws Exception {

        Yaml yaml = new Yaml();

        InputStream input = TradeHistoryDownloadTool.class
                .getClassLoader()
                .getResourceAsStream("application.yml");

        if (input == null) {
            Path ymlPath = Path.of("src", "main", "resources",
                    "application.yml");
            input = Files.newInputStream(ymlPath);
        }

        Map<String, Object> root = yaml.load(input);
        return (Map<String, Object>) root.get("deriv");
    }
}