package com.github.dayviddouglas.TradingBot.deriv;
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

/**
 * Low-level WebSocket client for Deriv.
 * Uses a message handler to route raw JSON messages to upper layers.
 */
public class DerivWsClient extends WebSocketClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DerivWsClient.class);

    private volatile Consumer<String> messageHandler = msg -> {};

    // lifecycle hooks
    private volatile Runnable onConnected;
    private volatile Consumer<CloseEvent> onDisconnected;

    public record CloseEvent(int code, String reason, boolean remote) {}

    // supervisor for ping + reconnect
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "deriv-ws-supervisor");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean shouldRun = new AtomicBoolean(true);
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

    // NEW: ensure ping loop starts only once (avoid multiple scheduleWithFixedDelay on reconnect)
    private final AtomicBoolean pingLoopStarted = new AtomicBoolean(false);

    // tuning (MVP)
    private Duration pingInterval = Duration.ofSeconds(25);
    private Duration reconnectMinDelay = Duration.ofSeconds(1);
    private Duration reconnectMaxDelay = Duration.ofSeconds(20);

    public DerivWsClient(URI serverUri) {
        super(serverUri);
    }

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

    @Override
    public void onOpen(ServerHandshake handshakeData) {
        reconnectAttempts.set(0);
        reconnectScheduled.set(false);

        log.info("Deriv WS opened. httpStatus={} httpMessage={}",
                handshakeData.getHttpStatus(), handshakeData.getHttpStatusMessage());

        // keep-alive ping loop
        startPingLoop();

        // IMPORTANT: do NOT block WebSocket thread with authorize/join calls
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

    @Override
    public void onMessage(String message) {
        try {
            messageHandler.accept(message);
        } catch (Exception e) {
            log.error("Message handler error. raw={}", message, e);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        log.warn("Deriv WS closed. code={} remote={} reason={}", code, remote, reason);

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

    @Override
    public void onError(Exception ex) {
        // onError often precedes onClose
        log.error("Deriv WS error", ex);
    }

    private void startPingLoop() {
        if (!pingLoopStarted.compareAndSet(false, true)) return;

        scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (!shouldRun.get()) return;
                if (!isOpen()) return;

                // Java-WebSocket ping frame
                sendPing();
            } catch (Exception e) {
                // keep silent; close/reconnect will handle if needed
                log.debug("Ping failed: {}", e.getMessage());
            }
        }, pingInterval.toSeconds(), pingInterval.toSeconds(), TimeUnit.SECONDS);
    }

    private void scheduleReconnect(int code, String reason, boolean remote) {
        // ensure only one reconnect task at a time
        if (!reconnectScheduled.compareAndSet(false, true)) return;

        int attempt = reconnectAttempts.incrementAndGet();
        long delayMs = computeBackoffMs(attempt);

        log.warn("WS reconnect scheduled | attempt={} | delayMs={} | lastCloseCode={} reason={}",
                attempt, delayMs, code, reason);

        scheduler.schedule(() -> {
            if (!shouldRun.get()) return;

            try {
                reconnectBlocking();
                // onOpen will run and reset flags
            } catch (Exception e) {
                reconnectScheduled.set(false); // allow scheduling next
                log.warn("WS reconnect failed | attempt={} | error={}", attempt, e.getMessage());
                scheduleReconnect(code, reason, remote);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private long computeBackoffMs(int attempt) {
        long min = reconnectMinDelay.toMillis();
        long max = reconnectMaxDelay.toMillis();
        long base = min * (1L << Math.min(attempt - 1, 5)); // exponential up to 2^5
        return Math.min(base, max);
    }

    @Override
    public void close() {
        try {
            shouldRun.set(false);
            scheduler.shutdownNow();
        } catch (Exception ignored) {}

        try {
            super.close();
        } catch (Exception e) {
            log.warn("Error while closing Deriv WS", e);
        }
    }
}