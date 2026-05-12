package com.github.dayviddouglas.TradingBot.backtest;

import java.util.List;

/**
 * Responsável pelo cálculo das métricas estatísticas de backtest.
 *
 * Extraído do SimpleBacktester para respeitar SRP:
 * - SimpleBacktester simula trades e coleta resultados
 * - BacktestMetricsCalculator calcula métricas a partir dos resultados
 *
 * Métricas calculadas:
 * - Contagens: totalTrades, wins, losses
 * - Taxas: winRate
 * - Médias: avgWin, avgLoss, totalPnl
 * - Ratios: payoffRatio, profitFactor
 * - Estatísticas: expectancy, maxDrawdown, maxConsecutiveLosses
 *
 * Classe utilitária final (não instanciável):
 * - Sem estado mutável (thread-safe)
 * - Métodos estáticos puros
 */
public final class BacktestMetricsCalculator {

    private BacktestMetricsCalculator() {
    }

    /**
     * Calcula todas as métricas e constrói o relatório de backtest.
     *
     * @param symbol  símbolo ou identificador do backtest
     * @param results lista de resultados de trades simulados
     * @return relatório completo ou relatório vazio se sem trades
     */
    public static BacktestReport calculate(String symbol, List<TradeResult> results) {
        if (results == null || results.isEmpty()) {
            return BacktestReport.empty(symbol);
        }

        int totalTrades = results.size();
        int wins = countWins(results);
        int losses = totalTrades - wins;

        double winRate = winRate(wins, totalTrades);
        double totalPnl = totalPnl(results);
        double avgWin = avgWin(results);
        double avgLoss = avgLoss(results);
        double payoffRatio = payoffRatio(avgWin, avgLoss);
        double expectancy = expectancy(wins, losses, totalTrades, avgWin, avgLoss);
        double profitFactor = profitFactor(results);
        double maxDrawdown = maxDrawdown(results);
        int maxConsecutiveLosses = maxConsecutiveLosses(results);

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

    private static int countWins(List<TradeResult> results) {
        return (int) results.stream().filter(TradeResult::won).count();
    }

    private static double winRate(int wins, int totalTrades) {
        return totalTrades == 0 ? 0.0 : (wins * 100.0 / totalTrades);
    }

    private static double totalPnl(List<TradeResult> results) {
        return results.stream().mapToDouble(TradeResult::pnl).sum();
    }

    private static double avgWin(List<TradeResult> results) {
        return results.stream()
                .filter(TradeResult::won)
                .mapToDouble(TradeResult::pnl)
                .average()
                .orElse(0.0);
    }

    private static double avgLoss(List<TradeResult> results) {
        return results.stream()
                .filter(TradeResult::lost)
                .mapToDouble(TradeResult::absolutePnl)
                .average()
                .orElse(0.0);
    }

    private static double payoffRatio(double avgWin, double avgLoss) {
        return avgLoss == 0.0 ? 0.0 : avgWin / avgLoss;
    }

    private static double expectancy(
            int wins, int losses, int total,
            double avgWin, double avgLoss
    ) {
        if (total == 0) return 0.0;
        return ((wins / (double) total) * avgWin)
                - ((losses / (double) total) * avgLoss);
    }

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
     * Calcula o Maximum Drawdown da equity curve.
     *
     * Maximum Drawdown = maior queda do pico ao vale
     * durante o período de simulação.
     *
     * @param results lista de resultados em ordem cronológica
     * @return maximum drawdown em R
     */
    private static double maxDrawdown(List<TradeResult> results) {
        double peak = 0.0;
        double equity = 0.0;
        double maxDrawdown = 0.0;

        for (TradeResult result : results) {
            equity += result.pnl();
            peak = Math.max(peak, equity);
            maxDrawdown = Math.max(maxDrawdown, peak - equity);
        }

        return maxDrawdown;
    }

    /**
     * Calcula a maior sequência de trades perdedores consecutivos.
     *
     * @param results lista de resultados em ordem cronológica
     * @return quantidade máxima de perdas consecutivas
     */
    private static int maxConsecutiveLosses(List<TradeResult> results) {
        int max = 0;
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
