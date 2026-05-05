package com.github.dayviddouglas.TradingBot.backtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.strategy.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Runner com modo SINGLE_STRATEGY.
 */
public class BacktestRunner {
    private static final Logger log = LoggerFactory.getLogger(BacktestRunner.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String DEFAULT_DATA_DIR = "data/history";
    private static final String DEFAULT_STRATEGIES_FILE = "src/main/resources/configs/strategies.json";

    public static void main(String[] args) {
        String dataDir = DEFAULT_DATA_DIR;
        String strategiesFile = DEFAULT_STRATEGIES_FILE;

        // Para testes isolados, usar SINGLE_STRATEGY
        BacktestMode mode = BacktestMode.SINGLE_STRATEGY;

        BacktestRunner runner = new BacktestRunner();

        try {
            runner.runAll(dataDir, strategiesFile, mode);
        } catch (Exception e) {
            log.error("BacktestRunner error", e);
        }
    }

    public void runAll(String dataDir, String strategiesFile, BacktestMode mode) throws Exception {
        JsonNode root = mapper.readTree(Files.readString(Paths.get(strategiesFile)));
        JsonNode profiles = root.path("profiles");

        if (!profiles.isArray() || profiles.isEmpty()) {
            log.warn("No profiles found in strategies file: {}", strategiesFile);
            return;
        }

        List<SimpleBacktester.BacktestReport> reports = new ArrayList<>();

        for (JsonNode profile : profiles) {
            String symbol = profile.path("symbol").asText("").trim();
            if (symbol.isBlank()) continue;

            Path historyFile = Paths.get(dataDir, symbol + "_60.json");
            if (!Files.exists(historyFile)) {
                log.warn("History file not found for symbol={} | file={}", symbol, historyFile.toAbsolutePath());
                continue;
            }

            List<Bar> bars = loadBars(historyFile);

            log.info("BACKTEST RUNNER | symbol={} | loadedBars={} | file={}",
                    symbol, bars.size(), historyFile.toAbsolutePath());

            if (bars.isEmpty()) {
                log.warn("BACKTEST RUNNER | symbol={} | no bars loaded from file", symbol);
                continue;
            }

            List<TradingStrategy> strategies = buildStrategiesFromProfile(profile);

            log.info("BACKTEST RUNNER | symbol={} | strategies={}",
                    symbol,
                    strategies.stream().map(TradingStrategy::name).toList());

            if (strategies.isEmpty()) {
                log.warn("BACKTEST RUNNER | symbol={} | no enabled strategies in profile", symbol);
                continue;
            }

            // Em SINGLE_STRATEGY, testa uma estratégia por vez
            if (mode == BacktestMode.SINGLE_STRATEGY) {
                for (TradingStrategy strategy : strategies) {
                    SimpleBacktester backtester = new SimpleBacktester(
                            symbol + " [" + strategy.name() + "]",
                            List.of(strategy),
                            profile.path("engine").path("maxBars").asInt(1500),
                            0.95,
                            profile.path("trade").path("duration").asInt(15),
                            mode
                    );

                    SimpleBacktester.BacktestReport report = backtester.run(bars);
                    reports.add(report);

                    System.out.println(report.toPrettyReport());
                }
            } else {
                SimpleBacktester backtester = new SimpleBacktester(
                        symbol,
                        strategies,
                        profile.path("engine").path("maxBars").asInt(1500),
                        0.95,
                        profile.path("trade").path("duration").asInt(15),
                        mode
                );

                SimpleBacktester.BacktestReport report = backtester.run(bars);
                reports.add(report);

                System.out.println(report.toPrettyReport());
            }
        }

        printConsolidatedReport(reports);
    }

    private List<Bar> loadBars(Path file) throws Exception {
        JsonNode root = mapper.readTree(Files.readString(file));

        List<Bar> bars = new ArrayList<>();

        JsonNode candlesNode = root.path("candles");
        if (!candlesNode.isArray()) {
            log.warn("Invalid history format | file={} | field 'candles' is not an array", file.toAbsolutePath());
            return bars;
        }

        for (JsonNode node : candlesNode) {
            long epoch = node.path("epoch").asLong(0);
            double open = node.path("open").asDouble(Double.NaN);
            double high = node.path("high").asDouble(Double.NaN);
            double low = node.path("low").asDouble(Double.NaN);
            double close = node.path("close").asDouble(Double.NaN);

            if (epoch <= 0 || !Double.isFinite(open) || !Double.isFinite(high)
                    || !Double.isFinite(low) || !Double.isFinite(close)) {
                continue;
            }

            bars.add(new Bar(
                    Instant.ofEpochSecond(epoch),
                    open,
                    high,
                    low,
                    close,
                    0.0
            ));
        }

        bars.sort(Comparator.comparing(Bar::timestamp));
        return bars;
    }

    private List<TradingStrategy> buildStrategiesFromProfile(JsonNode profile) {
        List<TradingStrategy> list = new ArrayList<>();
        JsonNode strategies = profile.path("strategies");

        JsonNode emaRsi = strategies.path("emaRsi");
        if (emaRsi.path("enabled").asBoolean(false)) {
            list.add(new EmaRsiStrategy(
                    emaRsi.path("emaFast").asInt(50),
                    emaRsi.path("emaSlow").asInt(200),
                    emaRsi.path("rsiPeriod").asInt(14),
                    emaRsi.path("rsiBuyThreshold").asDouble(65.0),
                    emaRsi.path("rsiSellThreshold").asDouble(35.0)
            ));
        }

        JsonNode supportResistance = strategies.path("supportResistance");
        if (supportResistance.path("enabled").asBoolean(false)) {
            list.add(new SupportResistanceStrategy(
                    supportResistance.path("lookback").asInt(480),
                    supportResistance.path("tolerancePct").asDouble(0.5)
            ));
        }

        JsonNode pinBar = strategies.path("pinBar");
        if (pinBar.path("enabled").asBoolean(false)) {
            list.add(new PinBarStrategy(
                    pinBar.path("wickToBodyRatio").asDouble(3.5),
                    pinBar.path("maxOppositeWickToBody").asDouble(0.15),
                    pinBar.path("srLookback").asInt(480),
                    pinBar.path("tolerancePct").asDouble(0.5)
            ));
        }

        JsonNode breakout = strategies.path("breakout");
        if (breakout.path("enabled").asBoolean(false)) {
            list.add(new BreakoutStrategy(
                    breakout.path("lookback").asInt(180),
                    breakout.path("bufferPct").asDouble(0.0002)
            ));
        }

        JsonNode bollinger = strategies.path("bollingerMeanReversion");
        if (bollinger.path("enabled").asBoolean(false)) {
            list.add(new BollingerMeanReversionStrategy(
                    bollinger.path("period").asInt(20),
                    bollinger.path("stdDevMultiplier").asDouble(2.0),
                    bollinger.path("entryThreshold").asDouble(0.98),
                    bollinger.path("useRsiConfirmation").asBoolean(true),
                    bollinger.path("rsiPeriod").asInt(14),
                    bollinger.path("rsiOverbought").asDouble(70.0),
                    bollinger.path("rsiOversold").asDouble(30.0)
            ));
        }

        JsonNode keltner = strategies.path("keltnerChannel");
        if (keltner.path("enabled").asBoolean(false)) {
            list.add(new KeltnerChannelStrategy(
                    keltner.path("emaPeriod").asInt(20),
                    keltner.path("atrPeriod").asInt(14),
                    keltner.path("atrMultiplier").asDouble(2.5),
                    keltner.path("requireStrongCandle").asBoolean(true)
            ));
        }

        JsonNode donchian1 = strategies.path("donchianSystem1");
        if (donchian1.path("enabled").asBoolean(false)) {
            list.add(new DonchianBreakoutStrategy(
                    donchian1.path("entryPeriod").asInt(20),
                    donchian1.path("exitPeriod").asInt(10),
                    donchian1.path("useATRFilter").asBoolean(true),
                    donchian1.path("atrPeriod").asInt(14),
                    donchian1.path("minATRExpansion").asDouble(0.05)
            ));
        }

        JsonNode donchian2 = strategies.path("donchianSystem2");
        if (donchian2.path("enabled").asBoolean(false)) {
            list.add(new DonchianBreakoutStrategy(
                    donchian2.path("entryPeriod").asInt(55),
                    donchian2.path("exitPeriod").asInt(20),
                    donchian2.path("useATRFilter").asBoolean(true),
                    donchian2.path("atrPeriod").asInt(14),
                    donchian2.path("minATRExpansion").asDouble(0.10)
            ));
        }

        return list;
    }

    private void printConsolidatedReport(List<SimpleBacktester.BacktestReport> reports) {
        if (reports.isEmpty()) {
            System.out.println("Nenhum relatório gerado.");
            return;
        }

        System.out.println("╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                        CONSOLIDATED BACKTEST REPORT                     ║");
        System.out.println("╠══════════════════════════════════════╦════════╦════════╦═══════════╦═══════════╦═══════════╣");
        System.out.println("║ SYMBOL / STRATEGY                    ║ TRADES ║ WIN %  ║ EXPECT.   ║ P.FACTOR  ║ RESULT    ║");
        System.out.println("╠══════════════════════════════════════╬════════╬════════╬═══════════╬═══════════╬═══════════╣");

        int totalTrades = 0;
        int totalWins = 0;
        int approved = 0;
        int rejected = 0;
        int weak = 0;

        for (SimpleBacktester.BacktestReport r : reports) {
            String resultText = r.classification();

            if ("APPROVED".equals(resultText)) approved++;
            else if ("WEAK".equals(resultText)) weak++;
            else if ("REJECTED".equals(resultText)) rejected++;

            System.out.printf("║ %-36s ║ %6d ║ %5.1f%% ║ %+8.4fR ║ %9.2f ║ %-9s ║%n",
                    truncate(r.symbol(), 36),
                    r.totalTrades(),
                    r.winRate(),
                    r.expectancy(),
                    r.profitFactor(),
                    resultText);

            totalTrades += r.totalTrades();
            totalWins += r.wins();
        }

        System.out.println("╠══════════════════════════════════════╬════════╬════════╬═══════════╬═══════════╬═══════════╣");

        double overallWinRate = totalTrades > 0 ? (double) totalWins * 100.0 / totalTrades : 0.0;
        System.out.printf("║ TOTAL                                ║ %6d ║ %5.1f%% ║           ║           ║           ║%n",
                totalTrades, overallWinRate);

        System.out.println("╠══════════════════════════════════════╩════════╩════════╩═══════════╩═══════════╩═══════════╣");
        System.out.printf("║  APPROVED: %-3d | WEAK: %-3d | REJECTED: %-3d | TOTAL: %-3d                              ║%n",
                approved, weak, rejected, reports.size());
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════════════════════╝");

        System.out.println();
        System.out.println("RECOMMENDATIONS:");
        for (SimpleBacktester.BacktestReport r : reports) {
            switch (r.classification()) {
                case "APPROVED" ->
                        System.out.printf("  ✅ %s: Ready to trade%n", r.symbol());
                case "WEAK" ->
                        System.out.printf("  ⚠️ %s: Monitor only%n", r.symbol());
                case "REJECTED" ->
                        System.out.printf("  ❌ %s: Do NOT trade%n", r.symbol());
                default ->
                        System.out.printf("  ❓ %s: No data%n", r.symbol());
            }
        }
    }

    private String truncate(String value, int max) {
        if (value == null) return "";
        if (value.length() <= max) return value;
        return value.substring(0, max - 3) + "...";
    }
}