package com.github.dayviddouglas.TradingBot.deriv.ws;

/**
 * Contrato para recebimento de ticks em tempo real vindos da API Deriv.
 *
 * Registrada no {@link com.github.dayviddouglas.TradingBot.deriv.DerivMarketDataService}
 * e implementada pelo {@code MultiSymbolDerivBotRunner} via lambda para rotear cada tick
 * ao {@link com.github.dayviddouglas.TradingBot.market.TickCandleAggregator}
 * correspondente ao símbolo recebido.
 */
@FunctionalInterface
public interface TickHandler {

    /**
     * Invocado pelo {@link com.github.dayviddouglas.TradingBot.deriv.DerivMarketDataService}
     * a cada tick recebido da API Deriv via WebSocket.
     *
     * @param symbol      símbolo do ativo que gerou o tick
     * @param epochSeconds timestamp do tick em epoch seconds (UTC)
     * @param quote       cotação do ativo no momento do tick
     */
    void onTick(String symbol, long epochSeconds, double quote);
}