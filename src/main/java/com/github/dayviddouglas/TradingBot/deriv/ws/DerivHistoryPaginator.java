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
 * Responsável pela paginação automática de histórico de candles.
 *
 * Extraído do DerivMarketDataService para respeitar SRP:
 * - DerivMarketDataService roteia mensagens
 * - DerivHistoryPaginator gerencia o ciclo de paginação
 *
 * A API Deriv limita cada requisição a 1000 candles.
 * Quando o count solicitado excede esse limite, este componente:
 * 1. Divide em páginas de até 1000 candles
 * 2. Solicita do presente ao passado
 * 3. Acumula resultados no PaginationContext
 * 4. Entrega o histórico completo via callback ao concluir
 *
 * Thread-safety: ConcurrentHashMap para contextos e rotas.
 */
@Component
public class DerivHistoryPaginator {

    private static final Logger log =
            LoggerFactory.getLogger(DerivHistoryPaginator.class);

    private static final int MAX_PER_REQUEST = 1000;

    private final DerivRequestSender requestSender;

    private final Map<Long, PaginationContext> contextsByParentReqId =
            new ConcurrentHashMap<>();

    private final Map<Long, PageRoute> routesByPageReqId =
            new ConcurrentHashMap<>();

    public DerivHistoryPaginator(DerivRequestSender requestSender) {
        this.requestSender = requestSender;
    }

    /**
     * Inicia requisição de histórico com paginação automática.
     *
     * @param symbol             símbolo do ativo
     * @param granularitySeconds granularidade em segundos
     * @param count              quantidade total desejada
     * @param onComplete         callback chamado quando concluído
     * @return parentReqId para correlação externa
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
     *
     * @param reqId ID da requisição desta página
     * @param bars  barras recebidas e parseadas
     */
    public void handlePage(long reqId, List<Bar> bars) {
        PageRoute route = routesByPageReqId.remove(reqId);

        if (route == null) {
            deliverSimplePage(reqId, bars);
            return;
        }

        handlePaginatedPage(route, bars);
    }

    /**
     * Trata erro em página de histórico paginado.
     * Entrega resultado parcial acumulado sem perder dados já recebidos.
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
     * Verifica se o reqId pertence a uma página de paginação em andamento.
     *
     * @param reqId ID a verificar
     * @return true se for uma página de paginação
     */
    public boolean isPaginatedPage(long reqId) {
        return routesByPageReqId.containsKey(reqId);
    }

    // ═══════════════════════════════════════════════════════════════
    // Envio de páginas
    // ═══════════════════════════════════════════════════════════════

    private void sendPage(PaginationContext ctx, int count, Long endEpoch) {
        long pageReqId = sendHistoryRequest(
                ctx.getSymbol(), ctx.getGranularitySeconds(), count, endEpoch);

        routesByPageReqId.put(pageReqId, new PageRoute(ctx.getParentReqId(), count));
    }

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

    private void deliverSimplePage(long reqId, List<Bar> bars) {
        PaginationContext ctx = contextsByParentReqId.remove(reqId);
        if (ctx == null) return;
        ctx.getOnComplete().accept(ctx.getParentReqId(), List.copyOf(bars));
    }

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

    private boolean shouldFetchMorePages(
            PaginationContext ctx,
            PageRoute route,
            List<Bar> bars
    ) {
        int remaining = ctx.getTotalRequested() - ctx.size();
        boolean receivedFullPage = bars.size() == route.requestedCount();
        return remaining > 0 && receivedFullPage && !bars.isEmpty();
    }

    private void requestNextPage(PaginationContext ctx, List<Bar> bars) {
        long oldestEpoch = bars.get(0).timestamp().getEpochSecond();
        int remaining = ctx.getTotalRequested() - ctx.size();
        int nextCount = Math.min(remaining, MAX_PER_REQUEST);
        sendPage(ctx, nextCount, oldestEpoch - 1);
    }

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

    private long generateParentReqId() {
        return requestSender.generateReqId();
    }
}