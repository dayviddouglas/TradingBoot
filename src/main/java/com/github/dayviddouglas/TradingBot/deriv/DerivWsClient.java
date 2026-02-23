package com.github.dayviddouglas.TradingBot.deriv;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.function.Consumer;

/**
 * Low-level WebSocket client for Deriv.
 * Uses a message handler to route raw JSON messages to upper layers.
 */
public class DerivWsClient extends WebSocketClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DerivWsClient.class);

    private volatile Consumer<String> messageHandler = msg -> {};

    public DerivWsClient(URI serverUri) {
        super(serverUri);
    }

    public void setMessageHandler(Consumer<String> handler) {
        this.messageHandler = (handler == null) ? (msg -> {}) : handler;
    }

    @Override
    public void onOpen(ServerHandshake handshakeData) {
        log.info("Deriv WS opened. httpStatus={} httpMessage={}",
                handshakeData.getHttpStatus(), handshakeData.getHttpStatusMessage());
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
    }

    @Override
    public void onError(Exception ex) {
        log.error("Deriv WS error", ex);
    }

    @Override
    public void close() {
        try {
            closeBlocking();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while closing Deriv WS", e);
        } catch (Exception e) {
            log.warn("Error while closing Deriv WS", e);
        }
    }
}
