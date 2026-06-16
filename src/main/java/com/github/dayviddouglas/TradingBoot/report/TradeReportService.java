package com.github.dayviddouglas.TradingBoot.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Responsável pela persistência de relatórios operacionais diários de trades.
 *
 * Gera três arquivos por dia em um subdiretório separado por data dentro de
 * {@code data/reports/{date}/}:
 * <ul>
 *   <li>{@code trades_{date}.csv} — linha por operação com todos os campos</li>
 *   <li>{@code trades_{date}.json} — array de operações para processamento programático</li>
 *   <li>{@code daily_summary_{date}.json} — resumo consolidado com métricas globais,
 *       performance por símbolo e breakdown por regime de mercado</li>
 * </ul>
 *
 * O breakdown por regime ({@code regimePerformance}) permite avaliar se o sistema
 * apresenta edge diferente em {@code TRENDING}, {@code RANGING} e {@code CHOPPY},
 * validando empiricamente a hipótese central do projeto de que estratégias de reversão
 * têm melhor desempenho em regime {@code RANGING}.
 *
 * Métricas calculadas por regime:
 * <ul>
 *   <li>{@code trades}, {@code wins}, {@code losses}: contagens</li>
 *   <li>{@code winRate}: percentual de acertos</li>
 *   <li>{@code totalProfit}: resultado financeiro acumulado</li>
 *   <li>{@code expectancy}: {@code totalProfit / totalTrades} — mais robusta que winRate isolado</li>
 * </ul>
 *
 * O método {@link #record} é {@code synchronized} para proteger contra escritas
 * concorrentes de múltiplos ativos fechando contratos quase simultaneamente.
 * Todos os timestamps são convertidos para horário de Brasília na persistência;
 * UTC é mantido como referência técnica nos campos {@code *Utc}.
 */
@Service
public class TradeReportService {

    /** Fuso horário utilizado para exibição de timestamps nos relatórios. */
    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    /** Diretório raiz de saída de todos os arquivos de relatório. */
    private static final Path REPORT_DIR = Path.of("data", "reports");

    private final ObjectMapper mapper;

    /**
     * Inicializa o serviço com {@link ObjectMapper} configurado para saída indentada.
     */
    public TradeReportService() {
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Persiste uma operação finalizada nos três arquivos de relatório do dia
     * e regenera o resumo diário com os dados atualizados.
     *
     * @param entry registro completo da operação finalizada
     * @throws IllegalStateException se ocorrer falha na escrita dos arquivos
     */
    public synchronized void record(TradeReportEntry entry) {
        try {
            LocalDate date    = entry.exitTimestamp().atZone(ZONE).toLocalDate();
            Path      dateDir = resolveDateDir(date);

            Files.createDirectories(dateDir);

            writeCsv(date, dateDir, entry);
            writeJson(date, dateDir, entry);
            writeDailySummary(date, dateDir);

        } catch (Exception e) {
            throw new IllegalStateException("Failed to record trade report", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Resolução de diretório por data
    // ═══════════════════════════════════════════════════════════════

    /**
     * Resolve o subdiretório de relatórios para a data informada.
     * Os arquivos de cada dia são organizados em {@code data/reports/{date}/}.
     *
     * @param date data do relatório no fuso de Brasília
     * @return caminho do diretório correspondente à data
     */
    private Path resolveDateDir(LocalDate date) {
        return REPORT_DIR.resolve(date.toString());
    }

    // ═══════════════════════════════════════════════════════════════
    // CSV
    // ═══════════════════════════════════════════════════════════════

    /**
     * Acrescenta a operação ao arquivo CSV do dia.
     * Quando o arquivo não existe, o cabeçalho é gerado antes da primeira linha de dados.
     * Os campos de estratégias são separados por {@code |} dentro da célula CSV.
     *
     * @param date    data da operação no fuso de Brasília
     * @param dateDir diretório de saída correspondente à data
     * @param entry   registro da operação a ser persistido
     * @throws IOException se ocorrer falha na escrita do arquivo
     */
    private void writeCsv(LocalDate date, Path dateDir,
                          TradeReportEntry entry) throws IOException {
        Path csvFile = dateDir.resolve("trades_" + date + ".csv");

        boolean exists = Files.exists(csvFile);

        List<String> lines = new ArrayList<>();

        ZonedDateTime entryBrasilia = entry.entryTimestamp().atZone(ZONE);
        ZonedDateTime exitBrasilia  = entry.exitTimestamp().atZone(ZONE);

        if (!exists) {
            lines.add("entryTimestampUtc,entryTimestampBrasilia,entryDateBrasilia,entryTimeBrasilia," +
                    "exitTimestampUtc,exitTimestampBrasilia,exitDateBrasilia,exitTimeBrasilia," +
                    "symbol,decisionMode,strategy,decisionStrategies,regime,signalType," +
                    "stake,currency,duration,durationUnit,durationMinutesReal," +
                    "profit,payout,roiPct,result,contractId");
        }

        lines.add(String.join(",",
                safe(entry.entryTimestamp().toString()),
                safe(entryBrasilia.toOffsetDateTime().toString()),
                safe(entryBrasilia.toLocalDate().toString()),
                safe(entryBrasilia.toLocalTime().toString()),
                safe(entry.exitTimestamp().toString()),
                safe(exitBrasilia.toOffsetDateTime().toString()),
                safe(exitBrasilia.toLocalDate().toString()),
                safe(exitBrasilia.toLocalTime().toString()),
                safe(entry.symbol()),
                safe(entry.decisionMode()),
                safe(entry.strategy()),
                safe(String.join("|",
                        entry.decisionStrategies() != null
                                ? entry.decisionStrategies()
                                : List.of())),
                safe(entry.regime()),
                safe(entry.signalType()),
                format(entry.stake()),
                safe(entry.currency()),
                String.valueOf(entry.duration()),
                safe(entry.durationUnit()),
                format(entry.durationMinutesReal()),
                format(entry.profit()),
                format(entry.payout()),
                format(entry.roiPct()),
                safe(entry.result()),
                String.valueOf(entry.contractId())
        ));

        Files.write(
                csvFile,
                lines,
                exists
                        ? new StandardOpenOption[]{StandardOpenOption.APPEND}
                        : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND}
        );
    }

    // ═══════════════════════════════════════════════════════════════
    // JSON
    // ═══════════════════════════════════════════════════════════════

    /**
     * Acrescenta a operação ao arquivo JSON do dia.
     * Carrega as entradas existentes, adiciona a nova e regrava o arquivo completo.
     *
     * @param date    data da operação no fuso de Brasília
     * @param dateDir diretório de saída correspondente à data
     * @param entry   registro da operação a ser persistido
     * @throws IOException se ocorrer falha na leitura ou escrita do arquivo
     */
    private void writeJson(LocalDate date, Path dateDir,
                           TradeReportEntry entry) throws IOException {
        Path jsonFile = dateDir.resolve("trades_" + date + ".json");

        List<Map<String, Object>> entries = new ArrayList<>();
        if (Files.exists(jsonFile)) {
            Map[] existing = mapper.readValue(jsonFile.toFile(), Map[].class);
            for (Map existingEntry : existing) {
                @SuppressWarnings("unchecked")
                Map<String, Object> casted = (Map<String, Object>) existingEntry;
                entries.add(casted);
            }
        }

        entries.add(toJsonMap(entry));

        mapper.writeValue(jsonFile.toFile(), entries);
    }

    // ═══════════════════════════════════════════════════════════════
    // Daily Summary
    // ═══════════════════════════════════════════════════════════════

    /**
     * Regenera o resumo diário consolidado a partir do arquivo JSON do dia.
     * Calcula métricas globais, performance por símbolo e breakdown por regime.
     *
     * O campo {@code regimePerformance} agrega por regime o total de trades,
     * wins, losses, winRate, totalProfit e expectancy ({@code totalProfit / totalTrades}).
     * Quando o campo {@code regime} de uma entrada está vazio ou ausente, é tratado
     * como {@code "CHOPPY"} por ser o regime padrão conservador do sistema.
     *
     * @param date    data do relatório no fuso de Brasília
     * @param dateDir diretório de saída correspondente à data
     * @throws IOException se ocorrer falha na leitura ou escrita dos arquivos
     */
    private void writeDailySummary(LocalDate date, Path dateDir) throws IOException {
        Path jsonFile = dateDir.resolve("trades_" + date + ".json");
        if (!Files.exists(jsonFile)) return;

        Map[] entries = mapper.readValue(jsonFile.toFile(), Map[].class);

        int    totalTrades = entries.length;
        long   wins        = 0;
        long   losses      = 0;
        double totalProfit = 0.0;

        Map<String, Integer> tradesBySymbol  = new LinkedHashMap<>();
        Map<String, Double>  profitBySymbol  = new LinkedHashMap<>();
        Map<String, Integer> winsBySymbol    = new LinkedHashMap<>();

        // Acumuladores para o breakdown por regime
        Map<String, Integer> tradesByRegime  = new LinkedHashMap<>();
        Map<String, Integer> winsByRegime    = new LinkedHashMap<>();
        Map<String, Double>  profitByRegime  = new LinkedHashMap<>();

        for (Map entry : entries) {
            String result = String.valueOf(entry.getOrDefault("result", ""));
            String symbol = String.valueOf(entry.getOrDefault("symbol", ""));
            String regime = String.valueOf(entry.getOrDefault("regime", "CHOPPY"));

            // Entradas sem regime explícito são tratadas como CHOPPY
            if (regime.isBlank()) regime = "CHOPPY";

            double profit = toDouble(entry.get("profit"));

            if ("WIN".equalsIgnoreCase(result))  wins++;
            if ("LOSS".equalsIgnoreCase(result)) losses++;

            totalProfit += profit;

            // Acumulação por símbolo
            tradesBySymbol.merge(symbol, 1, Integer::sum);
            profitBySymbol.merge(symbol, profit, Double::sum);
            if ("WIN".equalsIgnoreCase(result)) {
                winsBySymbol.merge(symbol, 1, Integer::sum);
            }

            // Acumulação por regime
            tradesByRegime.merge(regime, 1, Integer::sum);
            profitByRegime.merge(regime, profit, Double::sum);
            if ("WIN".equalsIgnoreCase(result)) {
                winsByRegime.merge(regime, 1, Integer::sum);
            }
        }

        double winRate = totalTrades > 0 ? (wins * 100.0 / totalTrades) : 0.0;

        // Win rate por símbolo
        Map<String, Double> winRateBySymbol = new LinkedHashMap<>();
        for (String symbol : tradesBySymbol.keySet()) {
            int tradeCount = tradesBySymbol.getOrDefault(symbol, 0);
            int symbolWins = winsBySymbol.getOrDefault(symbol, 0);
            winRateBySymbol.put(symbol,
                    tradeCount > 0 ? (symbolWins * 100.0 / tradeCount) : 0.0);
        }

        // Métricas derivadas por regime
        Map<String, Map<String, Object>> regimePerformance = new LinkedHashMap<>();
        for (String regime : tradesByRegime.keySet()) {
            int    regimeTrades     = tradesByRegime.getOrDefault(regime, 0);
            int    regimeWins       = winsByRegime.getOrDefault(regime, 0);
            double regimeProfit     = profitByRegime.getOrDefault(regime, 0.0);
            double regimeWinRate    = regimeTrades > 0
                    ? (regimeWins * 100.0 / regimeTrades) : 0.0;
            // Expectancy incorpora tanto a frequência de acertos quanto o payoff médio
            double regimeExpectancy = regimeTrades > 0
                    ? (regimeProfit / regimeTrades) : 0.0;

            Map<String, Object> regimeStats = new LinkedHashMap<>();
            regimeStats.put("trades",      regimeTrades);
            regimeStats.put("wins",        regimeWins);
            regimeStats.put("losses",      regimeTrades - regimeWins);
            regimeStats.put("winRate",     regimeWinRate);
            regimeStats.put("totalProfit", regimeProfit);
            regimeStats.put("expectancy",  regimeExpectancy);

            regimePerformance.put(regime, regimeStats);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("dateBrasilia",        date.toString());
        summary.put("generatedAtUtc",       Instant.now().toString());
        summary.put("generatedAtBrasilia",
                Instant.now().atZone(ZONE).toOffsetDateTime().toString());
        summary.put("timeZone",             ZONE.toString());
        summary.put("totalTrades",          totalTrades);
        summary.put("wins",                 wins);
        summary.put("losses",               losses);
        summary.put("winRate",              winRate);
        summary.put("totalProfit",          totalProfit);
        summary.put("tradesBySymbol",       tradesBySymbol);
        summary.put("profitBySymbol",       profitBySymbol);
        summary.put("winRateBySymbol",      winRateBySymbol);
        summary.put("regimePerformance",    regimePerformance);

        Path summaryFile = dateDir.resolve("daily_summary_" + date + ".json");
        mapper.writeValue(summaryFile.toFile(), summary);
    }

    // ═══════════════════════════════════════════════════════════════
    // Conversão e utilitários
    // ═══════════════════════════════════════════════════════════════

    /**
     * Converte um {@link TradeReportEntry} para mapa para serialização JSON.
     * Inclui timestamps em UTC e no fuso de Brasília, separados em data e hora.
     *
     * @param entry registro da operação
     * @return mapa com todos os campos prontos para serialização JSON
     */
    private Map<String, Object> toJsonMap(TradeReportEntry entry) {
        ZonedDateTime entryBrasilia = entry.entryTimestamp().atZone(ZONE);
        ZonedDateTime exitBrasilia  = entry.exitTimestamp().atZone(ZONE);

        Map<String, Object> map = new LinkedHashMap<>();

        map.put("entryTimestampUtc",      entry.entryTimestamp().toString());
        map.put("entryTimestampBrasilia", entryBrasilia.toOffsetDateTime().toString());
        map.put("entryDateBrasilia",      entryBrasilia.toLocalDate().toString());
        map.put("entryTimeBrasilia",      entryBrasilia.toLocalTime().toString());

        map.put("exitTimestampUtc",       entry.exitTimestamp().toString());
        map.put("exitTimestampBrasilia",  exitBrasilia.toOffsetDateTime().toString());
        map.put("exitDateBrasilia",       exitBrasilia.toLocalDate().toString());
        map.put("exitTimeBrasilia",       exitBrasilia.toLocalTime().toString());

        map.put("timeZone",              ZONE.toString());

        map.put("symbol",                entry.symbol());
        map.put("decisionMode",          entry.decisionMode());
        map.put("strategy",              entry.strategy());
        map.put("decisionStrategies",    entry.decisionStrategies());
        map.put("regime",                entry.regime());
        map.put("signalType",            entry.signalType());

        map.put("stake",                 entry.stake());
        map.put("currency",              entry.currency());
        map.put("duration",              entry.duration());
        map.put("durationUnit",          entry.durationUnit());
        map.put("durationMinutesReal",   entry.durationMinutesReal());

        map.put("profit",                entry.profit());
        map.put("payout",                entry.payout());
        map.put("roiPct",                entry.roiPct());
        map.put("result",                entry.result());
        map.put("contractId",            entry.contractId());

        return map;
    }

    /**
     * Escapa um valor de texto para uso em campo CSV, envolvendo em aspas duplas
     * e substituindo aspas internas por aspas simples.
     *
     * @param value valor a ser escapado; {@code null} retorna string vazia
     * @return valor formatado para CSV
     */
    private String safe(String value) {
        if (value == null) return "";
        return "\"" + value.replace("\"", "'") + "\"";
    }

    /**
     * Formata um valor decimal com 4 casas decimais usando ponto como separador.
     *
     * @param value valor a ser formatado
     * @return string formatada em locale US
     */
    private String format(double value) {
        return String.format(Locale.US, "%.4f", value);
    }

    /**
     * Converte um valor de {@code Object} para {@code double}.
     * Aceita {@link Number} diretamente ou representação textual numérica.
     * Retorna {@code 0.0} quando o valor for nulo ou não conversível.
     *
     * @param value valor a converter
     * @return valor como {@code double} ou {@code 0.0}
     */
    private double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(value.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return 0.0;
    }
}