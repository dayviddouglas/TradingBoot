package com.github.dayviddouglas.TradingBot.backtest.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dayviddouglas.TradingBot.backtest.result.BacktestReport;
import com.github.dayviddouglas.TradingBot.backtest.output.BacktestReportPrinter;
import com.github.dayviddouglas.TradingBot.config.strategy.*;
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
 * Orquestra a execução do backtest para todos os profiles configurados no strategies.json.
 *
 * Para cada profile, carrega o histórico de candles do diretório configurado em
 * {@link BacktestConfig}, constrói as estratégias habilitadas via {@link StrategiesConfigLoader}
 * e delega a simulação ao {@link SimpleBacktester}. O {@link DecisionMode} de cada profile
 * é respeitado, garantindo consistência entre runtime e backtest.
 *
 * No modo {@link DecisionMode#SINGLE_STRATEGY}, cada estratégia habilitada gera um
 * {@link SimpleBacktester} e um {@link BacktestReport} independentes, permitindo
 * comparação isolada do edge de cada estratégia.
 *
 * Nos modos {@link DecisionMode#VOTING} e {@link DecisionMode#CONFLUENCE}, todas as
 * estratégias habilitadas são avaliadas em conjunto por um único {@link SimpleBacktester}.
 *
 * Ao final, os relatórios são impressos via {@link BacktestReportPrinter}.
 * A ferramenta é executada diretamente via {@link #main(String[])},
 * independente do contexto Spring.
 */
public class BacktestRunner {

    private static final Logger log = LoggerFactory.getLogger(BacktestRunner.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Ponto de entrada standalone do backtest.
     * Executa todos os profiles com a configuração padrão {@link BacktestConfig#DEFAULT}.
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
     * Carrega os profiles via {@link StrategiesConfigLoader}, executa cada um
     * e imprime o relatório consolidado no console ao final.
     *
     * @param config configuração do backtest com caminhos, payout e duração
     * @throws Exception se ocorrer falha no carregamento dos profiles ou do histórico
     */
    public void runAll(BacktestConfig config) throws Exception {
        StrategiesConfigLoader loader = new StrategiesConfigLoader(
                new StrategyBuilder(),
                new StrategiesProfileParser(
                        new StrategiesProfileValidator()
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

    /**
     * Itera sobre todos os profiles, executa o backtest de cada um e acumula os relatórios.
     * Cada relatório individual também é impresso no console imediatamente após a execução.
     *
     * @param loader   loader que constrói estratégias a partir do profile
     * @param config   configuração do backtest
     * @param profiles lista de profiles carregados do strategies.json
     * @return lista consolidada de todos os relatórios gerados
     */
    private List<BacktestReport> runAllProfiles(
            StrategiesConfigLoader loader,
            BacktestConfig config,
            List<StrategiesProfile> profiles
    ) {
        List<BacktestReport> reports = new ArrayList<>();

        for (StrategiesProfile profile : profiles) {
            List<BacktestReport> profileReports = runProfile(loader, config, profile);
            reports.addAll(profileReports);
            profileReports.forEach(r -> System.out.println(r.toPrettyReport()));
        }

        return reports;
    }

    /**
     * Executa o backtest de um único profile.
     * Carrega o histórico de candles, constrói as estratégias habilitadas e
     * cria os {@link SimpleBacktester} conforme o {@link DecisionMode} do profile.
     * Retorna lista vazia quando o histórico ou as estratégias não estiverem disponíveis.
     *
     * @param loader  loader que constrói as estratégias a partir do profile
     * @param config  configuração do backtest
     * @param profile profile do ativo a ser testado
     * @return lista de relatórios gerados para este profile
     */
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

    /**
     * Cria e executa os {@link SimpleBacktester} para o profile informado.
     * No modo {@link DecisionMode#SINGLE_STRATEGY}, cria um backtester por estratégia habilitada.
     * Nos demais modos, cria um único backtester com todas as estratégias.
     *
     * @param config     configuração do backtest com payout e duração padrão
     * @param profile    profile do ativo com o {@link DecisionMode} e parâmetros do engine
     * @param strategies estratégias habilitadas para este profile
     * @param bars       histórico de candles carregado
     * @return lista de relatórios gerados
     */
    private List<BacktestReport> buildAndRunBacktesters(
            BacktestConfig config,
            StrategiesProfile profile,
            List<TradingStrategy> strategies,
            List<Bar> bars
    ) {
        DecisionMode mode     = profile.getDecisionMode();
        int          maxBars  = profile.getMaxBars();
        int          duration = resolveDuration(config, profile);

        List<BacktestReport> reports = new ArrayList<>();

        if (mode == DecisionMode.SINGLE_STRATEGY) {
            // Cria um backtester isolado por estratégia para comparação individual
            for (TradingStrategy strategy : strategies) {
                String          backtestId = profile.getSymbol() + " [" + strategy.name() + "]";
                SimpleBacktester backtester = new SimpleBacktester(
                        backtestId, List.of(strategy),
                        maxBars, config.profitPayout(),
                        duration, DecisionMode.SINGLE_STRATEGY, profile);
                reports.add(backtester.run(bars));
            }
        } else {
            // VOTING e CONFLUENCE avaliam todas as estratégias em conjunto
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

    /**
     * Carrega o histórico de candles do arquivo JSON correspondente ao símbolo.
     * O arquivo é esperado no formato {@code {dataDir}/{symbol}_60.json}.
     * Retorna lista vazia quando o arquivo não existir ou ocorrer falha na leitura.
     *
     * @param dataDir diretório base dos arquivos de histórico
     * @param symbol  símbolo do ativo
     * @return lista de candles ordenada cronologicamente ou lista vazia
     */
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

    /**
     * Lê e parseia o arquivo JSON de histórico extraindo o array {@code candles}.
     * Candles com campos inválidos são silenciosamente descartados.
     * O resultado é ordenado cronologicamente por timestamp.
     *
     * @param file caminho do arquivo JSON de histórico
     * @return lista de {@link Bar} válidos ordenada cronologicamente
     * @throws Exception se ocorrer falha na leitura ou deserialização do JSON
     */
    private List<Bar> parseBars(Path file) throws Exception {
        JsonNode root        = mapper.readTree(Files.readString(file));
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

    /**
     * Converte um nó JSON individual em um {@link Bar}.
     * Retorna {@code null} quando o epoch for inválido ou qualquer campo OHLC
     * não for um número finito, descartando silenciosamente o candle corrompido.
     * O volume é sempre {@code 0.0} pois a API Deriv não fornece volume real.
     *
     * @param node nó JSON representando um candle
     * @return {@link Bar} construído ou {@code null} se os dados forem inválidos
     */
    private Bar parseBar(JsonNode node) {
        long   epoch = node.path("epoch").asLong(0);
        double open  = node.path("open").asDouble(Double.NaN);
        double high  = node.path("high").asDouble(Double.NaN);
        double low   = node.path("low").asDouble(Double.NaN);
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

    /**
     * Resolve a duração do contrato para o backtest.
     * Prioriza o valor configurado no {@code TradeConfig} do profile;
     * utiliza o valor padrão do {@link BacktestConfig} quando o trade config não estiver disponível.
     *
     * @param config  configuração do backtest com duração padrão
     * @param profile profile do ativo com configuração de trade opcional
     * @return duração em candles a ser utilizada na simulação
     */
    private int resolveDuration(BacktestConfig config, StrategiesProfile profile) {
        return profile.getTrade() != null
                ? profile.getTrade().getDuration()
                : config.tradeDuration();
    }
}