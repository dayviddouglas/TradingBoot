package com.github.dayviddouglas.TradingBoot.deriv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dayviddouglas.TradingBoot.deriv.ws.DerivHistoryPaginator;
import com.github.dayviddouglas.TradingBoot.deriv.ws.DerivRequestSender;
import com.github.dayviddouglas.TradingBoot.deriv.ws.TickHandler;
import com.github.dayviddouglas.TradingBoot.deriv.ws.TickHeartbeat;
import com.github.dayviddouglas.TradingBoot.exceptions.DerivErrorException;
import com.github.dayviddouglas.TradingBoot.model.Bar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Camada de abstração da API Deriv sobre WebSocket.
 *
 * Concentra as seguintes responsabilidades:
 * <ul>
 *   <li>Registrar o handler de mensagens no {@link DerivWsClient} e rotear cada mensagem
 *       pelo campo {@code msg_type}</li>
 *   <li>Registrar e invocar callbacks externos de ticks, histórico de candles
 *       e atualizações de contratos abertos</li>
 *   <li>Notificar o {@link TickHeartbeat} a cada tick recebido, mantendo o monitor
 *       de saúde da conexão atualizado</li>
 *   <li>Parsear candles recebidos em objetos {@link Bar} ordenados cronologicamente</li>
 *   <li>Delegar paginação de histórico ao {@link DerivHistoryPaginator}</li>
 *   <li>Encaminhar erros da API ao {@link DerivRequestSender} ou ao {@link DerivHistoryPaginator}
 *       conforme o {@code req_id} da mensagem</li>
 * </ul>
 *
 * A autenticação ocorre na URL do WebSocket via OTP antes da conexão ser estabelecida.
 * Não há envio de mensagem de autorização após a conexão — o canal já está autenticado
 * ao ser aberto pelo {@link DerivWsClient}.
 */
public class DerivMarketDataService {

    private static final Logger log =
            LoggerFactory.getLogger(DerivMarketDataService.class);

    private final DerivWsClient wsClient;
    private final DerivRequestSender requestSender;
    private final DerivHistoryPaginator historyPaginator;
    private final TickHeartbeat tickHeartbeat;
    private final ObjectMapper mapper;

    /** Callback invocado pelo roteador a cada tick recebido da API. */
    private volatile TickHandler onTick;

    /** Callback invocado pelo {@link DerivHistoryPaginator} ao concluir o download de histórico. */
    private volatile BiConsumer<Long, List<Bar>> onCandleHistory;

    /** Callback invocado a cada atualização de contrato aberto recebida via stream. */
    private volatile Consumer<JsonNode> onOpenContract;

    /**
     * Inicializa o serviço registrando o roteador de mensagens e o handler de desconexão
     * no {@link DerivWsClient}.
     *
     * @param wsClient           cliente WebSocket utilizado para envio e recebimento de mensagens
     * @param requestSender      gerencia o envio de requisições e a correlação com Futures pendentes
     * @param historyPaginator   gerencia o ciclo de paginação para downloads de histórico extensos
     * @param tickHeartbeat      monitor de saúde da conexão, notificado a cada tick recebido
     * @param mapper             utilizado para deserializar as mensagens JSON recebidas
     */
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

    /**
     * Registra o handler invocado a cada tick recebido da API.
     * Implementado pelo {@code MultiSymbolDerivBotRunner} para rotear ticks
     * aos {@code TickCandleAggregator} correspondentes.
     *
     * @param handler implementação de {@link TickHandler} ou lambda equivalente
     */
    public void onTick(TickHandler handler) {
        this.onTick = handler;
    }

    /**
     * Registra o handler invocado ao concluir o download de um histórico de candles.
     * Recebe o ID pai da requisição e a lista completa de {@link Bar} acumulados.
     *
     * @param handler consumer com o ID pai e a lista de candles
     */
    public void onCandleHistory(BiConsumer<Long, List<Bar>> handler) {
        this.onCandleHistory = handler;
    }

    /**
     * Registra o handler invocado a cada atualização de contrato aberto
     * recebida via stream {@code proposal_open_contract}.
     *
     * @param handler consumer com a mensagem JSON completa da atualização
     */
    public void onOpenContract(Consumer<JsonNode> handler) {
        this.onOpenContract = handler;
    }

    // ═══════════════════════════════════════════════════════════════
    // Mercado
    // ═══════════════════════════════════════════════════════════════

    /**
     * Envia o request de subscrição de ticks em tempo real para o símbolo informado.
     * As atualizações chegam via handler registrado em {@link #onTick}.
     *
     * @param symbol símbolo do ativo a ser subscrito
     * @return {@code req_id} da requisição de subscrição
     */
    public long subscribeTicks(String symbol) {
        ObjectNode payload = requestSender.newPayload();
        payload.put("ticks", symbol);
        payload.put("subscribe", 1);

        long reqId = requestSender.sendFireAndForget(payload);
        log.info("TICKS SUBSCRIBED | symbol={} | req_id={}",
                symbol, reqId);
        return reqId;
    }

    /**
     * Inicia o download de histórico de candles com paginação automática.
     * Quando {@code count} exceder o limite de 1000 candles por requisição,
     * o {@link DerivHistoryPaginator} divide em páginas e acumula os resultados.
     * O histórico completo é entregue ao handler registrado em {@link #onCandleHistory}.
     *
     * @param symbol             símbolo do ativo
     * @param granularitySeconds granularidade dos candles em segundos
     * @param count              quantidade total de candles desejada; deve ser maior que zero
     * @return ID pai da requisição de paginação
     * @throws IllegalArgumentException se {@code count} for menor ou igual a zero
     */
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

    /**
     * Envia o request de proposal para obter o preço e o payout estimado de um contrato.
     * O Future é completado quando a API retornar a resposta do proposal.
     *
     * @param symbol       símbolo do ativo subjacente
     * @param contractType tipo de contrato: {@code CALL} ou {@code PUT}
     * @param amount       valor do stake
     * @param currency     moeda do stake
     * @param duration     duração numérica do contrato
     * @param durationUnit unidade de duração: {@code s}, {@code m}, {@code h} ou {@code d}
     * @return Future completado com a resposta JSON do proposal
     */
    public CompletableFuture<JsonNode> requestProposal(
            String symbol,
            String contractType,
            double amount,
            String currency,
            int duration,
            String durationUnit
    ) {
        ObjectNode payload = requestSender.newPayload();
        payload.put("proposal",          1);
        payload.put("underlying_symbol", symbol);
        payload.put("contract_type",     contractType);
        payload.put("amount",            amount);
        payload.put("basis",             "stake");
        payload.put("currency",          currency);
        payload.put("duration",          duration);
        payload.put("duration_unit",     durationUnit);
        return requestSender.send(payload);
    }

    /**
     * Envia o request de compra do contrato usando o ID do proposal aprovado.
     *
     * @param proposalId ID do proposal retornado pela API na etapa anterior
     * @param price      preço máximo aceito para a compra, equivalente ao stake
     * @return Future completado com a resposta JSON do buy, contendo {@code contract_id}
     */
    public CompletableFuture<JsonNode> buy(
            String proposalId,
            double price
    ) {
        ObjectNode payload = requestSender.newPayload();
        payload.put("buy",   proposalId);
        payload.put("price", price);
        return requestSender.send(payload);
    }

    /**
     * Subscreve o stream de atualizações de um contrato aberto.
     * As atualizações chegam via handler registrado em {@link #onOpenContract}.
     *
     * @param contractId ID do contrato a ser monitorado
     * @return Future completado com o acknowledge da subscrição
     */
    public CompletableFuture<JsonNode> subscribeOpenContract(
            long contractId
    ) {
        ObjectNode payload = requestSender.newPayload();
        payload.put("proposal_open_contract", 1);
        payload.put("contract_id",            contractId);
        payload.put("subscribe",              1);
        return requestSender.send(payload);
    }

    /**
     * Consulta o estado atual de um contrato aberto sem subscrever o stream.
     * Utilizado pelo watchdog do {@link com.github.dayviddouglas.TradingBoot.deriv.trade.monitor.TradeMonitor}
     * para polls periódicos quando o stream não entrega o fechamento.
     *
     * @param contractId ID do contrato a ser consultado
     * @return Future completado com a resposta JSON do estado atual do contrato
     */
    public CompletableFuture<JsonNode> getOpenContractOnce(
            long contractId
    ) {
        ObjectNode payload = requestSender.newPayload();
        payload.put("proposal_open_contract", 1);
        payload.put("contract_id",            contractId);
        return requestSender.send(payload);
    }

    /**
     * Cancela uma subscrição ativa pelo ID retornado pela API no campo {@code subscription.id}.
     *
     * @param subscriptionId ID da subscrição a ser cancelada
     * @return Future completado com a confirmação do cancelamento
     */
    public CompletableFuture<JsonNode> forget(String subscriptionId) {
        ObjectNode payload = requestSender.newPayload();
        payload.put("forget", subscriptionId);
        return requestSender.send(payload);
    }

    // ═══════════════════════════════════════════════════════════════
    // Roteamento de mensagens
    // ═══════════════════════════════════════════════════════════════

    /**
     * Ponto de entrada de todas as mensagens recebidas via WebSocket.
     * Deserializa o JSON, trata erros da API e roteia pelo campo {@code msg_type}.
     * Mensagens com {@code msg_type} não mapeado são ignoradas silenciosamente.
     *
     * @param raw mensagem JSON bruta recebida do WebSocket
     */
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
                default -> { /* msg_type não mapeado — ignorado silenciosamente */ }
            }

        } catch (Exception e) {
            log.warn("MESSAGE ROUTING ERROR | raw={}", raw, e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Handlers por tipo de mensagem
    // ═══════════════════════════════════════════════════════════════

    /**
     * Trata mensagens de erro retornadas pela API Deriv.
     * Quando o {@code req_id} pertencer a uma página de paginação em andamento,
     * delega ao {@link DerivHistoryPaginator} para entrega parcial do histórico.
     * Para demais requisições com {@code req_id} válido, completa o Future
     * correspondente com {@link DerivErrorException}.
     *
     * @param msg mensagem de erro JSON recebida da API
     */
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
     * Processa tick recebido, valida os campos obrigatórios e notifica o {@link TickHeartbeat}.
     * A notificação ao {@link TickHeartbeat} é obrigatória para que o monitor de saúde
     * da conexão funcione corretamente. Ticks com campos inválidos são silenciosamente descartados.
     *
     * @param msg mensagem JSON do tipo {@code tick}
     */
    private void handleTick(JsonNode msg) {
        JsonNode tick = msg.get("tick");
        if (tick == null || !tick.isObject()) return;

        String symbol = tick.path("symbol").asText("");
        long   epoch  = tick.path("epoch").asLong(-1);
        double quote  = tick.path("quote").asDouble(Double.NaN);

        if (symbol.isBlank() || epoch <= 0
                || !Double.isFinite(quote)) return;

        // Obrigatório para que o TickHeartbeat detecte conexões ativas
        tickHeartbeat.recordTick();

        TickHandler handler = onTick;
        if (handler == null) return;

        handler.onTick(symbol, epoch, quote);
    }

    /**
     * Processa página de histórico recebida e delega ao {@link DerivHistoryPaginator},
     * que decide se deve solicitar mais páginas ou entregar o resultado ao callback.
     *
     * @param msg mensagem JSON do tipo {@code history} ou {@code candles}
     */
    private void handleHistory(JsonNode msg) {
        JsonNode candles = msg.get("candles");
        if (candles == null || !candles.isArray()) return;

        List<Bar> bars  = parseBars(candles);
        long      reqId = msg.path("req_id").asLong(-1);

        historyPaginator.handlePage(reqId, bars);
    }

    /**
     * Processa atualização de contrato aberto recebida via stream.
     * Completa o Future pendente do {@link DerivRequestSender} para o acknowledge
     * da subscrição e invoca o callback externo registrado em {@link #onOpenContract}
     * para todas as atualizações subsequentes.
     *
     * @param msg mensagem JSON do tipo {@code proposal_open_contract}
     */
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

    /**
     * Converte o array JSON de candles em lista de {@link Bar} ordenada cronologicamente.
     * Candles com campos inválidos ou ausentes são silenciosamente descartados.
     *
     * @param candles nó JSON do tipo array com os candles recebidos
     * @return lista de {@link Bar} válidos ordenados por timestamp
     */
    private List<Bar> parseBars(JsonNode candles) {
        List<Bar> bars = new ArrayList<>(candles.size());

        for (JsonNode node : candles) {
            Bar bar = parseBar(node);
            if (bar != null) bars.add(bar);
        }

        bars.sort(Comparator.comparing(Bar::timestamp));
        return bars;
    }

    /**
     * Converte um nó JSON individual em um {@link Bar}.
     * Retorna {@code null} quando o epoch for inválido ou qualquer campo OHLC
     * não for um número finito, descartando silenciosamente o candle corrompido.
     * O campo {@code volume} aceita o valor padrão {@code 0.0} pois a API
     * Deriv não fornece volume real para forex e metais.
     *
     * @param node nó JSON representando um único candle
     * @return {@link Bar} construído ou {@code null} se os dados forem inválidos
     */
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

    /**
     * Encaminha o histórico completo ao callback externo registrado em {@link #onCandleHistory}.
     * Invocado pelo {@link DerivHistoryPaginator} ao concluir todas as páginas de uma requisição.
     *
     * @param reqId ID pai da requisição de paginação
     * @param bars  lista completa de candles acumulados
     */
    private void dispatchCandleHistory(long reqId, List<Bar> bars) {
        BiConsumer<Long, List<Bar>> handler = onCandleHistory;
        if (handler != null) {
            handler.accept(reqId, bars);
        }
    }
}