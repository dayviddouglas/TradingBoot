package com.github.dayviddouglas.TradingBot.backtest;

import java.util.List;

/**
 * Responsável por imprimir relatórios de backtest no console.
 *
 * Extraído do BacktestRunner para respeitar SRP:
 * - BacktestRunner orquestra a execução
 * - BacktestReportPrinter formata e exibe os resultados
 *
 * Classe utilitária final (não instanciável):
 * - Sem estado mutável (thread-safe)
 * - Métodos estáticos acessíveis diretamente
 */
public final class BacktestReportPrinter {

    private BacktestReportPrinter() {
    }

    /**
     * Imprime o relatório consolidado de todos os backtests executados.
     *
     * @param reports lista de relatórios a consolidar
     */
    public static void printConsolidated(List<BacktestReport> reports) {
        if (reports.isEmpty()) {
            System.out.println("Nenhum relatório gerado.");
            return;
        }

        printHeader();

        ConsolidatedTotals totals = new ConsolidatedTotals();

        for (BacktestReport report : reports) {
            printRow(report);
            totals.accumulate(report);
        }

        printFooter(totals, reports.size());
        printRecommendations(reports);
    }

    // ═══════════════════════════════════════════════════════════════
    // Impressão da tabela
    // ═══════════════════════════════════════════════════════════════

    private static void printHeader() {
        System.out.println(
                "╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println(
                "║                     CONSOLIDATED BACKTEST REPORT                        ║");
        System.out.println(
                "╠══════════════════════════════════════╦════════╦════════╦═══════════╦═══════════╦═══════════╣");
        System.out.println(
                "║ SYMBOL / STRATEGY                    ║ TRADES ║ WIN %  ║ EXPECT.   ║ P.FACTOR  ║ RESULT    ║");
        System.out.println(
                "╠══════════════════════════════════════╬════════╬════════╬═══════════╬═══════════╬═══════════╣");
    }

    private static void printRow(BacktestReport report) {
        System.out.printf(
                "║ %-36s ║ %6d ║ %5.1f%% ║ %+8.4fR ║ %9.2f ║ %-9s ║%n",
                truncate(report.symbol(), 36),
                report.totalTrades(),
                report.winRate(),
                report.expectancy(),
                report.profitFactor(),
                report.classification()
        );
    }

    private static void printFooter(ConsolidatedTotals totals, int totalReports) {
        System.out.println(
                "╠══════════════════════════════════════╬════════╬════════╬═══════════╬═══════════╬═══════════╣");

        double overallWinRate = totals.totalTrades > 0
                ? (totals.totalWins * 100.0 / totals.totalTrades)
                : 0.0;

        System.out.printf(
                "║ TOTAL                                ║ %6d ║ %5.1f%% ║           ║           ║           ║%n",
                totals.totalTrades, overallWinRate);

        System.out.println(
                "╠══════════════════════════════════════╩════════╩════════╩═══════════╩═══════════╩═══════════╣");
        System.out.printf(
                "║  APPROVED: %-3d | WEAK: %-3d | REJECTED: %-3d | TOTAL: %-3d                                ║%n",
                totals.approved, totals.weak, totals.rejected, totalReports);
        System.out.println(
                "╚══════════════════════════════════════════════════════════════════════════════════════════════╝");
    }

    private static void printRecommendations(List<BacktestReport> reports) {
        System.out.println();
        System.out.println("RECOMMENDATIONS:");

        for (BacktestReport report : reports) {
            String line = switch (report.classification()) {
                case "APPROVED" -> "  ✅ %s: Ready to trade".formatted(report.symbol());
                case "WEAK" -> "  ⚠️  %s: Monitor only".formatted(report.symbol());
                case "REJECTED" -> "  ❌ %s: Do NOT trade".formatted(report.symbol());
                default -> "  ❓ %s: No data".formatted(report.symbol());
            };
            System.out.println(line);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Utilitários
    // ═══════════════════════════════════════════════════════════════

    private static String truncate(String value, int max) {
        if (value == null) return "";
        if (value.length() <= max) return value;
        return value.substring(0, max - 3) + "...";
    }

    // ═══════════════════════════════════════════════════════════════
    // Totalizador interno
    // ═══════════════════════════════════════════════════════════════

    private static final class ConsolidatedTotals {

        int totalTrades = 0;
        int totalWins = 0;
        int approved = 0;
        int weak = 0;
        int rejected = 0;

        void accumulate(BacktestReport report) {
            totalTrades += report.totalTrades();
            totalWins += report.wins();

            switch (report.classification()) {
                case "APPROVED" -> approved++;
                case "WEAK" -> weak++;
                case "REJECTED" -> rejected++;
            }
        }
    }
}
