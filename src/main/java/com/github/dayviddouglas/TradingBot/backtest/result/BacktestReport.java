package com.github.dayviddouglas.TradingBot.backtest.result;

import java.util.List;

/**
 * Relatório imutável com todas as métricas estatísticas de um backtest.
 *
 * Produzido pelo {@link BacktestMetricsCalculator} a partir dos resultados
 * simulados pelo {@code SimpleBacktester}. A classificação de edge é calculada
 * combinando múltiplos critérios para evitar falsos positivos com amostras pequenas.
 *
 * Critérios de classificação:
 * <ul>
 *   <li>{@code APPROVED}: {@code totalTrades >= 30}, {@code winRate > 52%},
 *       {@code expectancy > 0.05} e {@code profitFactor > 1.1} — edge estatístico confirmado</li>
 *   <li>{@code WEAK}: {@code totalTrades >= 30} e {@code expectancy > 0.0} —
 *       expectância positiva mas abaixo dos limiares de aprovação</li>
 *   <li>{@code REJECTED}: {@code totalTrades >= 30} e {@code expectancy <= 0.0} —
 *       sem edge identificado</li>
 *   <li>{@code NO_DATA}: {@code totalTrades < 30} — amostra insuficiente para conclusão</li>
 * </ul>
 *
 * @param symbol               símbolo ou identificador do backtest
 * @param totalTrades          quantidade total de trades simulados
 * @param wins                 quantidade de trades vencedores
 * @param losses               quantidade de trades perdedores
 * @param winRate              taxa de acerto em percentual
 * @param totalPnl             lucro/prejuízo total acumulado em R
 * @param avgWin               ganho médio por trade vencedor em R
 * @param avgLoss              perda média por trade perdedor em R (valor absoluto)
 * @param payoffRatio          razão {@code avgWin / avgLoss}
 * @param expectancy           expectância por trade em R
 * @param profitFactor         razão {@code grossProfit / grossLoss}
 * @param maxDrawdown          máximo drawdown da equity curve em R
 * @param maxConsecutiveLosses maior sequência de perdas consecutivas
 * @param results              lista de resultados individuais dos trades simulados
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
     * Cria um relatório vazio para casos sem trades simulados.
     * Todos os campos numéricos são zerados e a lista de resultados é vazia.
     *
     * @param symbol símbolo ou identificador do backtest
     * @return relatório vazio com classificação {@code NO_DATA}
     */
    public static BacktestReport empty(String symbol) {
        return new BacktestReport(
                symbol, 0, 0, 0, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0, 0.0,
                0.0, 0, List.of()
        );
    }

    /**
     * Verifica se o backtest apresenta edge estatístico real, combinando
     * amostra mínima, taxa de acerto, expectância positiva significativa
     * e profit factor acima de 1.1.
     *
     * @return {@code true} se todos os critérios de edge forem satisfeitos
     */
    public boolean hasEdge() {
        return totalTrades >= 30
                && winRate > 52.0
                && expectancy > 0.05
                && profitFactor > 1.1;
    }

    /**
     * Classifica o resultado do backtest em uma das categorias operacionais
     * com base na amostra disponível e nos critérios de edge.
     *
     * @return {@code "APPROVED"}, {@code "WEAK"}, {@code "REJECTED"} ou {@code "NO_DATA"}
     */
    public String classification() {
        if (totalTrades < 30) return "NO_DATA";
        if (hasEdge())        return "APPROVED";
        if (expectancy > 0.0) return "WEAK";
        return "REJECTED";
    }

    /**
     * Formata o relatório completo em bloco de texto estruturado para exibição no console.
     *
     * @return string formatada com todas as métricas e a classificação final
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