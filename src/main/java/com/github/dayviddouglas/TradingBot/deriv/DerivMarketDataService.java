package com.github.dayviddouglas.TradingBot.deriv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dayviddouglas.TradingBot.deriv.ws.DerivHistoryPaginator;
import com.github.dayviddouglas.TradingBot.deriv.ws.DerivRequestSender;
import com.github.dayviddouglas.TradingBot.deriv.ws.TickHandler;
import com.github.dayviddouglas.TradingBot.deriv.ws.TickHeartbeat;
import com.github.dayviddouglas.TradingBot.exceptions.DerivErrorException;
import com.github.dayviddouglas.TradingBot.model.Bar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Camada de abstração da API Deriv sobre WebSocket.
 *
 * Correção v5.3:
 * O fluxo de autorização foi removido desta classe. Com o novo modelo
 * OTP da Deriv, a autenticação acontece na URL do WebSocket antes da
 * conexão ser estabelecida. Não é mais necessário enviar mensagem
 * de authorize após conectar.
 *
 * Responsabilidades mantidas:
 * - Registrar handler de mensagens no DerivWsClient
 * - Rotear mensagens por msg_type
 * - Despachar callbacks de ticks e contratos abertos
 * - Notificar TickHeartbeat a cada tick recebido
 * - Parsear candles recebidos em objetos Bar
 *
 * Responsabilidades removidas:
 * - authorize() — autenticação agora é via OTP na URL
 * - authorizeAndWaitFailFast() — não mais necessário
 * - ensureAuthorized() — não mais necessário
 * - Estado de autorização (authorized, lastAuthorizedToken) — removidos
 */
public class DerivMarketDataService {

    private static final Logger log =
            LoggerFactory.getLogger(DerivMarketDataService.class);

    private final DerivWsClient wsClient;
    private final DerivRequestSender requestSender;
    private final DerivHistoryPaginator historyPaginator;
    private final TickHeartbeat tickHeartbeat;
    private final ObjectMapper mapper;

    private volatile TickHandler onTick;
    private volatile BiConsumer<Long, List<Bar>> onCandleHistory;
    private volatile Consumer<JsonNode> onOpenContract;

    public DerivMarketDataService(
            DerivWsClient wsClient,
            DerivRequestSender requestSender,
            DerivHistoryPaginator historyPaginator,
            TickHeartbeat tickHeartbeat,
            ObjectMapper mapper
    ) {
        this.wsClient          = wsClient;
        this.requestSender     = requestSender;
        this.historyPaginator  = historyPaginator;
        this.tickHeartbeat     = tickHeartbeat;
        this.mapper            = mapper;

        this.wsClient.setMessageHandler(this::routeMessage);
        this.wsClient.setOnDisconnected(evt ->
                log.warn("WS DISCONNECTED | code={} | reason={}",
                        evt.code(), evt.reason()));
    }

    // ═══════════════════════════════════════════════════════════════
    // Registro de callbacks
    // ═══════════════════════════════════════════════════════════════

    public void onTick(TickHandler handler) {
        this.onTick = handler;
    }

    public void onCandleHistory(BiConsumer<Long, List<Bar>> handler) {
        this.onCandleHistory = handler;
    }

    public void onOpenContract(Consumer<JsonNode> handler) {
        this.onOpenContract = handler;
    }

    // ═══════════════════════════════════════════════════════════════
    // Mercado
    // ═══════════════════════════════════════════════════════════════

    public long subscribeTicks(String symbol) {
        ObjectNode payload = requestSender.newPayload();
        payload.put("ticks", symbol);
        payload.put("subscribe", 1);

        long reqId = requestSender.sendFireAndForget(payload);
        log.info("TICKS SUBSCRIBED | symbol={} | req_id={}",
                symbol, reqId);
        return reqId;
    }

    public long fetchCandleHistory(
            String symbol,
            int granularitySeconds,
            int count
    ) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be > 0");
        }

        return historyPaginator.request(
                symbol,
                granularitySeconds,
                count,
                this::dispatchCandleHistory
        );
    }

    // ═══════════════════════════════════════════════════════════════
    // Trade
    // ═══════════════════════════════════════════════════════════════

    public CompletableFuture<JsonNode> requestProposal(
            String symbol,
            String contractType,
            double amount,
            String currency,
            int duration,
            String durationUnit
    ) {
        ObjectNode payload = requestSender.newPayload();
        payload.put("proposal",         1);
        payload.put("underlying_symbol", symbol);
        payload.put("contract_type",    contractType);
        payload.put("amount",           amount);
        payload.put("basis",            "stake");
        payload.put("currency",         currency);
        payload.put("duration",         duration);
        payload.put("duration_unit",    durationUnit);
        return requestSender.send(payload);
    }

    public CompletableFuture<JsonNode> buy(
            String proposalId,
            double price
    ) {
        ObjectNode payload = requestSender.newPayload();
        payload.put("buy",   proposalId);
        payload.put("price", price);
        return requestSender.send(payload);
    }

    public CompletableFuture<JsonNode> subscribeOpenContract(
            long contractId
    ) {
        ObjectNode payload = requestSender.newPayload();
        payload.put("proposal_open_contract", 1);
        payload.put("contract_id",            contractId);
        payload.put("subscribe",              1);
        return requestSender.send(payload);
    }

    public CompletableFuture<JsonNode> getOpenContractOnce(
            long contractId
    ) {
        ObjectNode payload = requestSender.newPayload();
        payload.put("proposal_open_contract", 1);
        payload.put("contract_id",            contractId);
        return requestSender.send(payload);
    }

    public CompletableFuture<JsonNode> forget(String subscriptionId) {
        ObjectNode payload = requestSender.newPayload();
        payload.put("forget", subscriptionId);
        return requestSender.send(payload);
    }

    // ═══════════════════════════════════════════════════════════════
    // Roteamento de mensagens
    // ═══════════════════════════════════════════════════════════════

    private void routeMessage(String raw) {
        try {
            JsonNode msg = mapper.readTree(raw);

            if (msg.has("error")) {
                handleError(msg);
                return;
            }

            String msgType = msg.path("msg_type").asText("");

            switch (msgType) {
                case "tick"                   -> handleTick(msg);
                case "history", "candles"     -> handleHistory(msg);
                case "proposal_open_contract" -> handleOpenContract(msg);
                case "proposal", "buy",
                     "forget"                 -> requestSender.complete(msg);
                default -> { /* ignorado silenciosamente */ }
            }

        } catch (Exception e) {
            log.warn("MESSAGE ROUTING ERROR | raw={}", raw, e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Handlers por tipo de mensagem
    // ═══════════════════════════════════════════════════════════════

    private void handleError(JsonNode msg) {
        String errMessage = msg.path("error").path("message")
                .asText("Deriv error");
        String errCode    = msg.path("error").path("code").asText("");
        long   reqId      = msg.path("req_id").asLong(-1);

        log.warn("DERIV API ERROR | req_id={} | code={} | message={}",
                reqId, errCode, errMessage);

        if (historyPaginator.isPaginatedPage(reqId)) {
            historyPaginator.handlePageError(reqId);
            return;
        }

        if (reqId > 0) {
            requestSender.completeExceptionally(
                    reqId, new DerivErrorException(errMessage));
        }
    }

    /**
     * Processa tick recebido e notifica o TickHeartbeat.
     *
     * tickHeartbeat.recordTick() é obrigatório para que o monitor
     * de saúde da conexão funcione corretamente.
     */
    private void handleTick(JsonNode msg) {
        JsonNode tick = msg.get("tick");
        if (tick == null || !tick.isObject()) return;

        String symbol = tick.path("symbol").asText("");
        long   epoch  = tick.path("epoch").asLong(-1);
        double quote  = tick.path("quote").asDouble(Double.NaN);

        if (symbol.isBlank() || epoch <= 0
                || !Double.isFinite(quote)) return;

        tickHeartbeat.recordTick();

        TickHandler handler = onTick;
        if (handler == null) return;

        handler.onTick(symbol, epoch, quote);
    }

    private void handleHistory(JsonNode msg) {
        JsonNode candles = msg.get("candles");
        if (candles == null || !candles.isArray()) return;

        List<Bar> bars  = parseBars(candles);
        long      reqId = msg.path("req_id").asLong(-1);

        historyPaginator.handlePage(reqId, bars);
    }

    private void handleOpenContract(JsonNode msg) {
        requestSender.complete(msg);

        Consumer<JsonNode> handler = onOpenContract;
        if (handler != null) {
            handler.accept(msg);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Parsing de candles
    // ═══════════════════════════════════════════════════════════════

    private List<Bar> parseBars(JsonNode candles) {
        List<Bar> bars = new ArrayList<>(candles.size());

        for (JsonNode node : candles) {
            Bar bar = parseBar(node);
            if (bar != null) bars.add(bar);
        }

        bars.sort(Comparator.comparing(Bar::timestamp));
        return bars;
    }

    private Bar parseBar(JsonNode node) {
        long   epoch  = node.path("epoch").asLong(-1);
        if (epoch <= 0) return null;

        double open   = node.path("open").asDouble(Double.NaN);
        double high   = node.path("high").asDouble(Double.NaN);
        double low    = node.path("low").asDouble(Double.NaN);
        double close  = node.path("close").asDouble(Double.NaN);
        double volume = node.path("volume").asDouble(0.0);

        if (!Double.isFinite(open)  || !Double.isFinite(high)
                || !Double.isFinite(low)   || !Double.isFinite(close)) {
            return null;
        }

        return new Bar(
                Instant.ofEpochSecond(epoch),
                open, high, low, close, volume
        );
    }

    // ═══════════════════════════════════════════════════════════════
    // Utilitários
    // ═══════════════════════════════════════════════════════════════

    private void dispatchCandleHistory(long reqId, List<Bar> bars) {
        BiConsumer<Long, List<Bar>> handler = onCandleHistory;
        if (handler != null) {
            handler.accept(reqId, bars);
        }
    }
}