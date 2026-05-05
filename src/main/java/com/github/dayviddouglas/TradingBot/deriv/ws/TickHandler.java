package com.github.dayviddouglas.TradingBot.deriv.ws;

/**
 * Interface funcional para recebimento de ticks em tempo real.
 *
 * Registrada no DerivMarketDataService e implementada pelo
 * MultiSymbolDerivBotRunner via lambda para rotear ticks
 * aos TickCandleAggregators correspondentes.
 *
 * @FunctionalInterface garante compatibilidade com lambdas e
 * method references, facilitando o registro do callback.
 */
@FunctionalInterface
public interface TickHandler {

    /**
     * Chamado quando um tick é recebido da API Deriv.
     *
     * @param symbol      símbolo do ativo
     * @param epochSeconds timestamp do tick em epoch seconds (UTC)
     * @param quote       cotação do tick
     */
    void onTick(String symbol, long epochSeconds, double quote);
}