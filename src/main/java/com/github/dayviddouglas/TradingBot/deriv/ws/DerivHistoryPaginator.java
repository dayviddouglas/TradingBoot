package com.github.dayviddouglas.TradingBot.deriv.ws;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dayviddouglas.TradingBot.model.Bar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Responsável pela paginação automática de histórico de candles junto à API Deriv.
 *
 * A API Deriv limita cada requisição de histórico a {@value MAX_PER_REQUEST} candles.
 * Quando a quantidade solicitada excede esse limite, este componente divide a requisição
 * em páginas sequenciais, coletando do presente ao passado:
 * <ol>
 *   <li>Solicita a primeira página com até {@value MAX_PER_REQUEST} candles a partir de "latest"</li>
 *   <li>Ao receber cada página, verifica se ainda há candles restantes a buscar</li>
 *   <li>Quando necessário, solicita a próxima página usando o timestamp do candle mais antigo
 *       da página atual como ponto de corte ({@code endEpoch - 1})</li>
 *   <li>Ao atingir a quantidade total ou esgotar o histórico disponível, entrega todos os
 *       candles acumulados via callback ao chamador original</li>
 * </ol>
 *
 * A correlação entre páginas e contextos é mantida por dois mapas thread-safe:
 * {@code contextsByParentReqId} mapeia o ID pai para o {@link PaginationContext} acumulador,
 * e {@code routesByPageReqId} mapeia o ID de cada página para sua {@link PageRoute},
 * que contém a referência ao ID pai.
 */
@Component
public class DerivHistoryPaginator {

    private static final Logger log =
            LoggerFactory.getLogger(DerivHistoryPaginator.class);

    /** Limite máximo de candles por requisição imposto pela API Deriv. */
    private static final int MAX_PER_REQUEST = 1000;

    private final DerivRequestSender requestSender;

    /**
     * Contextos de paginação ativos, indexados pelo ID pai gerado internamente.
     * Cada contexto acumula as barras recebidas de todas as páginas de uma mesma requisição.
     */
    private final Map<Long, PaginationContext> contextsByParentReqId =
            new ConcurrentHashMap<>();

    /**
     * Mapeamento de ID de página para sua rota de paginação.
     * Permite identificar, ao receber uma resposta de histórico, se ela pertence
     * a uma paginação em andamento e qual é o contexto acumulador correspondente.
     */
    private final Map<Long, PageRoute> routesByPageReqId =
            new ConcurrentHashMap<>();

    /**
     * @param requestSender utilizado para enviar os requests de histórico via WebSocket
     */
    public DerivHistoryPaginator(DerivRequestSender requestSender) {
        this.requestSender = requestSender;
    }

    /**
     * Inicia uma requisição de histórico com paginação automática.
     * Quando {@code count} for menor ou igual a {@value MAX_PER_REQUEST}, envia uma
     * única página. Quando exceder o limite, inicia o ciclo de paginação e registra
     * o número estimado de páginas necessárias.
     *
     * @param symbol             símbolo do ativo
     * @param granularitySeconds granularidade dos candles em segundos
     * @param count              quantidade total de candles desejada
     * @param onComplete         callback invocado com o ID pai e a lista completa de candles
     *                           ao concluir todas as páginas
     * @return ID pai da requisição, utilizado para correlação externa
     */
    public long request(
            String symbol,
            int granularitySeconds,
            int count,
            BiConsumer<Long, List<Bar>> onComplete
    ) {
        long parentReqId = generateParentReqId();

        PaginationContext ctx = new PaginationContext(
                parentReqId, symbol, granularitySeconds, count, onComplete);

        contextsByParentReqId.put(parentReqId, ctx);

        int firstPageCount = Math.min(count, MAX_PER_REQUEST);
        sendPage(ctx, firstPageCount, null);

        if (count > MAX_PER_REQUEST) {
            int totalPages = (int) Math.ceil((double) count / MAX_PER_REQUEST);
            log.info("PAGINATED HISTORY START | symbol={} | count={} " +
                            "| parent_req_id={} | estimatedPages={}",
                    symbol, count, parentReqId, totalPages);
        }

        return parentReqId;
    }

    /**
     * Processa uma página de histórico recebida da API.
     * Quando o {@code reqId} corresponder a uma rota de paginação registrada,
     * delega ao fluxo paginado; caso contrário, trata como requisição simples
     * e entrega diretamente ao callback do contexto.
     *
     * @param reqId ID da requisição da página recebida
     * @param bars  candles parseados recebidos nesta página
     */
    public void handlePage(long reqId, List<Bar> bars) {
        PageRoute route = routesByPageReqId.remove(reqId);

        if (route == null) {
            // Requisição sem paginação: entrega diretamente ao callback
            deliverSimplePage(reqId, bars);
            return;
        }

        handlePaginatedPage(route, bars);
    }

    /**
     * Trata erro em uma página de histórico paginado.
     * Entrega ao callback o resultado parcial já acumulado até o momento da falha,
     * sem perder os candles das páginas anteriores.
     *
     * @param reqId ID da página que falhou
     */
    public void handlePageError(long reqId) {
        PageRoute route = routesByPageReqId.remove(reqId);
        if (route == null) return;

        long parentReqId = route.parentReqId();
        PaginationContext ctx = contextsByParentReqId.remove(parentReqId);

        List<Bar> partial = ctx != null ? ctx.snapshot() : List.of();
        log.warn("PAGINATED HISTORY ERROR | parent_req_id={} | partial_bars={}",
                parentReqId, partial.size());

        if (ctx != null) {
            ctx.getOnComplete().accept(parentReqId, partial);
        }
    }

    /**
     * Verifica se o {@code reqId} corresponde a uma página de paginação em andamento.
     * Utilizado pelo {@link com.github.dayviddouglas.TradingBot.deriv.DerivMarketDataService}
     * para rotear a resposta ao handler correto.
     *
     * @param reqId ID a ser verificado
     * @return {@code true} se o ID pertencer a uma página de paginação registrada
     */
    public boolean isPaginatedPage(long reqId) {
        return routesByPageReqId.containsKey(reqId);
    }

    // ═══════════════════════════════════════════════════════════════
    // Envio de páginas
    // ═══════════════════════════════════════════════════════════════

    /**
     * Envia uma página de histórico e registra a rota de paginação correspondente.
     * Quando {@code endEpoch} for nulo, solicita a partir de "latest".
     *
     * @param ctx      contexto acumulador da paginação
     * @param count    quantidade de candles desta página
     * @param endEpoch epoch do último candle aceito nesta página; nulo para a primeira página
     */
    private void sendPage(PaginationContext ctx, int count, Long endEpoch) {
        long pageReqId = sendHistoryRequest(
                ctx.getSymbol(), ctx.getGranularitySeconds(), count, endEpoch);

        routesByPageReqId.put(pageReqId, new PageRoute(ctx.getParentReqId(), count));
    }

    /**
     * Monta e envia o payload de requisição de histórico via WebSocket.
     * Utiliza o campo {@code end: "latest"} na primeira página e o epoch calculado
     * nas páginas subsequentes para navegar para o passado.
     *
     * @param symbol             símbolo do ativo
     * @param granularitySeconds granularidade dos candles em segundos
     * @param count              quantidade de candles solicitados nesta página
     * @param endEpoch           epoch de corte; nulo para solicitar a partir de "latest"
     * @return ID da requisição gerado pelo {@link DerivRequestSender}
     */
    private long sendHistoryRequest(
            String symbol,
            int granularitySeconds,
            int count,
            Long endEpoch
    ) {
        ObjectNode payload = requestSender.newPayload();
        payload.put("ticks_history", symbol);
        payload.put("adjust_start_time", 1);
        payload.put("count", count);
        payload.put("style", "candles");
        payload.put("granularity", granularitySeconds);

        if (endEpoch == null) {
            payload.put("end", "latest");
        } else {
            payload.put("end", endEpoch);
        }

        return requestSender.sendFireAndForget(payload);
    }

    // ═══════════════════════════════════════════════════════════════
    // Processamento de páginas
    // ═══════════════════════════════════════════════════════════════

    /**
     * Entrega os candles de uma requisição simples (sem paginação) diretamente ao callback.
     * Remove o contexto do mapa após a entrega.
     *
     * @param reqId ID da requisição, equivalente ao ID pai neste caso
     * @param bars  candles recebidos
     */
    private void deliverSimplePage(long reqId, List<Bar> bars) {
        PaginationContext ctx = contextsByParentReqId.remove(reqId);
        if (ctx == null) return;
        ctx.getOnComplete().accept(ctx.getParentReqId(), List.copyOf(bars));
    }

    /**
     * Processa uma página dentro de um ciclo de paginação.
     * Acumula os candles no contexto e decide se deve solicitar mais páginas
     * ou finalizar a paginação e entregar o resultado completo.
     *
     * @param route rota da página recebida, contendo referência ao ID pai
     * @param bars  candles desta página
     */
    private void handlePaginatedPage(PageRoute route, List<Bar> bars) {
        long parentReqId = route.parentReqId();
        PaginationContext ctx = contextsByParentReqId.get(parentReqId);

        if (ctx == null) {
            log.warn("PAGINATED HISTORY | context not found | parent_req_id={}",
                    parentReqId);
            return;
        }

        ctx.prependPage(bars);

        if (shouldFetchMorePages(ctx, route, bars)) {
            requestNextPage(ctx, bars);
            return;
        }

        finalizePagination(ctx);
    }

    /**
     * Determina se ainda é necessário buscar mais páginas.
     * Continua a paginação somente quando ainda há candles restantes,
     * a página atual veio completa (sem truncamento pela API) e não está vazia.
     *
     * @param ctx   contexto acumulador com o total já recebido
     * @param route rota da página atual com a quantidade solicitada
     * @param bars  candles recebidos nesta página
     * @return {@code true} se uma nova página deve ser solicitada
     */
    private boolean shouldFetchMorePages(
            PaginationContext ctx,
            PageRoute route,
            List<Bar> bars
    ) {
        int remaining = ctx.getTotalRequested() - ctx.size();
        boolean receivedFullPage = bars.size() == route.requestedCount();
        return remaining > 0 && receivedFullPage && !bars.isEmpty();
    }

    /**
     * Solicita a próxima página usando o epoch do candle mais antigo da página atual
     * como ponto de corte, subtraindo 1 segundo para evitar sobreposição.
     *
     * @param ctx  contexto acumulador com o total já recebido
     * @param bars candles da página atual, usados para extrair o epoch mais antigo
     */
    private void requestNextPage(PaginationContext ctx, List<Bar> bars) {
        long oldestEpoch = bars.get(0).timestamp().getEpochSecond();
        int remaining = ctx.getTotalRequested() - ctx.size();
        int nextCount = Math.min(remaining, MAX_PER_REQUEST);
        sendPage(ctx, nextCount, oldestEpoch - 1);
    }

    /**
     * Finaliza o ciclo de paginação, remove o contexto do mapa e entrega
     * a lista completa e imutável de candles ao callback do chamador original.
     *
     * @param ctx contexto acumulador com todos os candles das páginas recebidas
     */
    private void finalizePagination(PaginationContext ctx) {
        contextsByParentReqId.remove(ctx.getParentReqId());

        List<Bar> finalBars = ctx.snapshot();
        log.info("PAGINATED HISTORY COMPLETE | parent_req_id={} | symbol={} " +
                        "| requested={} | received={}",
                ctx.getParentReqId(), ctx.getSymbol(),
                ctx.getTotalRequested(), finalBars.size());

        ctx.getOnComplete().accept(ctx.getParentReqId(), finalBars);
    }

    // ═══════════════════════════════════════════════════════════════
    // Utilitários
    // ═══════════════════════════════════════════════════════════════

    /**
     * Gera um novo ID pai delegando ao {@link DerivRequestSender},
     * garantindo unicidade global entre todos os IDs de requisição.
     *
     * @return novo ID pai para a requisição de histórico
     */
    private long generateParentReqId() {
        return requestSender.generateReqId();
    }
}