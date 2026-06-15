package com.github.dayviddouglas.TradingBot.backtest.result;

/**
 * Resultado imutável de uma operação simulada pelo {@code SimpleBacktester} sobre dados históricos.
 *
 * Representa o desfecho de uma entrada e saída do mercado simulada:
 * o sinal de entrada, os preços de entrada e saída e o PnL resultante.
 * O PnL é positivo ({@code profitPayout}) em trades vencedores e negativo ({@code -1.0})
 * em trades perdedores, refletindo a estrutura de payout fixo do produto binário simulado.
 *
 * @param timestamp    horário de entrada no formato ISO, extraído do candle de sinal
 * @param strategyName nome da estratégia que gerou o sinal de entrada
 * @param signalType   tipo do sinal: {@code "BUY"} ou {@code "SELL"}
 * @param entryPrice   preço de fechamento do candle no momento do sinal
 * @param exitPrice    preço de fechamento do candle de saída, N barras à frente
 * @param won          {@code true} se o trade resultou em vitória
 * @param pnl          lucro ({@code +profitPayout}) ou prejuízo ({@code -1.0})
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
     * @return {@code true} se o trade foi perdedor
     */
    public boolean lost() {
        return !won;
    }

    /**
     * Retorna o valor absoluto do PnL.
     * Utilizado pelo {@link BacktestMetricsCalculator} para calcular
     * {@code avgLoss} e {@code grossLoss} sem negação explícita.
     *
     * @return {@code |pnl|}
     */
    public double absolutePnl() {
        return Math.abs(pnl);
    }
}