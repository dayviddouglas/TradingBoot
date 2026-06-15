package com.github.dayviddouglas.TradingBot.backtest.result;

import java.util.List;

/**
 * Responsável pelo cálculo das métricas estatísticas de backtest a partir
 * dos resultados de trades simulados pelo {@code SimpleBacktester}.
 *
 * Métricas calculadas:
 * <ul>
 *   <li>Contagens: {@code totalTrades}, {@code wins}, {@code losses}</li>
 *   <li>Taxas: {@code winRate}</li>
 *   <li>Médias: {@code avgWin}, {@code avgLoss}, {@code totalPnl}</li>
 *   <li>Ratios: {@code payoffRatio}, {@code profitFactor}</li>
 *   <li>Estatísticas de risco: {@code expectancy}, {@code maxDrawdown},
 *       {@code maxConsecutiveLosses}</li>
 * </ul>
 *
 * Esta é uma classe utilitária final e não instanciável.
 * Todos os métodos são estáticos e sem estado, tornando a classe thread-safe por natureza.
 */
public final class BacktestMetricsCalculator {

    private BacktestMetricsCalculator() {
    }

    /**
     * Calcula todas as métricas estatísticas e constrói o {@link BacktestReport} completo.
     * Quando a lista de resultados for nula ou vazia, retorna um relatório vazio via
     * {@link BacktestReport#empty(String)}.
     *
     * @param symbol  símbolo ou identificador do backtest
     * @param results lista de resultados de trades simulados em ordem cronológica
     * @return relatório completo com todas as métricas calculadas, ou relatório vazio
     */
    public static BacktestReport calculate(String symbol, List<TradeResult> results) {
        if (results == null || results.isEmpty()) {
            return BacktestReport.empty(symbol);
        }

        int totalTrades = results.size();
        int wins        = countWins(results);
        int losses      = totalTrades - wins;

        double winRate              = winRate(wins, totalTrades);
        double totalPnl             = totalPnl(results);
        double avgWin               = avgWin(results);
        double avgLoss              = avgLoss(results);
        double payoffRatio          = payoffRatio(avgWin, avgLoss);
        double expectancy           = expectancy(wins, losses, totalTrades, avgWin, avgLoss);
        double profitFactor         = profitFactor(results);
        double maxDrawdown          = maxDrawdown(results);
        int    maxConsecutiveLosses = maxConsecutiveLosses(results);

        return new BacktestReport(
                symbol, totalTrades, wins, losses,
                winRate, totalPnl, avgWin, avgLoss,
                payoffRatio, expectancy, profitFactor,
                maxDrawdown, maxConsecutiveLosses, results
        );
    }

    // ═══════════════════════════════════════════════════════════════
    // Métricas individuais
    // ═══════════════════════════════════════════════════════════════

    /**
     * Conta o total de trades vencedores na lista de resultados.
     *
     * @param results lista de resultados simulados
     * @return quantidade de trades onde {@link TradeResult#won()} é {@code true}
     */
    private static int countWins(List<TradeResult> results) {
        return (int) results.stream().filter(TradeResult::won).count();
    }

    /**
     * Calcula a taxa de acerto em percentual.
     *
     * @param wins        quantidade de trades vencedores
     * @param totalTrades quantidade total de trades
     * @return win rate entre 0 e 100; {@code 0.0} quando não há trades
     */
    private static double winRate(int wins, int totalTrades) {
        return totalTrades == 0 ? 0.0 : (wins * 100.0 / totalTrades);
    }

    /**
     * Soma o PnL de todos os trades para obter o resultado líquido total.
     *
     * @param results lista de resultados simulados
     * @return PnL total acumulado
     */
    private static double totalPnl(List<TradeResult> results) {
        return results.stream().mapToDouble(TradeResult::pnl).sum();
    }

    /**
     * Calcula o ganho médio dos trades vencedores.
     *
     * @param results lista de resultados simulados
     * @return média do PnL dos trades com {@code won == true}; {@code 0.0} se não houver wins
     */
    private static double avgWin(List<TradeResult> results) {
        return results.stream()
                .filter(TradeResult::won)
                .mapToDouble(TradeResult::pnl)
                .average()
                .orElse(0.0);
    }

    /**
     * Calcula a perda média dos trades perdedores em valor absoluto.
     *
     * @param results lista de resultados simulados
     * @return média do PnL absoluto dos trades com {@code lost == true}; {@code 0.0} se não houver losses
     */
    private static double avgLoss(List<TradeResult> results) {
        return results.stream()
                .filter(TradeResult::lost)
                .mapToDouble(TradeResult::absolutePnl)
                .average()
                .orElse(0.0);
    }

    /**
     * Calcula o payoff ratio: razão entre o ganho médio e a perda média.
     *
     * @param avgWin  ganho médio dos trades vencedores
     * @param avgLoss perda média dos trades perdedores em valor absoluto
     * @return {@code avgWin / avgLoss}; {@code 0.0} quando {@code avgLoss} for zero
     */
    private static double payoffRatio(double avgWin, double avgLoss) {
        return avgLoss == 0.0 ? 0.0 : avgWin / avgLoss;
    }

    /**
     * Calcula a expectância por trade: resultado esperado médio em R por operação.
     * Combina a probabilidade de acerto, o ganho médio e a probabilidade de perda.
     *
     * @param wins     quantidade de trades vencedores
     * @param losses   quantidade de trades perdedores
     * @param total    quantidade total de trades
     * @param avgWin   ganho médio dos vencedores
     * @param avgLoss  perda média dos perdedores em valor absoluto
     * @return expectância por trade; {@code 0.0} quando não há trades
     */
    private static double expectancy(
            int wins, int losses, int total,
            double avgWin, double avgLoss
    ) {
        if (total == 0) return 0.0;
        return ((wins / (double) total) * avgWin)
                - ((losses / (double) total) * avgLoss);
    }

    /**
     * Calcula o profit factor: razão entre o lucro bruto total e a perda bruta total.
     *
     * @param results lista de resultados simulados
     * @return {@code grossProfit / grossLoss}; {@code 0.0} quando não há perdas
     */
    private static double profitFactor(List<TradeResult> results) {
        double grossProfit = results.stream()
                .filter(TradeResult::won)
                .mapToDouble(TradeResult::pnl)
                .sum();

        double grossLoss = results.stream()
                .filter(TradeResult::lost)
                .mapToDouble(TradeResult::absolutePnl)
                .sum();

        return grossLoss == 0.0 ? 0.0 : grossProfit / grossLoss;
    }

    /**
     * Calcula o Maximum Drawdown da equity curve em ordem cronológica.
     * O drawdown é a maior queda do pico ao vale acumulada durante a simulação.
     *
     * @param results lista de resultados em ordem cronológica
     * @return maximum drawdown em R
     */
    private static double maxDrawdown(List<TradeResult> results) {
        double peak        = 0.0;
        double equity      = 0.0;
        double maxDrawdown = 0.0;

        for (TradeResult result : results) {
            equity     += result.pnl();
            peak        = Math.max(peak, equity);
            maxDrawdown = Math.max(maxDrawdown, peak - equity);
        }

        return maxDrawdown;
    }

    /**
     * Calcula a maior sequência de trades perdedores consecutivos.
     *
     * @param results lista de resultados em ordem cronológica
     * @return quantidade máxima de perdas consecutivas observadas
     */
    private static int maxConsecutiveLosses(List<TradeResult> results) {
        int max     = 0;
        int current = 0;

        for (TradeResult result : results) {
            if (result.lost()) {
                current++;
                max = Math.max(max, current);
            } else {
                current = 0;
            }
        }

        return max;
    }
}