package com.github.dayviddouglas.TradingBoot.engine.regime;

import com.github.dayviddouglas.TradingBoot.model.Bar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orquestrador do pipeline de monitoramento contínuo de regime de mercado.
 *
 * Recebe eventos {@link #onBar} do {@link com.github.dayviddouglas.TradingBoot.engine.core.StrategyEngine},
 * aplica decimação temporal e aciona a classificação de regime na janela correta de candles.
 *
 * <b>Fundamentação científica dos três parâmetros críticos:</b>
 * <ul>
 *   <li><b>Decimação ({@value DECIMATION_INTERVAL} candles)</b>: implementa a filtragem de ruído
 *       de microestrutura intraday de Hansen {@literal &} Lunde (2006). Em candles de 1 minuto,
 *       avalia o regime a cada 15 minutos, capturando mudanças estruturais sem reagir a ruído
 *       de curto prazo.</li>
 *   <li><b>Janela de observação ({@value REGIME_LOOKBACK} candles)</b>: calibrado para capturar
 *       a duração modal dos regimes estruturais conforme Bulla {@literal &} Bulla (2006).
 *       Em candles de 1 minuto, 200 candles ≈ 3h20min de histórico.</li>
 *   <li><b>Filtro de persistência (no {@link RegimeStateTracker})</b>: implementação do teste
 *       de Hamilton (1989) com 3 confirmações consecutivas, reduzindo a probabilidade de
 *       falso positivo a ≈ 0.001.</li>
 * </ul>
 *
 * Disponibiliza dois pipelines distintos:
 * <ul>
 *   <li><b>Runtime</b> via {@link #onBar}: aplica decimação, classifica o regime, aciona
 *       o filtro de persistência e, quando confirmado, atualiza o {@link RegimeRegistry}
 *       e persiste o evento no {@link RegimeHistoryService}</li>
 *   <li><b>Warm-up histórico</b> via {@link #evaluateForWarmUp}: mesmo pipeline de decimação
 *       e classificação, mas atualiza o {@link RegimeRegistry} silenciosamente sem gravar
 *       no {@link RegimeHistoryService} nem gerar logs de transição intermediária</li>
 * </ul>
 *
 * Os contadores de decimação são compartilhados entre warm-up e runtime via
 * {@code decimationCounterBySymbol}, garantindo continuidade exata dos ciclos de decimação
 * entre as duas fases sem reinicialização ao término do warm-up.
 */
@Component
public class MarketRegimeMonitor {

    private static final Logger log =
            LoggerFactory.getLogger(MarketRegimeMonitor.class);

    /**
     * Frequência de avaliação: uma avaliação a cada {@value DECIMATION_INTERVAL} candles fechados.
     * Em candles de 1 minuto, avalia o regime a cada 15 minutos.
     * Implementa a decimação temporal de Hansen {@literal &} Lunde (2006).
     */
    private static final int DECIMATION_INTERVAL = 15;

    /**
     * Tamanho da janela de candles fornecida ao {@link MarketRegimeClassifier}.
     * Valor baseado em Bulla {@literal &} Bulla (2006) para capturar a duração modal
     * dos regimes estruturais. Em candles de 1 minuto: 200 candles ≈ 3h20min.
     */
    private static final int REGIME_LOOKBACK = 200;

    private final MarketRegimeClassifier regimeClassifier;
    private final RegimeStateTracker     stateTracker;
    private final RegimeRegistry         regimeRegistry;
    private final RegimeHistoryService   regimeHistoryService;

    /**
     * Contadores de decimação por símbolo, compartilhados entre warm-up e runtime.
     * Cada símbolo tem seu contador incrementado independentemente, pois cada ativo
     * possui sua própria thread de processamento via virtual thread do
     * {@link com.github.dayviddouglas.TradingBoot.engine.core.StrategyEngine}.
     */
    private final Map<String, Integer> decimationCounterBySymbol =
            new ConcurrentHashMap<>();

    /**
     * @param regimeClassifier     classifica o regime com base na janela de candles
     * @param stateTracker         aplica o filtro de persistência de Hamilton (1989) por símbolo
     * @param regimeRegistry       armazena o regime confirmado para consulta em O(1)
     * @param regimeHistoryService persiste eventos de transição no arquivo JSON diário
     */
    public MarketRegimeMonitor(
            MarketRegimeClassifier regimeClassifier,
            RegimeStateTracker stateTracker,
            RegimeRegistry regimeRegistry,
            RegimeHistoryService regimeHistoryService
    ) {
        this.regimeClassifier     = regimeClassifier;
        this.stateTracker         = stateTracker;
        this.regimeRegistry       = regimeRegistry;
        this.regimeHistoryService = regimeHistoryService;
    }

    // ═══════════════════════════════════════════════════════════════
    // Runtime — pipeline principal
    // ═══════════════════════════════════════════════════════════════

    /**
     * Processa um novo candle fechado e aciona a avaliação de regime quando necessário.
     * Invocado pelo {@link com.github.dayviddouglas.TradingBoot.engine.core.StrategyEngine}
     * a cada candle aceito em tempo real.
     *
     * A avaliação efetiva ocorre apenas a cada {@value DECIMATION_INTERVAL} candles,
     * implementando a decimação temporal. Quando uma transição é confirmada pelo
     * {@link RegimeStateTracker}, o {@link RegimeRegistry} é atualizado e o evento
     * é persistido no {@link RegimeHistoryService} em virtual thread.
     *
     * @param symbol   símbolo do ativo que recebeu o novo candle
     * @param snapshot snapshot imutável do histórico atual do ativo
     */
    public void onBar(String symbol, List<Bar> snapshot) {
        if (symbol == null || symbol.isBlank()) return;
        if (snapshot == null || snapshot.isEmpty()) return;

        int counter = decimationCounterBySymbol.merge(symbol, 1, Integer::sum);

        if (counter < DECIMATION_INTERVAL) return;

        // Reseta o contador e aciona a avaliação de regime
        decimationCounterBySymbol.put(symbol, 0);

        evaluateRegime(symbol, snapshot);
    }

    // ═══════════════════════════════════════════════════════════════
    // Warm-up histórico — pipeline sem persistência
    // ═══════════════════════════════════════════════════════════════

    /**
     * Avalia o regime a partir de um snapshot histórico sem registrar eventos no
     * {@link RegimeHistoryService} nem gerar logs de transição intermediária.
     *
     * Utilizado exclusivamente pelo
     * {@link com.github.dayviddouglas.TradingBoot.engine.core.StrategyEngine#evaluateRegimeFromHistory()}
     * durante o warm-up inicial. Compartilha os contadores de decimação com o runtime,
     * garantindo continuidade exata dos ciclos entre as duas fases.
     *
     * Quando uma transição é confirmada, atualiza o {@link RegimeRegistry} via
     * {@link RegimeRegistry#updateRegimeSilently} sem log nem gravação em arquivo.
     * O regime final confirmado é logado pelo
     * {@link com.github.dayviddouglas.TradingBoot.engine.core.StrategyEngine}
     * ao término do warm-up via {@link #getConfirmedRegime}.
     *
     * @param symbol   símbolo do ativo
     * @param snapshot snapshot parcial do histórico em ordem cronológica
     */
    public void evaluateForWarmUp(String symbol, List<Bar> snapshot) {
        if (symbol == null || symbol.isBlank()) return;
        if (snapshot == null || snapshot.isEmpty()) return;

        int counter = decimationCounterBySymbol.merge(symbol, 1, Integer::sum);

        if (counter < DECIMATION_INTERVAL) return;

        decimationCounterBySymbol.put(symbol, 0);

        evaluateRegimeForWarmUp(symbol, snapshot);
    }

    /**
     * Retorna o regime confirmado atual para o símbolo informado.
     * Delega para o {@link RegimeStateTracker}, que mantém o estado interno por símbolo.
     * Utilizado pelo {@link com.github.dayviddouglas.TradingBoot.engine.core.StrategyEngine}
     * para registrar o regime final ao término do warm-up.
     *
     * @param symbol símbolo do ativo
     * @return regime confirmado ou {@link MarketRegime#CHOPPY} se nenhum foi confirmado ainda
     */
    public MarketRegime getConfirmedRegime(String symbol) {
        return stateTracker.getCurrentRegime(symbol);
    }

    // ═══════════════════════════════════════════════════════════════
    // Pipeline de avaliação — runtime
    // ═══════════════════════════════════════════════════════════════

    /**
     * Executa o pipeline completo de classificação e persistência de regime em tempo real.
     * Extrai a janela de observação, classifica o regime via {@link MarketRegimeClassifier}
     * e passa para o {@link RegimeStateTracker} aplicar o filtro de persistência.
     * Quando a transição é confirmada, atualiza o {@link RegimeRegistry} com log
     * e persiste o evento no {@link RegimeHistoryService} em virtual thread.
     *
     * @param symbol   símbolo do ativo
     * @param snapshot histórico completo disponível para extração da janela
     */
    private void evaluateRegime(String symbol, List<Bar> snapshot) {
        List<Bar> window = extractWindow(snapshot);

        if (window.size() < 60) {
            log.debug("REGIME MONITOR | symbol={} | window too small ({} bars), skipping",
                    symbol, window.size());
            return;
        }

        RegimeMetrics metrics = regimeClassifier.classifyWithMetrics(window);

        log.debug("REGIME MONITOR | symbol={} | decimation={} | {}",
                symbol, DECIMATION_INTERVAL, metrics.toLogString());

        stateTracker.evaluate(
                symbol,
                metrics.regime(),
                metrics,
                event -> onRegimeConfirmed(event)
        );
    }

    /**
     * Callback invocado pelo {@link RegimeStateTracker} quando uma transição é confirmada
     * em tempo real. Atualiza o {@link RegimeRegistry} e persiste o evento no
     * {@link RegimeHistoryService} em virtual thread para não bloquear o pipeline de candles.
     *
     * @param event evento de mudança de regime confirmado após o filtro de persistência
     */
    private void onRegimeConfirmed(RegimeChangeEvent event) {
        regimeRegistry.updateRegime(event.symbol(), event.currentRegime());

        // Persistência em virtual thread para não bloquear o pipeline de candles
        Thread.startVirtualThread(() ->
                regimeHistoryService.record(event));
    }

    // ═══════════════════════════════════════════════════════════════
    // Pipeline de avaliação — warm-up histórico
    // ═══════════════════════════════════════════════════════════════

    /**
     * Executa o pipeline de classificação de regime sem persistência.
     * Idêntico a {@link #evaluateRegime} exceto pelo callback de confirmação,
     * que utiliza {@link RegimeRegistry#updateRegimeSilently} em vez de
     * {@link RegimeRegistry#updateRegime}, sem log nem gravação em arquivo.
     *
     * @param symbol   símbolo do ativo
     * @param snapshot histórico disponível para extração da janela e classificação
     */
    private void evaluateRegimeForWarmUp(String symbol, List<Bar> snapshot) {
        List<Bar> window = extractWindow(snapshot);

        if (window.size() < 60) return;

        RegimeMetrics metrics = regimeClassifier.classifyWithMetrics(window);

        stateTracker.evaluate(
                symbol,
                metrics.regime(),
                metrics,
                event -> onRegimeConfirmedWarmUp(event)
        );
    }

    /**
     * Callback de confirmação de regime durante o warm-up histórico.
     * Atualiza o {@link RegimeRegistry} silenciosamente sem log nem gravação
     * no {@link RegimeHistoryService}, pois os dados são históricos e não representam
     * transições em tempo real.
     *
     * @param event evento de mudança de regime confirmado nos dados históricos
     */
    private void onRegimeConfirmedWarmUp(RegimeChangeEvent event) {
        regimeRegistry.updateRegimeSilently(event.symbol(), event.currentRegime());
    }

    // ═══════════════════════════════════════════════════════════════
    // Utilitários
    // ═══════════════════════════════════════════════════════════════

    /**
     * Extrai os últimos {@value REGIME_LOOKBACK} candles do snapshot completo.
     * Quando o histórico disponível for menor que o lookback, retorna tudo disponível.
     *
     * @param snapshot histórico completo do ativo
     * @return sublista com no máximo {@value REGIME_LOOKBACK} candles mais recentes
     */
    private List<Bar> extractWindow(List<Bar> snapshot) {
        if (snapshot.size() <= REGIME_LOOKBACK) {
            return snapshot;
        }
        return snapshot.subList(
                snapshot.size() - REGIME_LOOKBACK,
                snapshot.size());
    }
}