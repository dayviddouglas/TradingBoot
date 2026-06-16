package com.github.dayviddouglas.TradingBoot.bot;

import com.github.dayviddouglas.TradingBoot.config.strategy.StrategiesConfigLoader;
import com.github.dayviddouglas.TradingBoot.config.strategy.StrategiesProfile;
import com.github.dayviddouglas.TradingBoot.engine.regime.MarketRegimeMonitor;
import com.github.dayviddouglas.TradingBoot.engine.core.StrategyEngine;
import com.github.dayviddouglas.TradingBoot.market.TickCandleAggregator;
import com.github.dayviddouglas.TradingBoot.model.Bar;
import com.github.dayviddouglas.TradingBoot.strategy.TradingStrategy;
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
 * Cada pipeline representa o conjunto completo de componentes necessários
 * para processar um ativo em tempo real, seguindo o fluxo:
 * {@link TickCandleAggregator} → {@link StrategyEngine} → {@code Signal} → {@code DerivTradeService}.
 *
 * O {@link MarketRegimeMonitor} é injetado via construtor e compartilhado entre
 * todos os pipelines. Cada pipeline notifica o monitor a cada candle processado,
 * mas o monitor mantém estado isolado por símbolo internamente.
 *
 * O mapa de pipelines é populado exclusivamente durante o bootstrap do bot
 * pela thread principal e, após isso, apenas lido pelos callbacks de mercado.
 * O uso de {@link LinkedHashMap} preserva a ordem de inserção para
 * garantir logs previsíveis durante a inicialização.
 */
@Component
public class PipelineRegistry {

    private static final Logger log = LoggerFactory.getLogger(PipelineRegistry.class);

    private final StrategiesConfigLoader strategiesLoader;

    /**
     * Monitor de regime compartilhado entre todos os pipelines.
     * Mantém estado isolado por símbolo internamente via {@code ConcurrentHashMap},
     * recebendo notificações de cada pipeline a cada candle fechado processado
     * pelo {@link StrategyEngine} correspondente.
     */
    private final MarketRegimeMonitor regimeMonitor;

    private final Map<String, BotPipeline> pipelinesBySymbol =
            new LinkedHashMap<>();

    /**
     * @param strategiesLoader responsável por carregar e construir estratégias a partir do strategies.json
     * @param regimeMonitor    monitor de regime compartilhado entre todos os pipelines do sistema
     */
    public PipelineRegistry(
            StrategiesConfigLoader strategiesLoader,
            MarketRegimeMonitor regimeMonitor
    ) {
        this.strategiesLoader = strategiesLoader;
        this.regimeMonitor = regimeMonitor;
    }

    /**
     * Constrói e registra os pipelines para todos os profiles fornecidos.
     *
     * Profiles sem estratégias habilitadas são ignorados com log de aviso.
     * Ao final, registra no log o total de pipelines criados com sucesso.
     *
     * @param profiles      lista de profiles carregados do strategies.json
     * @param signalHandler callback invocado quando o engine emite um sinal final
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
     * @return {@link Optional} contendo o pipeline, ou vazio se não existir
     */
    public Optional<BotPipeline> get(String symbol) {
        return Optional.ofNullable(pipelinesBySymbol.get(symbol));
    }

    /**
     * Retorna uma cópia imutável de todos os pipelines registrados,
     * indexados pelo símbolo do ativo.
     *
     * @return mapa imutável de pipelines por símbolo
     */
    public Map<String, BotPipeline> getAll() {
        return Map.copyOf(pipelinesBySymbol);
    }

    /**
     * Retorna uma cópia imutável de todos os símbolos com pipelines registrados.
     *
     * @return lista de símbolos registrados
     */
    public List<String> getSymbols() {
        return List.copyOf(pipelinesBySymbol.keySet());
    }

    /**
     * Retorna os profiles de todos os pipelines registrados,
     * extraídos diretamente dos {@link BotPipeline} armazenados.
     *
     * @return lista de profiles dos pipelines ativos
     */
    public List<StrategiesProfile> getProfiles() {
        return pipelinesBySymbol.values().stream()
                .map(BotPipeline::profile)
                .toList();
    }

    /**
     * Retorna a quantidade de pipelines atualmente registrados.
     *
     * @return número de pipelines registrados
     */
    public int size() {
        return pipelinesBySymbol.size();
    }

    // ═══════════════════════════════════════════════════════════════
    // Construção de pipeline
    // ═══════════════════════════════════════════════════════════════

    /**
     * Constrói e registra o pipeline completo para um ativo.
     *
     * Caso nenhuma estratégia esteja habilitada para o profile,
     * o pipeline não é criado e um aviso é registrado no log.
     *
     * @param profile       configuração do ativo
     * @param signalHandler callback de sinal final a ser vinculado ao engine
     */
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
     * Constrói o {@link StrategyEngine} com o {@link MarketRegimeMonitor} injetado.
     *
     * O monitor compartilhado é passado ao engine para que cada candle processado
     * notifique o monitoramento de regime do ativo correspondente. O callback de
     * sinal final vincula o engine ao {@link SignalHandler} fornecido pelo runner.
     *
     * @param profile       configuração do ativo
     * @param strategies    estratégias habilitadas para o ativo
     * @param signalHandler callback invocado ao emitir sinal final
     * @return engine configurado com monitoramento de regime ativo
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

    /**
     * Constrói o {@link TickCandleAggregator} vinculado ao engine do ativo.
     *
     * Configura o agregador com a granularidade em segundos definida no profile
     * e registra {@link StrategyEngine#onBar(Bar)} como callback de candle fechado,
     * acionado a cada vez que um novo candle é completado pelo agregador.
     *
     * @param profile configuração do ativo com a granularidade em segundos
     * @param engine  engine que receberá os candles fechados
     * @return agregador configurado para o ativo
     */
    private TickCandleAggregator buildAggregator(
            StrategiesProfile profile,
            StrategyEngine engine
    ) {
        return new TickCandleAggregator(
                profile.getGranularitySeconds(),
                engine::onBar
        );
    }

    /**
     * Registra no log os detalhes do pipeline criado para um ativo.
     *
     * Inclui símbolo, granularidade, capacidade de barras, quantidade de estratégias,
     * configuração de trade, modo de decisão, parâmetros de range e confirmação
     * de que o monitoramento de regime está ativo.
     *
     * @param profile    configuração do ativo
     * @param strategies estratégias habilitadas no pipeline
     */
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