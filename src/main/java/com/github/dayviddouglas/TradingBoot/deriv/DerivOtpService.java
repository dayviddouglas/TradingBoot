package com.github.dayviddouglas.TradingBoot.deriv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dayviddouglas.TradingBoot.config.core.DerivProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Responsável por obter a URI WebSocket autenticada da API REST da Deriv
 * via troca do Personal Access Token (PAT) por um OTP de uso único.
 *
 * O OTP é obtido via {@code POST} ao endpoint
 * {@code /trading/v1/options/accounts/{accountId}/otp}, utilizando o PAT
 * como Bearer no header {@code Authorization} e o App ID no header {@code Deriv-App-ID}.
 * A resposta contém a URI WebSocket com o OTP embutido, pronta para conexão autenticada.
 *
 * Implementa retry automático com backoff crescente para o código HTTP {@code 429},
 * gerado pelo rate limiting do Cloudflare (error code 1015). Os intervalos de espera
 * seguem a sequência definida em {@link #RETRY_DELAYS_MS}, crescendo progressivamente
 * até o limite de {@value MAX_RETRIES} tentativas.
 */
@Service
public class DerivOtpService {

    private static final Logger log =
            LoggerFactory.getLogger(DerivOtpService.class);

    /** URL base da API REST da Deriv. */
    private static final String BASE_URL =
            "https://api.derivws.com";

    /** Timeout aplicado tanto na conexão quanto em cada requisição HTTP. */
    private static final Duration REQUEST_TIMEOUT =
            Duration.ofSeconds(15);

    /** Número máximo de tentativas antes de lançar {@link IllegalStateException}. */
    private static final int MAX_RETRIES = 5;

    /**
     * Delays em milissegundos entre tentativas consecutivas.
     * Cresce progressivamente para respeitar o rate limit do Cloudflare.
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

    /**
     * @param props  propriedades da Deriv: {@code accountId}, {@code accessToken} e {@code appId}
     * @param mapper utilizado para deserializar a resposta JSON e extrair a URL do WebSocket
     */
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
     * Obtém a URI WebSocket autenticada com OTP via chamada REST à API da Deriv.
     * Em caso de resposta {@code 429}, aguarda o intervalo configurado e retenta.
     * Qualquer outro código de erro HTTP não realiza retry e lança exceção imediatamente.
     * Erros de I/O ou timeout realizam retry com o mesmo backoff.
     *
     * @return URI do WebSocket com OTP embutido, pronta para conexão autenticada
     * @throws IllegalStateException se todas as {@value MAX_RETRIES} tentativas falharem,
     *                               se a resposta não contiver a URL ou se a thread for interrompida
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

                if (status == 200) {
                    return parseWsUri(response.body());
                }

                // Rate limit do Cloudflare — aguarda o intervalo configurado e retenta
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

                // Qualquer outro código HTTP é tratado como falha definitiva sem retry
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

                // Aguarda o intervalo de backoff antes da próxima tentativa
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
     * Deserializa o corpo da resposta HTTP e extrai a URI WebSocket do campo {@code data.url}.
     * O OTP é mascarado no log para não expor o token autenticado.
     *
     * @param responseBody corpo JSON da resposta HTTP com status {@code 200}
     * @return URI do WebSocket com OTP embutido
     * @throws IllegalStateException se o campo {@code data.url} estiver ausente ou em branco,
     *                               ou se o JSON não puder ser deserializado
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

            // OTP mascarado no log para não expor o token autenticado
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