package com.github.dayviddouglas.TradingBot.report;

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
 * Serviço responsável pela persistência de relatórios operacionais diários.
 *
 * Atualização v5:
 * - Adicionado breakdown de performance segmentado por regime de mercado.
 * - Métricas por regime: trades, wins, losses, winRate, totalProfit, expectancy.
 *
 * Fundamentação:
 * Com a introdução do módulo de monitoramento de regime (Regime Switching),
 * torna-se essencial avaliar se o sistema apresenta edge diferente em
 * TRENDING vs RANGING vs CHOPPY.
 *
 * O breakdown por regime permite:
 * - Validar hipótese central do projeto (mean reversion funciona melhor em RANGING)
 * - Detectar degradação de performance em determinados contextos
 * - Ajustar pesos do StrategyWeightProfile com base em evidência empírica
 *
 * Expectancy por regime:
 * expectancy = totalProfit / totalTrades
 * Métrica mais robusta que winRate isoladamente, pois incorpora payoff.
 */
@Service
public class TradeReportService {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private static final Path REPORT_DIR = Path.of("data", "reports");

    private final ObjectMapper mapper;

    public TradeReportService() {
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public synchronized void record(TradeReportEntry entry) {
        try {
            Files.createDirectories(REPORT_DIR);

            LocalDate date = entry.exitTimestamp().atZone(ZONE).toLocalDate();

            writeCsv(date, entry);
            writeJson(date, entry);
            writeDailySummary(date);

        } catch (Exception e) {
            throw new IllegalStateException("Failed to record trade report", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CSV (inalterado)
    // ═══════════════════════════════════════════════════════════════

    private void writeCsv(LocalDate date, TradeReportEntry entry) throws IOException {
        Path csvFile = REPORT_DIR.resolve("trades_" + date + ".csv");

        boolean exists = Files.exists(csvFile);

        List<String> lines = new ArrayList<>();

        ZonedDateTime entryBrasilia = entry.entryTimestamp().atZone(ZONE);
        ZonedDateTime exitBrasilia = entry.exitTimestamp().atZone(ZONE);

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
                        entry.decisionStrategies() != null ? entry.decisionStrategies() : List.of())),
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
    // JSON (inalterado)
    // ═══════════════════════════════════════════════════════════════

    private void writeJson(LocalDate date, TradeReportEntry entry) throws IOException {
        Path jsonFile = REPORT_DIR.resolve("trades_" + date + ".json");

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
    // DAILY SUMMARY — ATUALIZADO (breakdown por regime)
    // ═══════════════════════════════════════════════════════════════

    private void writeDailySummary(LocalDate date) throws IOException {
        Path jsonFile = REPORT_DIR.resolve("trades_" + date + ".json");
        if (!Files.exists(jsonFile)) return;

        Map[] entries = mapper.readValue(jsonFile.toFile(), Map[].class);

        int totalTrades = entries.length;
        long wins = 0;
        long losses = 0;
        double totalProfit = 0.0;

        Map<String, Integer> tradesBySymbol = new LinkedHashMap<>();
        Map<String, Double> profitBySymbol = new LinkedHashMap<>();
        Map<String, Integer> winsBySymbol = new LinkedHashMap<>();

        // ── NOVO: métricas por regime ──
        Map<String, Integer> tradesByRegime = new LinkedHashMap<>();
        Map<String, Integer> winsByRegime = new LinkedHashMap<>();
        Map<String, Double> profitByRegime = new LinkedHashMap<>();

        for (Map entry : entries) {
            String result = String.valueOf(entry.getOrDefault("result", ""));
            String symbol = String.valueOf(entry.getOrDefault("symbol", ""));
            String regime = String.valueOf(entry.getOrDefault("regime", "CHOPPY"));

            double profit = toDouble(entry.get("profit"));

            if ("WIN".equalsIgnoreCase(result)) wins++;
            if ("LOSS".equalsIgnoreCase(result)) losses++;

            totalProfit += profit;

            // ── Por símbolo ──
            tradesBySymbol.merge(symbol, 1, Integer::sum);
            profitBySymbol.merge(symbol, profit, Double::sum);
            if ("WIN".equalsIgnoreCase(result)) {
                winsBySymbol.merge(symbol, 1, Integer::sum);
            }

            // ── NOVO: Por regime ──
            tradesByRegime.merge(regime, 1, Integer::sum);
            profitByRegime.merge(regime, profit, Double::sum);
            if ("WIN".equalsIgnoreCase(result)) {
                winsByRegime.merge(regime, 1, Integer::sum);
            }
        }

        double winRate = totalTrades > 0 ? (wins * 100.0 / totalTrades) : 0.0;

        Map<String, Double> winRateBySymbol = new LinkedHashMap<>();
        for (String symbol : tradesBySymbol.keySet()) {
            int tradeCount = tradesBySymbol.getOrDefault(symbol, 0);
            int symbolWins = winsBySymbol.getOrDefault(symbol, 0);
            winRateBySymbol.put(symbol,
                    tradeCount > 0 ? (symbolWins * 100.0 / tradeCount) : 0.0);
        }

        // ── NOVO: métricas derivadas por regime ──
        Map<String, Map<String, Object>> regimePerformance = new LinkedHashMap<>();
        for (String regime : tradesByRegime.keySet()) {
            int regimeTrades = tradesByRegime.getOrDefault(regime, 0);
            int regimeWins = winsByRegime.getOrDefault(regime, 0);
            double regimeProfit = profitByRegime.getOrDefault(regime, 0.0);

            double regimeWinRate = regimeTrades > 0
                    ? (regimeWins * 100.0 / regimeTrades)
                    : 0.0;

            double regimeExpectancy = regimeTrades > 0
                    ? (regimeProfit / regimeTrades)
                    : 0.0;

            Map<String, Object> regimeStats = new LinkedHashMap<>();
            regimeStats.put("trades", regimeTrades);
            regimeStats.put("wins", regimeWins);
            regimeStats.put("losses", regimeTrades - regimeWins);
            regimeStats.put("winRate", regimeWinRate);
            regimeStats.put("totalProfit", regimeProfit);
            regimeStats.put("expectancy", regimeExpectancy);

            regimePerformance.put(regime, regimeStats);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("dateBrasilia", date.toString());
        summary.put("generatedAtUtc", Instant.now().toString());
        summary.put("generatedAtBrasilia",
                Instant.now().atZone(ZONE).toOffsetDateTime().toString());
        summary.put("timeZone", ZONE.toString());
        summary.put("totalTrades", totalTrades);
        summary.put("wins", wins);
        summary.put("losses", losses);
        summary.put("winRate", winRate);
        summary.put("totalProfit", totalProfit);
        summary.put("tradesBySymbol", tradesBySymbol);
        summary.put("profitBySymbol", profitBySymbol);
        summary.put("winRateBySymbol", winRateBySymbol);

        // ── NOVO BLOCO ──
        summary.put("regimePerformance", regimePerformance);

        Path summaryFile = REPORT_DIR.resolve("daily_summary_" + date + ".json");
        mapper.writeValue(summaryFile.toFile(), summary);
    }

    // ═══════════════════════════════════════════════════════════════
    // Conversão e utilitários (inalterados)
    // ═══════════════════════════════════════════════════════════════

    private Map<String, Object> toJsonMap(TradeReportEntry entry) {
        ZonedDateTime entryBrasilia = entry.entryTimestamp().atZone(ZONE);
        ZonedDateTime exitBrasilia = entry.exitTimestamp().atZone(ZONE);

        Map<String, Object> map = new LinkedHashMap<>();

        map.put("entryTimestampUtc", entry.entryTimestamp().toString());
        map.put("entryTimestampBrasilia",
                entryBrasilia.toOffsetDateTime().toString());
        map.put("entryDateBrasilia",
                entryBrasilia.toLocalDate().toString());
        map.put("entryTimeBrasilia",
                entryBrasilia.toLocalTime().toString());

        map.put("exitTimestampUtc", entry.exitTimestamp().toString());
        map.put("exitTimestampBrasilia",
                exitBrasilia.toOffsetDateTime().toString());
        map.put("exitDateBrasilia",
                exitBrasilia.toLocalDate().toString());
        map.put("exitTimeBrasilia",
                exitBrasilia.toLocalTime().toString());

        map.put("timeZone", ZONE.toString());

        map.put("symbol", entry.symbol());
        map.put("decisionMode", entry.decisionMode());
        map.put("strategy", entry.strategy());
        map.put("decisionStrategies", entry.decisionStrategies());
        map.put("regime", entry.regime());
        map.put("signalType", entry.signalType());

        map.put("stake", entry.stake());
        map.put("currency", entry.currency());
        map.put("duration", entry.duration());
        map.put("durationUnit", entry.durationUnit());
        map.put("durationMinutesReal", entry.durationMinutesReal());

        map.put("profit", entry.profit());
        map.put("payout", entry.payout());
        map.put("roiPct", entry.roiPct());
        map.put("result", entry.result());
        map.put("contractId", entry.contractId());

        return map;
    }

    private String safe(String value) {
        if (value == null) return "";
        return "\"" + value.replace("\"", "'") + "\"";
    }

    private String format(double value) {
        return String.format(Locale.US, "%.4f", value);
    }

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