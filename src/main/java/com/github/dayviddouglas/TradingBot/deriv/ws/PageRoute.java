package com.github.dayviddouglas.TradingBot.deriv.ws;

/**
 * Roteamento de uma página de histórico ao seu contexto de paginação.
 *
 * Associa o reqId de cada página individual ao parentReqId da
 * requisição original, permitindo que o DerivHistoryPaginator
 * saiba a qual contexto de paginação uma resposta pertence.
 *
 * @param parentReqId    ID da requisição original (pai)
 * @param requestedCount quantidade de candles solicitados nesta página
 */
public record PageRoute(
        long parentReqId,
        int requestedCount
) {
}