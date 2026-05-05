package com.github.dayviddouglas.TradingBot.exceptions;

/**
 * Exceção lançada pelo TradeExecutor quando a execução falha definitivamente.
 *
 * Encapsula erros da API Deriv, timeouts e respostas inválidas
 * que ocorrem durante o ciclo proposal → buy.
 *
 * RuntimeException (unchecked) porque:
 * - Erros de execução são imprevisíveis e não fazem parte do fluxo normal
 * - O DerivTradeService trata via handleTradeException()
 * - CompletableFuture já encapsula exceções checadas
 */
public class TradeExecutionException extends RuntimeException {

    public TradeExecutionException(String message) {
        super(message);
    }

    public TradeExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}