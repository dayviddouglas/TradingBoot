package com.github.dayviddouglas.TradingBot.deriv;

import com.github.dayviddouglas.TradingBot.deriv.ws.TickHeartbeat;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Cliente WebSocket de baixo nível para comunicação com a API da Deriv.
 *
 * Correção v5.3:
 * - URI agora é dinâmica via Supplier<URI> — obtida via OTP antes de
 *   cada conexão para o runtime do bot.
 * - O OTP só é buscado quando connect() ou connectBlocking() é chamado,
 *   não no construtor. Isso evita chamadas REST durante o startup do
 *   Spring (que causavam HTTP 429 Rate Limit).
 * - Construtor retrocompatível para ferramentas standalone mantido.
 */
public class DerivWsClient implements AutoCloseable {

    private static final Logger log =
            LoggerFactory.getLogger(DerivWsClient.class);

    /**
     * Supplier de URI chamado antes de cada conexão.
     * Para o runtime: chama DerivOtpService.fetchWsUri()
     * Para ferramentas standalone: retorna URI fixa
     */
    private final Supplier<URI> uriSupplier;
    private final TickHeartbeat tickHeartbeat;

    /**
     * Volatile porque pode ser substituída por uma nova instância
     * durante reconexão em uma thread diferente.
     * Null antes da primeira chamada a connect().
     */
    private volatile WebSocketClient internalClient;

    private volatile Consumer<String>      messageHandler = msg -> {};
    private volatile Runnable              onConnected;
    private volatile Consumer<CloseEvent>  onDisconnected;

    public record CloseEvent(int code, String reason, boolean remote) {}

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "deriv-ws-supervisor");
                t.setDaemon(true);
                return t;
            });

    private final AtomicBoolean shouldRun          = new AtomicBoolean(true);
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts  = new AtomicInteger(0);
    private final AtomicBoolean pingLoopStarted    = new AtomicBoolean(false);

    private Duration pingInterval      = Duration.ofSeconds(25);
    private Duration reconnectMinDelay = Duration.ofSeconds(1);
    private Duration reconnectMaxDelay = Duration.ofSeconds(20);

    // ═══════════════════════════════════════════════════════════════
    // Construtores
    // ═══════════════════════════════════════════════════════════════

    /**
     * Construtor principal para runtime com OTP dinâmico.
     *
     * O uriSupplier é chamado apenas quando connect() ou
     * connectBlocking() for invocado — nunca no construtor.
     * Isso evita chamadas REST durante o startup do Spring.
     *
     * @param uriSupplier   fornece a URI do WebSocket antes de cada conexão
     * @param tickHeartbeat monitor de chegada de ticks
     */
    public DerivWsClient(Supplier<URI> uriSupplier, TickHeartbeat tickHeartbeat) {
        this.uriSupplier   = uriSupplier;
        this.tickHeartbeat = tickHeartbeat;
        // internalClient NÃO é criado aqui — criado apenas em connect()
    }

    /**
     * Construtor retrocompatível para ferramentas standalone.
     *
     * Aceita URI fixa — usado por:
     * - ListOfFinancialAssets
     * - DerivHistoryDownloadTool
     * - TradeHistoryDownloadTool
     *
     * @param serverUri URI fixa do WebSocket
     */
    public DerivWsClient(URI serverUri) {
        this(() -> serverUri, new TickHeartbeat());
    }

    // ═══════════════════════════════════════════════════════════════
    // Criação do cliente interno
    // ═══════════════════════════════════════════════════════════════

    /**
     * Cria novo WebSocketClient chamando uriSupplier.get().
     *
     * Para o runtime: chama DerivOtpService.fetchWsUri() → REST call.
     * Para standalone: retorna URI fixa imediatamente.
     *
     * Chamado apenas em connect(), connectBlocking() e scheduleReconnect().
     */
    private WebSocketClient createNewClient() {
        URI uri = uriSupplier.get();
        log.info("WS CLIENT CREATING | uri={}",
                uri.toString().replaceAll("otp=[^&]+", "otp=***"));

        return new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake handshakeData) {
                DerivWsClient.this.handleOnOpen(handshakeData);
            }

            @Override
            public void onMessage(String message) {
                DerivWsClient.this.handleOnMessage(message);
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                DerivWsClient.this.handleOnClose(code, reason, remote);
            }

            @Override
            public void onError(Exception ex) {
                DerivWsClient.this.handleOnError(ex);
            }
        };
    }

    // ═══════════════════════════════════════════════════════════════
    // Configuração de callbacks e parâmetros
    // ═══════════════════════════════════════════════════════════════

    public void setMessageHandler(Consumer<String> handler) {
        this.messageHandler = (handler == null) ? (msg -> {}) : handler;
    }

    public void setOnConnected(Runnable cb) {
        this.onConnected = cb;
    }

    public void setOnDisconnected(Consumer<CloseEvent> cb) {
        this.onDisconnected = cb;
    }

    public void setPingInterval(Duration pingInterval) {
        if (pingInterval != null) this.pingInterval = pingInterval;
    }

    public void setReconnectBackoff(Duration minDelay, Duration maxDelay) {
        if (minDelay != null) this.reconnectMinDelay = minDelay;
        if (maxDelay != null) this.reconnectMaxDelay = maxDelay;
    }

    // ═══════════════════════════════════════════════════════════════
    // API pública de conexão
    // ═══════════════════════════════════════════════════════════════

    /**
     * Cria o cliente e conecta de forma assíncrona.
     * O OTP é obtido aqui — nunca no construtor.
     */
    public void connect() {
        internalClient = createNewClient();
        internalClient.connect();
    }

    /**
     * Cria o cliente e conecta de forma bloqueante.
     * O OTP é obtido aqui — nunca no construtor.
     */
    public void connectBlocking() throws InterruptedException {
        internalClient = createNewClient();
        internalClient.connectBlocking();
    }

    /**
     * Cria o cliente e conecta de forma bloqueante com timeout.
     * O OTP é obtido aqui — nunca no construtor.
     */
    public void connectBlocking(long timeout, TimeUnit unit)
            throws InterruptedException {
        internalClient = createNewClient();
        internalClient.connectBlocking(timeout, unit);
    }

    public void send(String text) {
        if (internalClient == null) {
            throw new IllegalStateException(
                    "WebSocket not connected. Call connect() first.");
        }
        internalClient.send(text);
    }

    public boolean isOpen() {
        return internalClient != null && internalClient.isOpen();
    }

    // ═══════════════════════════════════════════════════════════════
    // Handlers internos
    // ═══════════════════════════════════════════════════════════════

    private void handleOnOpen(ServerHandshake handshakeData) {
        reconnectAttempts.set(0);
        reconnectScheduled.set(false);

        log.info("Deriv WS opened | httpStatus={}",
                handshakeData.getHttpStatus());

        startPingLoop();
        tickHeartbeat.startMonitoring();

        Runnable cb = onConnected;
        if (cb != null) {
            Thread.startVirtualThread(() -> {
                try {
                    cb.run();
                } catch (Exception e) {
                    log.warn("onConnected callback error", e);
                }
            });
        }
    }

    private void handleOnMessage(String message) {
        try {
            messageHandler.accept(message);
        } catch (Exception e) {
            log.error("Message handler error | raw={}", message, e);
        }
    }

    private void handleOnClose(int code, String reason, boolean remote) {
        log.warn("Deriv WS closed | code={} | remote={} | reason={}",
                code, remote, reason);

        tickHeartbeat.stopMonitoring();

        Consumer<CloseEvent> cb = onDisconnected;
        if (cb != null) {
            try {
                cb.accept(new CloseEvent(code, reason, remote));
            } catch (Exception e) {
                log.warn("onDisconnected callback error", e);
            }
        }

        if (shouldRun.get()) {
            scheduleReconnect(code, reason, remote);
        }
    }

    private void handleOnError(Exception ex) {
        log.error("Deriv WS error", ex);
    }

    // ═══════════════════════════════════════════════════════════════
    // Ping Loop (keep-alive)
    // ═══════════════════════════════════════════════════════════════

    private void startPingLoop() {
        if (!pingLoopStarted.compareAndSet(false, true)) return;

        scheduler.scheduleWithFixedDelay(() -> {
                    try {
                        if (!shouldRun.get()) return;
                        if (!isOpen()) return;
                        internalClient.sendPing();
                    } catch (Exception e) {
                        log.debug("Ping failed: {}", e.getMessage());
                    }
                }, pingInterval.toSeconds(), pingInterval.toSeconds(),
                TimeUnit.SECONDS);
    }

    // ═══════════════════════════════════════════════════════════════
    // Reconexão automática com backoff exponencial
    // ═══════════════════════════════════════════════════════════════

    /**
     * Agenda reconexão com backoff exponencial.
     *
     * createNewClient() chama uriSupplier.get() internamente,
     * garantindo que um novo OTP seja obtido antes de cada
     * tentativa de reconexão.
     */
    private void scheduleReconnect(int code, String reason, boolean remote) {
        if (!reconnectScheduled.compareAndSet(false, true)) return;

        int  attempt = reconnectAttempts.incrementAndGet();
        long delayMs = computeBackoffMs(attempt);

        if (attempt == 1) {
            log.warn("WS disconnected | code={} | reconnecting...", code);
        } else {
            log.warn("WS reconnect | attempt={} | waiting={}ms",
                    attempt, delayMs);
        }

        scheduler.schedule(() -> {
            if (!shouldRun.get()) return;

            try {
                log.info("WS reconnecting... | attempt={}", attempt);

                tickHeartbeat.stopMonitoring();

                try {
                    if (internalClient != null && internalClient.isOpen()) {
                        internalClient.close();
                    }
                } catch (Exception ignored) {}

                tickHeartbeat.reset();

                // createNewClient() → uriSupplier.get() → OTP fresco
                internalClient = createNewClient();
                boolean connected = internalClient
                        .connectBlocking(10, TimeUnit.SECONDS);

                if (!connected || !internalClient.isOpen()) {
                    throw new IllegalStateException(
                            "Socket not open after connectBlocking");
                }

                log.info("WS reconnection successful | attempt={}", attempt);

            } catch (Exception e) {
                reconnectScheduled.set(false);
                long nextDelay = computeBackoffMs(attempt + 1);
                log.warn("WS reconnect failed | attempt={} | reason={} "
                                + "| retrying in {}ms...",
                        attempt, e.getMessage(), nextDelay);
                scheduleReconnect(code, reason, remote);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private long computeBackoffMs(int attempt) {
        long min  = reconnectMinDelay.toMillis();
        long max  = reconnectMaxDelay.toMillis();
        long base = min * (1L << Math.min(attempt - 1, 5));
        return Math.min(base, max);
    }

    // ═══════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void close() {
        try {
            shouldRun.set(false);
            tickHeartbeat.stopMonitoring();
            scheduler.shutdownNow();
        } catch (Exception ignored) {}

        try {
            if (internalClient != null) {
                internalClient.close();
            }
        } catch (Exception e) {
            log.warn("Error while closing Deriv WS", e);
        }
    }
}