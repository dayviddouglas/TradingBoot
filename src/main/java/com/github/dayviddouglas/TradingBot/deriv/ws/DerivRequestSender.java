package com.github.dayviddouglas.TradingBot.deriv.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dayviddouglas.TradingBot.deriv.DerivWsClient;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Responsável por enviar requisições à API Deriv e correlacionar respostas.
 *
 * Extraído do DerivMarketDataService para respeitar SRP:
 * - DerivMarketDataService orquestra e roteia mensagens
 * - DerivRequestSender envia requests e gerencia Futures pendentes
 *
 * Responsabilidades:
 * - Gerar req_id único por requisição
 * - Registrar CompletableFuture por req_id
 * - Completar Futures quando a resposta chegar
 * - Limpar Futures automaticamente após conclusão
 *
 * Thread-safety:
 * - AtomicLong para geração de req_id
 * - ConcurrentHashMap para mapa de Futures pendentes
 */
@Component
public class DerivRequestSender {

    private final DerivWsClient wsClient;
    private final ObjectMapper mapper;
    private final AtomicLong reqIdSeq = new AtomicLong(1000);
    private final Map<Long, CompletableFuture<JsonNode>> pendingByReqId =
            new ConcurrentHashMap<>();

    public DerivRequestSender(DerivWsClient wsClient, ObjectMapper mapper) {
        this.wsClient = wsClient;
        this.mapper = mapper;
    }

    /**
     * Envia payload e registra Future para correlação com a resposta.
     *
     * O req_id é inserido automaticamente no payload antes do envio.
     *
     * @param payload ObjectNode com os campos da requisição
     * @return Future completado quando a resposta chegar
     */
    public CompletableFuture<JsonNode> send(ObjectNode payload) {
        long reqId = nextReqId();
        payload.put("req_id", reqId);

        CompletableFuture<JsonNode> future = registerFuture(reqId);
        wsClient.send(payload.toString());

        return future;
    }

    /**
     * Envia payload sem aguardar resposta (fire-and-forget).
     *
     * Usado para subscribes onde a resposta chega via callback
     * registrado no DerivMarketDataService.
     *
     * @param payload ObjectNode com os campos da requisição
     * @return req_id gerado para rastreamento externo
     */
    public long sendFireAndForget(ObjectNode payload) {
        long reqId = nextReqId();
        payload.put("req_id", reqId);
        wsClient.send(payload.toString());
        return reqId;
    }

    /**
     * Completa o Future pendente correspondente ao req_id da resposta.
     *
     * @param msg mensagem de resposta contendo o req_id
     */
    public void complete(JsonNode msg) {
        long reqId = msg.path("req_id").asLong(-1);
        if (reqId <= 0) return;

        CompletableFuture<JsonNode> future = pendingByReqId.get(reqId);
        if (future != null) {
            future.complete(msg);
        }
    }

    /**
     * Completa o Future pendente com exceção.
     * Usado quando a API retorna erro para o req_id.
     *
     * @param reqId     ID da requisição que falhou
     * @param exception exceção a ser propagada ao chamador
     */
    public void completeExceptionally(long reqId, Exception exception) {
        CompletableFuture<JsonNode> future = pendingByReqId.remove(reqId);
        if (future != null) {
            future.completeExceptionally(exception);
        }
    }

    /**
     * Verifica se existe Future pendente para o req_id.
     *
     * @param reqId ID da requisição
     * @return true se existe Future aguardando resposta
     */
    public boolean hasPending(long reqId) {
        return pendingByReqId.containsKey(reqId);
    }

    /**
     * Cria ObjectNode vazio para construção de payloads.
     *
     * @return ObjectNode vazio gerenciado pelo mapper interno
     */
    public ObjectNode newPayload() {
        return mapper.createObjectNode();
    }

    /**
     * Gera um req_id único sem enviar nenhuma requisição à API.
     * Usado pelo DerivHistoryPaginator para gerar o parentReqId
     * de paginação sem disparar request à API.
     *
     * @return próximo req_id disponível
     */
    public long generateReqId() {
        return nextReqId();
    }

    // ═══════════════════════════════════════════════════════════════
    // Internos
    // ═══════════════════════════════════════════════════════════════

    private long nextReqId() {
        return reqIdSeq.getAndIncrement();
    }

    private CompletableFuture<JsonNode> registerFuture(long reqId) {
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingByReqId.put(reqId, future);
        future.whenComplete((ok, err) -> pendingByReqId.remove(reqId));
        return future;
    }
}