package com.github.dayviddouglas.TradingBot.exceptions;

/**
 * Exceção que representa um erro retornado pela API da Deriv via WebSocket.
 *
 * Quando a API da Deriv retorna uma mensagem com o campo "error",
 * o DerivMarketDataService encapsula a mensagem de erro nesta exceção
 * e completa o CompletableFuture pendente com completeExceptionally().
 *
 * Isso permite que os chamadores (ex: DerivTradeService) tratem erros
 * da API de forma tipada e estruturada, diferenciando de outros tipos
 * de exceção (timeout, IO, etc.).
 *
 * Estende RuntimeException (unchecked) para evitar a necessidade de
 * declarar throws em toda a cadeia de chamada. Isso é adequado porque:
 * - Erros da API são imprevisíveis e não devem ser tratados no fluxo normal
 * - Os chamadores usam CompletableFuture que já encapsula exceções
 * - O handleTradeException() do DerivTradeService faz a classificação adequada
 *
 * Exemplos de mensagens que esta exceção pode conter:
 * - "Stake can not have more than 2 decimal places"
 * - "This contract is not offered for this duration"
 * - "The underlying market has moved too much"
 * - "Insufficient balance"
 * - "Please log in"
 *
 * ⚠️ Ponto de atenção: A mensagem de erro vem da Deriv em inglês.
 * O DerivTradeService normaliza para lowercase antes de comparar,
 * então as comparações no handleTradeException são case-insensitive.
 *
 * 💡 Sugestão: Em evolução futura, considere adicionar o código do erro
 * (ex: "PriceMoved", "AuthorizationRequired") como campo separado
 * para classificação mais precisa, em vez de depender de string matching
 * na mensagem.
 */
public class DerivErrorException extends RuntimeException {

    /**
     * Construtor com a mensagem de erro retornada pela API Deriv.
     *
     * @param message mensagem de erro original da API (ex: "Insufficient balance")
     */
    public DerivErrorException(String message) {
        super(message);
    }
}