package com.github.dayviddouglas.TradingBot.bot;

import com.github.dayviddouglas.TradingBot.config.StrategiesConfigLoader;
import com.github.dayviddouglas.TradingBot.config.StrategiesProfile;
import com.github.dayviddouglas.TradingBot.engine.regime.MarketRegimeMonitor;
import com.github.dayviddouglas.TradingBot.engine.core.StrategyEngine;
import com.github.dayviddouglas.TradingBot.market.TickCandleAggregator;
import com.github.dayviddouglas.TradingBot.strategy.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Responsável por construir e armazenar os pipelines de cada ativo.
 *
 * Correção v5:
 * - Injeta MarketRegimeMonitor via construtor.
 * - Usa StrategyEngine.fromProfile() com monitor para runtime.
 * - Backtest continua usando fromProfile() sem monitor (compatibilidade).
 *
 * Um pipeline representa o conjunto completo de componentes
 * necessários para processar um ativo em tempo real:
 * TickCandleAggregator → StrategyEngine → Signal → DerivTradeService
 *
 * Thread-safety: o mapa é populado apenas durante o bootstrap
 * (thread principal) e depois apenas lido pelos callbacks.
 * LinkedHashMap mantém a ordem de inserção para logs previsíveis.
 */
@Component
public class PipelineRegistry {

    private static final Logger log = LoggerFactory.getLogger(PipelineRegistry.class);

    private final StrategiesConfigLoader strategiesLoader;

    /**
     * Monitor de regime injetado pelo Spring.
     *
     * Compartilhado entre todos os pipelines: cada pipeline notifica
     * o monitor via StrategyEngine.onBar(), mas o monitor mantém
     * estado isolado por símbolo internamente (ConcurrentHashMap).
     */
    private final MarketRegimeMonitor regimeMonitor;

    private final Map<String, BotPipeline> pipelinesBySymbol =
            new LinkedHashMap<>();

    public PipelineRegistry(
            StrategiesConfigLoader strategiesLoader,
            MarketRegimeMonitor regimeMonitor
    ) {
        this.strategiesLoader = strategiesLoader;
        this.regimeMonitor = regimeMonitor;
    }

    /**
     * Constrói e registra os pipelines para todos os profiles.
     *
     * Profiles sem estratégias habilitadas são ignorados com log de aviso.
     *
     * @param profiles      lista de profiles do strategies.json
     * @param signalHandler callback chamado quando o engine emite sinal
     */
    public void buildAll(
            List<StrategiesProfile> profiles,
            SignalHandler signalHandler
    ) {
        for (StrategiesProfile profile : profiles) {
            buildAndRegister(profile, signalHandler);
        }

        log.info("PIPELINE REGISTRY | {} pipelines registered",
                pipelinesBySymbol.size());
    }

    /**
     * Retorna o pipeline de um ativo pelo símbolo.
     *
     * @param symbol símbolo do ativo
     * @return Optional com o pipeline ou vazio se não existir
     */
    public Optional<BotPipeline> get(String symbol) {
        return Optional.ofNullable(pipelinesBySymbol.get(symbol));
    }

    /**
     * Retorna todos os pipelines registrados.
     *
     * @return mapa imutável de pipelines por símbolo
     */
    public Map<String, BotPipeline> getAll() {
        return Map.copyOf(pipelinesBySymbol);
    }

    /**
     * Retorna todos os símbolos registrados.
     *
     * @return lista de símbolos
     */
    public List<String> getSymbols() {
        return List.copyOf(pipelinesBySymbol.keySet());
    }

    /**
     * Retorna todos os profiles registrados.
     *
     * @return lista de profiles
     */
    public List<StrategiesProfile> getProfiles() {
        return pipelinesBySymbol.values().stream()
                .map(BotPipeline::profile)
                .toList();
    }

    /**
     * Retorna a quantidade de pipelines registrados.
     *
     * @return número de pipelines
     */
    public int size() {
        return pipelinesBySymbol.size();
    }

    // ═══════════════════════════════════════════════════════════════
    // Construção de pipeline
    // ═══════════════════════════════════════════════════════════════

    private void buildAndRegister(
            StrategiesProfile profile,
            SignalHandler signalHandler
    ) {
        String symbol = profile.getSymbol();
        List<TradingStrategy> strategies =
                strategiesLoader.buildStrategies(profile);

        if (strategies.isEmpty()) {
            log.warn("PIPELINE SKIP | symbol={} | reason=no enabled strategies",
                    symbol);
            return;
        }

        StrategyEngine engine = buildEngine(profile, strategies, signalHandler);
        TickCandleAggregator aggregator = buildAggregator(profile, engine);

        pipelinesBySymbol.put(symbol,
                new BotPipeline(profile, engine, aggregator));

        logPipelineRegistered(profile, strategies);
    }

    /**
     * Constrói o StrategyEngine com MarketRegimeMonitor injetado.
     *
     * Usa StrategyEngine.fromProfile() com monitor para garantir que
     * o monitoramento de regime seja ativado em todos os pipelines
     * de runtime. O monitor é o mesmo bean compartilhado por todos
     * os pipelines, mas mantém estado isolado por símbolo internamente.
     *
     * @param profile       profile do ativo
     * @param strategies    estratégias habilitadas
     * @param signalHandler callback de sinal final
     * @return StrategyEngine configurado com monitoramento de regime
     */
    private StrategyEngine buildEngine(
            StrategiesProfile profile,
            List<TradingStrategy> strategies,
            SignalHandler signalHandler
    ) {
        StrategyEngine engine = StrategyEngine.fromProfile(
                profile,
                strategies,
                regimeMonitor
        );

        engine.onFinalSignal(signal ->
                signalHandler.handle(profile, engine, signal));

        return engine;
    }

    private TickCandleAggregator buildAggregator(
            StrategiesProfile profile,
            StrategyEngine engine
    ) {
        return new TickCandleAggregator(
                profile.getGranularitySeconds(),
                engine::onBar
        );
    }

    private void logPipelineRegistered(
            StrategiesProfile profile,
            List<TradingStrategy> strategies
    ) {
        log.info("PIPELINE REGISTERED | symbol={} | granularity={}s " +
                        "| maxBars={} | strategies={} | tradeEnabled={} " +
                        "| decisionMode={} | rangeLookback={} | rangeMultiplier={} " +
                        "| regimeMonitor=enabled",
                profile.getSymbol(),
                profile.getGranularitySeconds(),
                profile.getMaxBars(),
                strategies.size(),
                profile.getTrade().isEnabled(),
                profile.getDecisionMode(),
                profile.getRangeLookback(),
                profile.getRangeMultiplier()
        );
    }
}