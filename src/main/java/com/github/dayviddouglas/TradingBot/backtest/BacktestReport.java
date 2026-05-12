package com.github.dayviddouglas.TradingBot.backtest;

import java.util.List;

/**
 * Relatório completo de backtest com todas as métricas estatísticas.
 *
 * Gerado pelo SimpleBacktester ao final da simulação.
 * Imutável após criação (record).
 *
 * Classificação de edge:
 * - APPROVED:  >= 30 trades, winRate > 52%, expectancy > 0.05, profitFactor > 1.1
 * - WEAK:      >= 30 trades, expectancy positiva mas abaixo do threshold
 * - REJECTED:  >= 30 trades, expectancy negativa ou zero
 * - NO_DATA:   menos de 30 trades (amostra insuficiente)
 *
 * @param symbol               símbolo ou identificador do backtest
 * @param totalTrades          quantidade total de trades simulados
 * @param wins                 quantidade de trades vencedores
 * @param losses               quantidade de trades perdedores
 * @param winRate              taxa de acerto em percentual
 * @param totalPnl             lucro/prejuízo total acumulado
 * @param avgWin               ganho médio por trade vencedor
 * @param avgLoss              perda média por trade perdedor
 * @param payoffRatio          razão avgWin / avgLoss
 * @param expectancy           expectância por trade em R
 * @param profitFactor         razão grossProfit / grossLoss
 * @param maxDrawdown          máximo drawdown da equity curve
 * @param maxConsecutiveLosses maior sequência de perdas consecutivas
 * @param results              lista de resultados individuais
 */
public record BacktestReport(
        String symbol,
        int totalTrades,
        int wins,
        int losses,
        double winRate,
        double totalPnl,
        double avgWin,
        double avgLoss,
        double payoffRatio,
        double expectancy,
        double profitFactor,
        double maxDrawdown,
        int maxConsecutiveLosses,
        List<TradeResult> results
) {
    /**
     * Cria relatório vazio para casos sem dados suficientes.
     *
     * @param symbol símbolo do ativo
     * @return relatório com todos os valores zerados
     */
    public static BacktestReport empty(String symbol) {
        return new BacktestReport(
                symbol, 0, 0, 0, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0, 0.0,
                0.0, 0, List.of()
        );
    }

    /**
     * Verifica se o backtest apresenta edge estatístico real.
     *
     * Critérios combinados para evitar falsos positivos:
     * - Amostra mínima de 30 trades
     * - Win rate acima de 52%
     * - Expectância positiva significativa
     * - Profit factor acima de 1.1
     *
     * @return true se o sistema apresenta edge
     */
    public boolean hasEdge() {
        return totalTrades >= 30
                && winRate > 52.0
                && expectancy > 0.05
                && profitFactor > 1.1;
    }

    /**
     * Classifica o resultado do backtest em categorias operacionais.
     *
     * @return classificação: APPROVED, WEAK, REJECTED ou NO_DATA
     */
    public String classification() {
        if (totalTrades < 30) return "NO_DATA";
        if (hasEdge()) return "APPROVED";
        if (expectancy > 0.0) return "WEAK";
        return "REJECTED";
    }

    /**
     * Formata o relatório completo para exibição no console.
     *
     * @return string formatada com todas as métricas
     */
    public String toPrettyReport() {
        return """
                ╔══════════════════════════════════════════════════╗
                ║  BACKTEST REPORT - %-29s ║
                ╠══════════════════════════════════════════════════╣
                ║  Total trades:         %-26d ║
                ║  Wins:                 %-26d ║
                ║  Losses:               %-26d ║
                ║  Win Rate:             %-24.1f %% ║
                ║                                                  ║
                ║  Avg Win:              %-24.2f R ║
                ║  Avg Loss:             %-24.2f R ║
                ║  Payoff Ratio:         %-26.2f ║
                ║                                                  ║
                ║  Expectancy:           %-24.4f R ║
                ║  Profit Factor:        %-26.2f ║
                ║  Total P&L:            %-24.2f R ║
                ║                                                  ║
                ║  Max Drawdown:         %-24.2f R ║
                ║  Max Consec. Losses:   %-26d ║
                ║                                                  ║
                ║  RESULT:               %-26s ║
                ║  HAS EDGE:             %-26s ║
                ╚══════════════════════════════════════════════════╝
                """.formatted(
                symbol, totalTrades, wins, losses, winRate,
                avgWin, avgLoss, payoffRatio,
                expectancy, profitFactor, totalPnl,
                maxDrawdown, maxConsecutiveLosses,
                classification(),
                hasEdge() ? "YES ✅" : "NO ❌"
        );
    }
}