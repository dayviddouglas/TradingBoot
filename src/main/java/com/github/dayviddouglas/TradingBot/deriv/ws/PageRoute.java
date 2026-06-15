package com.github.dayviddouglas.TradingBot.deriv.ws;

/**
 * Associa o ID de uma página individual de histórico ao ID pai da requisição
 * de paginação que a originou.
 *
 * Utilizado pelo {@link DerivHistoryPaginator} para identificar, ao receber
 * uma resposta de histórico via WebSocket, a qual contexto de paginação ela pertence
 * e quantos candles haviam sido solicitados nesta página — informação necessária
 * para determinar se a página veio completa e se mais páginas devem ser solicitadas.
 *
 * @param parentReqId    ID da requisição original que iniciou o ciclo de paginação
 * @param requestedCount quantidade de candles solicitados nesta página específica
 */
public record PageRoute(
        long parentReqId,
        int requestedCount
) {
}