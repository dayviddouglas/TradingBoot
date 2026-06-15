package com.github.dayviddouglas.TradingBot.backtest.output;

import com.github.dayviddouglas.TradingBot.backtest.result.BacktestReport;

import java.util.List;

/**
 * Responsável por formatar e exibir relatórios de backtest no console.
 *
 * Recebe a lista de {@link BacktestReport} produzida pelo {@code BacktestRunner},
 * imprime uma tabela consolidada com métricas por símbolo/estratégia e exibe
 * recomendações operacionais baseadas na classificação de cada relatório.
 *
 * Esta é uma classe utilitária final e não instanciável.
 */
public final class BacktestReportPrinter {

    private BacktestReportPrinter() {
    }

    /**
     * Imprime o relatório consolidado de todos os backtests executados.
     * Exibe uma tabela com trades, win rate, expectancy e profit factor por linha,
     * seguida de totais globais, contagem por classificação e recomendações operacionais.
     *
     * @param reports lista de relatórios produzidos pelo {@code BacktestRunner}
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

    /**
     * Imprime o cabeçalho da tabela com os nomes das colunas.
     */
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

    /**
     * Imprime uma linha da tabela com as métricas do relatório informado.
     *
     * @param report relatório de backtest de um símbolo ou estratégia
     */
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

    /**
     * Imprime o rodapé da tabela com os totais globais e a contagem por classificação.
     *
     * @param totals       acumulador com os totais calculados sobre todos os relatórios
     * @param totalReports quantidade total de relatórios processados
     */
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

    /**
     * Imprime recomendações operacionais baseadas na classificação de cada relatório.
     * {@code APPROVED} indica pronto para operar; {@code WEAK} indica monitoramento apenas;
     * {@code REJECTED} indica que o símbolo não deve ser operado.
     *
     * @param reports lista de relatórios com classificação calculada
     */
    private static void printRecommendations(List<BacktestReport> reports) {
        System.out.println();
        System.out.println("RECOMMENDATIONS:");

        for (BacktestReport report : reports) {
            String line = switch (report.classification()) {
                case "APPROVED" -> "  ✅ %s: Ready to trade".formatted(report.symbol());
                case "WEAK"     -> "  ⚠️  %s: Monitor only".formatted(report.symbol());
                case "REJECTED" -> "  ❌ %s: Do NOT trade".formatted(report.symbol());
                default         -> "  ❓ %s: No data".formatted(report.symbol());
            };
            System.out.println(line);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Utilitários
    // ═══════════════════════════════════════════════════════════════

    /**
     * Trunca o valor para o comprimento máximo informado, adicionando {@code "..."}
     * quando o corte for necessário.
     *
     * @param value valor a truncar; {@code null} retorna string vazia
     * @param max   comprimento máximo permitido
     * @return valor truncado ou original se já dentro do limite
     */
    private static String truncate(String value, int max) {
        if (value == null) return "";
        if (value.length() <= max) return value;
        return value.substring(0, max - 3) + "...";
    }

    // ═══════════════════════════════════════════════════════════════
    // Totalizador interno
    // ═══════════════════════════════════════════════════════════════

    /**
     * Acumula os totais consolidados de todos os relatórios processados.
     * Não é thread-safe — utilizado apenas de forma sequencial em {@link #printConsolidated}.
     */
    private static final class ConsolidatedTotals {

        int totalTrades = 0;
        int totalWins   = 0;
        int approved    = 0;
        int weak        = 0;
        int rejected    = 0;

        /**
         * Acumula os dados do relatório informado nos totais consolidados.
         *
         * @param report relatório a ser acumulado
         */
        void accumulate(BacktestReport report) {
            totalTrades += report.totalTrades();
            totalWins   += report.wins();

            switch (report.classification()) {
                case "APPROVED" -> approved++;
                case "WEAK"     -> weak++;
                case "REJECTED" -> rejected++;
            }
        }
    }
}