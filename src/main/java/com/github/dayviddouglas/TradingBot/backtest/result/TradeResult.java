package com.github.dayviddouglas.TradingBot.backtest.result;

/**
 * Resultado imutável de uma operação simulada no backtest.
 *
 * Representa o resultado de uma entrada e saída do mercado
 * simulada pelo SimpleBacktester sobre dados históricos.
 *
 * @param timestamp    horário de entrada no formato ISO
 * @param strategyName nome da estratégia que gerou o sinal
 * @param signalType   tipo do sinal (BUY ou SELL)
 * @param entryPrice   preço de entrada (close do candle de sinal)
 * @param exitPrice    preço de saída (close N barras à frente)
 * @param won          true se o trade foi vencedor
 * @param pnl          lucro ou prejuízo (+profitPayout para WIN, -1.0 para LOSS)
 */
public record TradeResult(
        String timestamp,
        String strategyName,
        String signalType,
        double entryPrice,
        double exitPrice,
        boolean won,
        double pnl
) {
    /**
     * Verifica se o resultado representa uma perda.
     *
     * @return true se o trade foi perdedor
     */
    public boolean lost() {
        return !won;
    }

    /**
     * Retorna o valor absoluto do pnl.
     * Útil para cálculo de avgLoss sem negação explícita.
     *
     * @return |pnl|
     */
    public double absolutePnl() {
        return Math.abs(pnl);
    }
}
