package com.github.dayviddouglas.TradingBot.exceptions;

/**
 * Representa um erro retornado pela API da Deriv via WebSocket.
 *
 * Quando a API retorna uma mensagem com o campo {@code error},
 * o {@link com.github.dayviddouglas.TradingBot.deriv.DerivMarketDataService}
 * encapsula a mensagem nesta exceção e completa o {@code CompletableFuture}
 * pendente via {@code completeExceptionally()}, permitindo tratamento tipado
 * pelos chamadores.
 *
 * Estende {@link RuntimeException} pois erros da API são imprevisíveis
 * e não fazem parte do fluxo normal de execução. O
 * {@link com.github.dayviddouglas.TradingBot.deriv.trade.context.TradeErrorHandler}
 * é responsável por classificar e tratar esses erros por categoria.
 *
 * Exemplos de mensagens que esta exceção pode conter:
 * <ul>
 *   <li>{@code "Stake can not have more than 2 decimal places"}</li>
 *   <li>{@code "This contract is not offered for this duration"}</li>
 *   <li>{@code "The underlying market has moved too much"}</li>
 *   <li>{@code "Insufficient balance"}</li>
 *   <li>{@code "Please log in"}</li>
 * </ul>
 *
 * A mensagem de erro vem da Deriv em inglês. O
 * {@link com.github.dayviddouglas.TradingBot.deriv.trade.context.TradeErrorHandler}
 * normaliza para lowercase antes de comparar, tornando a classificação insensível a maiúsculas.
 */
public class DerivErrorException extends RuntimeException {

    /**
     * @param message mensagem de erro original retornada pela API Deriv
     */
    public DerivErrorException(String message) {
        super(message);
    }
}