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
 * Responsável por enviar requisições à API Deriv via WebSocket e correlacionar
 * as respostas assíncronas com os {@link CompletableFuture} registrados pelos chamadores.
 *
 * Cada requisição recebe um {@code req_id} único gerado por {@link AtomicLong},
 * inserido automaticamente no payload antes do envio. Quando a resposta chega,
 * o {@link com.github.dayviddouglas.TradingBot.deriv.DerivMarketDataService} invoca
 * {@link #complete} com a mensagem, que localiza e completa o Future correspondente.
 * O Future é removido do mapa automaticamente ao ser completado, seja com sucesso
 * ou com exceção.
 *
 * Dois modos de envio são suportados:
 * <ul>
 *   <li>{@link #send}: registra um Future e aguarda a resposta da API</li>
 *   <li>{@link #sendFireAndForget}: envia sem registrar Future; a resposta
 *       é tratada via callback registrado externamente</li>
 * </ul>
 */
@Component
public class DerivRequestSender {

    private final DerivWsClient wsClient;
    private final ObjectMapper mapper;

    /** Gerador de IDs únicos sequenciais para correlação de requisições e respostas. */
    private final AtomicLong reqIdSeq = new AtomicLong(1000);

    /**
     * Futures pendentes indexados pelo {@code req_id}.
     * Cada entrada é removida automaticamente ao ser completada via {@code whenComplete}.
     */
    private final Map<Long, CompletableFuture<JsonNode>> pendingByReqId =
            new ConcurrentHashMap<>();

    /**
     * @param wsClient cliente WebSocket utilizado para enviar os payloads serializados
     * @param mapper   utilizado para criar {@link ObjectNode} vazios via {@link #newPayload}
     */
    public DerivRequestSender(DerivWsClient wsClient, ObjectMapper mapper) {
        this.wsClient = wsClient;
        this.mapper = mapper;
    }

    /**
     * Envia o payload ao WebSocket e registra um {@link CompletableFuture} para
     * receber a resposta quando ela chegar. O {@code req_id} é inserido automaticamente
     * no payload antes do envio.
     *
     * @param payload {@link ObjectNode} com os campos da requisição
     * @return Future completado com a resposta da API quando disponível
     */
    public CompletableFuture<JsonNode> send(ObjectNode payload) {
        long reqId = nextReqId();
        payload.put("req_id", reqId);

        CompletableFuture<JsonNode> future = registerFuture(reqId);
        wsClient.send(payload.toString());

        return future;
    }

    /**
     * Envia o payload ao WebSocket sem registrar Future para a resposta.
     * Utilizado em requisições cujas respostas chegam via callbacks registrados
     * externamente, como subscrições de ticks e histórico paginado.
     * O {@code req_id} gerado é retornado para rastreamento externo pelo chamador.
     *
     * @param payload {@link ObjectNode} com os campos da requisição
     * @return {@code req_id} gerado e inserido no payload
     */
    public long sendFireAndForget(ObjectNode payload) {
        long reqId = nextReqId();
        payload.put("req_id", reqId);
        wsClient.send(payload.toString());
        return reqId;
    }

    /**
     * Localiza e completa o Future pendente correspondente ao {@code req_id}
     * extraído da mensagem de resposta recebida pelo WebSocket.
     * Mensagens sem {@code req_id} válido são ignoradas silenciosamente.
     *
     * @param msg mensagem de resposta recebida via WebSocket contendo o campo {@code req_id}
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
     * Completa o Future pendente com a exceção informada, propagando o erro
     * ao chamador que aguarda a resposta. Remove o Future do mapa após a conclusão.
     * Utilizado quando a API retorna um erro para o {@code req_id} correspondente.
     *
     * @param reqId     ID da requisição que falhou
     * @param exception exceção a ser propagada ao chamador via Future
     */
    public void completeExceptionally(long reqId, Exception exception) {
        CompletableFuture<JsonNode> future = pendingByReqId.remove(reqId);
        if (future != null) {
            future.completeExceptionally(exception);
        }
    }

    /**
     * Verifica se existe Future pendente aguardando resposta para o {@code req_id} informado.
     *
     * @param reqId ID da requisição a ser verificado
     * @return {@code true} se existir Future registrado para o ID
     */
    public boolean hasPending(long reqId) {
        return pendingByReqId.containsKey(reqId);
    }

    /**
     * Cria um {@link ObjectNode} vazio para construção de payloads de requisição.
     *
     * @return {@link ObjectNode} vazio gerenciado pelo {@link ObjectMapper} interno
     */
    public ObjectNode newPayload() {
        return mapper.createObjectNode();
    }

    /**
     * Gera um {@code req_id} único sem enviar nenhuma requisição à API.
     * Utilizado pelo {@link DerivHistoryPaginator} para gerar o {@code parentReqId}
     * de paginação antes de iniciar as páginas individuais.
     *
     * @return próximo {@code req_id} disponível na sequência
     */
    public long generateReqId() {
        return nextReqId();
    }

    // ═══════════════════════════════════════════════════════════════
    // Internos
    // ═══════════════════════════════════════════════════════════════

    /**
     * Incrementa e retorna o próximo {@code req_id} da sequência atômica.
     *
     * @return próximo ID único disponível
     */
    private long nextReqId() {
        return reqIdSeq.getAndIncrement();
    }

    /**
     * Cria e registra um {@link CompletableFuture} para o {@code req_id} informado.
     * Configura remoção automática do mapa ao ser completado, com ou sem exceção,
     * evitando acúmulo de entradas para Futures já resolvidos.
     *
     * @param reqId ID da requisição associado ao Future
     * @return Future registrado e pronto para ser completado pela resposta da API
     */
    private CompletableFuture<JsonNode> registerFuture(long reqId) {
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingByReqId.put(reqId, future);
        future.whenComplete((ok, err) -> pendingByReqId.remove(reqId));
        return future;
    }
}