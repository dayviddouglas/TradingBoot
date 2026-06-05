package com.github.dayviddouglas.TradingBot.backtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dayviddouglas.TradingBot.config.StrategiesConfigLoader;
import com.github.dayviddouglas.TradingBot.config.StrategiesProfile;
import com.github.dayviddouglas.TradingBot.engine.decision.DecisionMode;
import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.strategy.TradingStrategy;
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
 * Runner de backtest alinhado ao runtime.
 *
 * Responsabilidade única: orquestrar a execução do backtest
 * para todos os profiles do strategies.json.
 *
 * Após refatoração, delega para:
 * - StrategiesConfigLoader    → carrega profiles e constrói estratégias
 * - SimpleBacktester          → simula trades por profile
 * - BacktestMetricsCalculator → calcula métricas (via SimpleBacktester)
 * - BacktestReportPrinter     → imprime relatórios no console
 * - BacktestConfig            → centraliza configuração
 *
 * O decisionMode de cada profile controla o modo do backtest,
 * garantindo consistência entre runtime e backtest.
 */
public class BacktestRunner {

    private static final Logger log = LoggerFactory.getLogger(BacktestRunner.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Ponto de entrada standalone do backtest.
     */
    public static void main(String[] args) {
        BacktestRunner runner = new BacktestRunner();
        try {
            runner.runAll(BacktestConfig.DEFAULT);
        } catch (Exception e) {
            log.error("BacktestRunner error", e);
        }
    }

    /**
     * Executa o backtest para todos os profiles do strategies.json.
     *
     * @param config configuração do backtest
     */
    public void runAll(BacktestConfig config) throws Exception {
        StrategiesConfigLoader loader = new StrategiesConfigLoader(
                new com.github.dayviddouglas.TradingBot.config.StrategyBuilder(),
                new com.github.dayviddouglas.TradingBot.config.StrategiesProfileParser(
                        new com.github.dayviddouglas.TradingBot.config.StrategiesProfileValidator()
                )
        );

        List<StrategiesProfile> profiles = loader.loadProfiles(config.strategiesFile());

        if (profiles == null || profiles.isEmpty()) {
            log.warn("No profiles found in: {}", config.strategiesFile());
            return;
        }

        List<BacktestReport> reports = runAllProfiles(loader, config, profiles);

        BacktestReportPrinter.printConsolidated(reports);
    }

    // ═══════════════════════════════════════════════════════════════
    // Execução por profile
    // ═══════════════════════════════════════════════════════════════

    private List<BacktestReport> runAllProfiles(
            StrategiesConfigLoader loader,
            BacktestConfig config,
            List<StrategiesProfile> profiles
    ) {
        List<BacktestReport> reports = new ArrayList<>();

        for (StrategiesProfile profile : profiles) {
            List<BacktestReport> profileReports =
                    runProfile(loader, config, profile);
            reports.addAll(profileReports);
            profileReports.forEach(r -> System.out.println(r.toPrettyReport()));
        }

        return reports;
    }

    private List<BacktestReport> runProfile(
            StrategiesConfigLoader loader,
            BacktestConfig config,
            StrategiesProfile profile
    ) {
        String symbol = profile.getSymbol();

        List<Bar> bars = loadBars(config.dataDir(), symbol);
        if (bars.isEmpty()) {
            log.warn("BACKTEST SKIP | symbol={} | reason=no bars loaded", symbol);
            return List.of();
        }

        List<TradingStrategy> strategies = loader.buildStrategies(profile);
        if (strategies.isEmpty()) {
            log.warn("BACKTEST SKIP | symbol={} | reason=no enabled strategies", symbol);
            return List.of();
        }

        log.info("BACKTEST RUNNING | symbol={} | bars={} | mode={} | strategies={}",
                symbol, bars.size(), profile.getDecisionMode(),
                strategies.stream().map(TradingStrategy::name).toList());

        return buildAndRunBacktesters(config, profile, strategies, bars);
    }

    private List<BacktestReport> buildAndRunBacktesters(
            BacktestConfig config,
            StrategiesProfile profile,
            List<TradingStrategy> strategies,
            List<Bar> bars
    ) {
        DecisionMode mode = profile.getDecisionMode();
        int maxBars = profile.getMaxBars();
        int duration = resolveDuration(config, profile);

        List<BacktestReport> reports = new ArrayList<>();

        if (mode == DecisionMode.SINGLE_STRATEGY) {
            for (TradingStrategy strategy : strategies) {
                String backtestId = profile.getSymbol() + " [" + strategy.name() + "]";
                SimpleBacktester backtester = new SimpleBacktester(
                        backtestId, List.of(strategy),
                        maxBars, config.profitPayout(),
                        duration, DecisionMode.SINGLE_STRATEGY, profile);
                reports.add(backtester.run(bars));
            }
        } else {
            SimpleBacktester backtester = new SimpleBacktester(
                    profile.getSymbol(), strategies,
                    maxBars, config.profitPayout(),
                    duration, mode, profile);
            reports.add(backtester.run(bars));
        }

        return reports;
    }

    // ═══════════════════════════════════════════════════════════════
    // Carregamento de histórico
    // ═══════════════════════════════════════════════════════════════

    private List<Bar> loadBars(String dataDir, String symbol) {
        Path file = Paths.get(dataDir, symbol + "_60.json");

        if (!Files.exists(file)) {
            log.warn("HISTORY NOT FOUND | symbol={} | file={}", symbol,
                    file.toAbsolutePath());
            return List.of();
        }

        try {
            return parseBars(file);
        } catch (Exception e) {
            log.error("HISTORY LOAD ERROR | symbol={} | file={}", symbol,
                    file.toAbsolutePath(), e);
            return List.of();
        }
    }

    private List<Bar> parseBars(Path file) throws Exception {
        JsonNode root = mapper.readTree(Files.readString(file));
        JsonNode candlesNode = root.path("candles");

        if (!candlesNode.isArray()) {
            log.warn("INVALID HISTORY FORMAT | file={}", file.toAbsolutePath());
            return List.of();
        }

        List<Bar> bars = new ArrayList<>();
        for (JsonNode node : candlesNode) {
            Bar bar = parseBar(node);
            if (bar != null) bars.add(bar);
        }

        bars.sort(Comparator.comparing(Bar::timestamp));
        return bars;
    }

    private Bar parseBar(JsonNode node) {
        long epoch = node.path("epoch").asLong(0);
        double open = node.path("open").asDouble(Double.NaN);
        double high = node.path("high").asDouble(Double.NaN);
        double low = node.path("low").asDouble(Double.NaN);
        double close = node.path("close").asDouble(Double.NaN);

        if (epoch <= 0 || !Double.isFinite(open) || !Double.isFinite(high)
                || !Double.isFinite(low) || !Double.isFinite(close)) {
            return null;
        }

        return new Bar(Instant.ofEpochSecond(epoch), open, high, low, close, 0.0);
    }

    // ═══════════════════════════════════════════════════════════════
    // Utilitários
    // ═══════════════════════════════════════════════════════════════

    private int resolveDuration(BacktestConfig config, StrategiesProfile profile) {
        return profile.getTrade() != null
                ? profile.getTrade().getDuration()
                : config.tradeDuration();
    }
}