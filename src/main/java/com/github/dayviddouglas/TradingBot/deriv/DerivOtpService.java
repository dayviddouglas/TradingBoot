package com.github.dayviddouglas.TradingBot.deriv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dayviddouglas.TradingBot.config.DerivProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Serviço responsável por obter o OTP da API REST da Deriv.
 *
 * Correção v5.3.1:
 * Adicionado retry automático com backoff para o erro 429
 * (rate limiting do Cloudflare — error code 1015).
 * O retry aguarda intervalos crescentes antes de tentar novamente,
 * respeitando o limite de requisições da API.
 */
@Service
public class DerivOtpService {


    private static final Logger log =
            LoggerFactory.getLogger(DerivOtpService.class);

    private static final String BASE_URL =
            "https://api.derivws.com";

    private static final Duration REQUEST_TIMEOUT =
            Duration.ofSeconds(15);

    /**
     * Máximo de tentativas antes de desistir.
     */
    private static final int MAX_RETRIES = 5;

    /**
     * Delays entre tentativas em milissegundos.
     * Crescente para respeitar o rate limit do Cloudflare.
     */
    private static final long[] RETRY_DELAYS_MS = {
            5_000,   // 5s
            15_000,  // 15s
            30_000,  // 30s
            60_000,  // 1min
            120_000  // 2min
    };

    private final DerivProperties props;
    private final ObjectMapper    mapper;
    private final HttpClient      httpClient;

    public DerivOtpService(
            DerivProperties props,
            ObjectMapper mapper
    ) {
        this.props      = props;
        this.mapper     = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .build();
    }

    /**
     * Obtém o OTP da API REST com retry automático para 429.
     *
     * @return URI do WebSocket autenticado com OTP embutido
     * @throws IllegalStateException se todas as tentativas falharem
     */
    public URI fetchWsUri() {
        String accountId   = props.getAccountId();
        String accessToken = props.getAccessToken();
        String appId       = props.getAppId();

        String url = BASE_URL
                + "/trading/v1/options/accounts/"
                + accountId
                + "/otp";

        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                log.info("OTP REQUEST | attempt={}/{} | accountId={}",
                        attempt, MAX_RETRIES, accountId);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Deriv-App-ID",  appId)
                        .header("Content-Type",  "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build();

                HttpResponse<String> response = httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofString());

                int status = response.statusCode();

                log.info("OTP RESPONSE | status={} | attempt={}",
                        status, attempt);

                // Sucesso
                if (status == 200) {
                    return parseWsUri(response.body());
                }

                // Rate limit — aguarda e tenta novamente
                if (status == 429) {
                    long delayMs = RETRY_DELAYS_MS[
                            Math.min(attempt - 1, RETRY_DELAYS_MS.length - 1)];

                    log.warn("OTP RATE LIMITED (429) | attempt={}/{} "
                                    + "| waiting={}ms before retry...",
                            attempt, MAX_RETRIES, delayMs);

                    if (attempt < MAX_RETRIES) {
                        Thread.sleep(delayMs);
                        continue;
                    }
                }

                // Outro erro HTTP — não faz retry
                throw new IllegalStateException(
                        "OTP request failed | status=" + status
                                + " | body=" + response.body());

            } catch (IllegalStateException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "OTP request interrupted", e);
            } catch (Exception e) {
                lastException = e;
                log.warn("OTP REQUEST ERROR | attempt={}/{} | error={}",
                        attempt, MAX_RETRIES, e.getMessage());

                if (attempt < MAX_RETRIES) {
                    long delayMs = RETRY_DELAYS_MS[
                            Math.min(attempt - 1, RETRY_DELAYS_MS.length - 1)];
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                "OTP request interrupted", ie);
                    }
                }
            }
        }

        throw new IllegalStateException(
                "OTP request failed after " + MAX_RETRIES
                        + " attempts", lastException);
    }

    /**
     * Parseia o corpo da resposta e extrai a URI do WebSocket.
     */
    private URI parseWsUri(String responseBody) {
        try {
            JsonNode root  = mapper.readTree(responseBody);
            String   wsUrl = root.path("data").path("url").asText("");

            if (wsUrl.isBlank()) {
                throw new IllegalStateException(
                        "OTP response did not contain WebSocket URL. "
                                + "body=" + responseBody);
            }

            log.info("OTP WS URL OBTAINED | url={}",
                    wsUrl.replaceAll("otp=[^&]+", "otp=***"));

            return URI.create(wsUrl);

        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to parse OTP response: "
                            + e.getMessage(), e);
        }
    }
}