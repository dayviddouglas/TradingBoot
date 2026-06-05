package com.github.dayviddouglas.TradingBot.engine.regime;

import com.github.dayviddouglas.TradingBot.model.Bar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orquestrador do pipeline de monitoramento de regime de mercado.
 *
 * Responsabilidade única: receber eventos onBar do StrategyEngine,
 * aplicar decimação temporal e acionar a classificação de regime
 * na janela correta de candles.
 *
 * Fundamentação científica — três parâmetros críticos:
 *
 * 1. Decimação (DECIMATION_INTERVAL = 15 candles):
 *    Baseado em [Hansen & Lunde, 2006] para filtragem de ruído de
 *    microestrutura intraday. Avaliar regime a cada tick seria dominado
 *    por ruído; avaliar a cada 15 minutos captura mudanças estruturais
 *    sem ser excessivamente reativo a movimentos de curto prazo.
 *    Referência: Hansen, P.R. & Lunde, A. (2006). "Realized Variance
 *    and Market Microstructure Noise." Journal of Business & Economic
 *    Statistics, 24(2), 127-161.
 *
 * 2. Janela de observação (REGIME_LOOKBACK = 200 candles):
 *    Calibrado para capturar a duração modal dos regimes estruturais
 *    conforme [Bulla & Bulla, 2006]. Com candles de 1 minuto, 200 candles
 *    representam ~3.3 horas, período suficiente para capturar tanto
 *    sessões de range curto quanto tendências de meia-sessão.
 *    Referência: Bulla, J. & Bulla, I. (2006). "Stylized Facts of
 *    Financial Time Series and Hidden Semi-Markov Models." Computational
 *    Statistics & Data Analysis, 51(4), 2192-2209.
 *
 * 3. Filtro de persistência (no RegimeStateTracker):
 *    Implementação do teste de Hamilton [1989] com 3 confirmações,
 *    reduzindo α (probabilidade de falso positivo) a ≈ 0.001.
 *
 * Fluxo por candle (runtime):
 * 1. onBar() recebe cada candle do StrategyEngine
 * 2. Incrementa contador de decimação por símbolo
 * 3. Se contador < DECIMATION_INTERVAL: retorna sem avaliar
 * 4. Se contador == DECIMATION_INTERVAL: avalia e reseta contador
 * 5. Extrai janela de REGIME_LOOKBACK candles do snapshot
 * 6. Classifica regime via MarketRegimeClassifier (retorna RegimeMetrics)
 * 7. Passa para RegimeStateTracker aplicar filtro de persistência
 * 8. Se transição confirmada: atualiza RegimeRegistry + notifica RegimeHistoryService
 *
 * Fluxo de warm-up histórico (v5.4.2):
 * 1. evaluateForWarmUp() recebe snapshot parcial do histórico
 * 2. Aplica mesma decimação do runtime (compartilha contadores)
 * 3. Classifica regime com pipeline idêntico ao runtime
 * 4. Se transição confirmada: atualiza RegimeRegistry silenciosamente
 * 5. NÃO grava no RegimeHistoryService (dados históricos, não tempo real)
 * 6. NÃO gera logs de transições intermediárias do histórico
 *
 * Thread-safety:
 * ConcurrentHashMap para o mapa de contadores por símbolo. Cada símbolo
 * tem sua própria thread do StrategyEngine (via virtual thread), portanto
 * o contador de cada símbolo é acessado por uma única thread por vez.
 */
@Component
public class MarketRegimeMonitor {

    private static final Logger log =
            LoggerFactory.getLogger(MarketRegimeMonitor.class);

    /**
     * Frequência de avaliação: a cada N candles fechados.
     *
     * Valor 15 implementa a decimação de Hansen & Lunde [2006]:
     * com candles de 1 minuto, avalia o regime a cada 15 minutos,
     * filtrando o ruído de microestrutura intraday.
     */
    private static final int DECIMATION_INTERVAL = 15;

    /**
     * Janela de candles usada pelo classificador de regime.
     *
     * Valor 200 baseado em Bulla & Bulla [2006] para capturar
     * a duração modal dos regimes estruturais em séries financeiras.
     * Com candles de 1 minuto: 200 candles ≈ 3h20min de histórico.
     */
    private static final int REGIME_LOOKBACK = 200;

    private final MarketRegimeClassifier regimeClassifier;
    private final RegimeStateTracker     stateTracker;
    private final RegimeRegistry         regimeRegistry;
    private final RegimeHistoryService   regimeHistoryService;

    /**
     * Contadores de decimação por símbolo.
     *
     * Compartilhados entre o warm-up e o runtime para garantir
     * continuidade exata dos ciclos de decimação entre as duas fases.
     * Ao término do warm-up, o contador de cada símbolo estará no
     * valor correto para continuação imediata com os ticks reais.
     */
    private final Map<String, Integer> decimationCounterBySymbol =
            new ConcurrentHashMap<>();

    /**
     * Construtor com injeção de dependências via construtor (DIP).
     *
     * @param regimeClassifier     classifica o regime com base nos candles
     * @param stateTracker         aplica filtro de persistência por símbolo
     * @param regimeRegistry       armazena regime confirmado para consulta O(1)
     * @param regimeHistoryService persiste eventos de transição em arquivo JSON
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
     * Processa um novo candle fechado e aciona a avaliação de regime
     * quando necessário.
     *
     * Ponto de entrada chamado pelo StrategyEngine a cada candle aceito
     * em tempo real. A avaliação efetiva ocorre apenas a cada
     * DECIMATION_INTERVAL candles, implementando a decimação temporal
     * de Hansen & Lunde [2006].
     *
     * Quando a transição é confirmada:
     * - RegimeRegistry é atualizado com log operacional
     * - RegimeHistoryService grava o evento no JSON diário
     *
     * @param symbol   símbolo do ativo que recebeu o novo candle
     * @param snapshot snapshot imutável do histórico atual do ativo
     */
    public void onBar(String symbol, List<Bar> snapshot) {
        if (symbol == null || symbol.isBlank()) return;
        if (snapshot == null || snapshot.isEmpty()) return;

        int counter = decimationCounterBySymbol.merge(symbol, 1, Integer::sum);

        if (counter < DECIMATION_INTERVAL) return;

        decimationCounterBySymbol.put(symbol, 0);

        evaluateRegime(symbol, snapshot);
    }

    // ═══════════════════════════════════════════════════════════════
    // Warm-up histórico — pipeline sem persistência
    // ═══════════════════════════════════════════════════════════════

    /**
     * Avalia o regime a partir de snapshot histórico sem registrar
     * eventos no RegimeHistoryService.
     *
     * Usado exclusivamente pelo StrategyEngine.evaluateRegimeFromHistory()
     * durante o warm-up inicial. Compartilha os contadores de decimação
     * com o runtime para garantir continuidade exata entre as duas fases.
     *
     * Diferenças em relação a onBar():
     * - Não grava no RegimeHistoryService (dados históricos)
     * - Não gera logs de transições intermediárias do histórico
     * - Usa updateRegimeSilently() no RegimeRegistry
     *
     * O regime final confirmado ao término do warm-up é logado
     * pelo StrategyEngine via getConfirmedRegime().
     *
     * @param symbol   símbolo do ativo
     * @param snapshot snapshot parcial do histórico em ordem cronológica
     * @since v5.4.2
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
     * Retorna o regime confirmado atual para um símbolo.
     *
     * Delega para o RegimeStateTracker para leitura do estado interno.
     * Usado pelo StrategyEngine para logar o regime final após o warm-up.
     *
     * @param symbol símbolo do ativo
     * @return regime confirmado ou CHOPPY se nenhum confirmado ainda
     * @since v5.4.2
     */
    public MarketRegime getConfirmedRegime(String symbol) {
        return stateTracker.getCurrentRegime(symbol);
    }

    // ═══════════════════════════════════════════════════════════════
    // Pipeline de avaliação — runtime
    // ═══════════════════════════════════════════════════════════════

    /**
     * Executa o pipeline completo de classificação e persistência de regime.
     *
     * Quando a transição é confirmada:
     * - RegimeRegistry.updateRegime() → log operacional
     * - RegimeHistoryService.record() → JSON diário
     *
     * @param symbol   símbolo do ativo
     * @param snapshot histórico completo disponível
     */
    private void evaluateRegime(String symbol, List<Bar> snapshot) {
        List<Bar> window = extractWindow(snapshot);

        if (window.size() < 60) {
            log.debug("REGIME MONITOR | symbol={} | window too small "
                            + "({} bars), skipping",
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
     * Callback invocado pelo RegimeStateTracker quando uma transição
     * é confirmada em tempo real.
     *
     * Responsabilidades:
     * 1. Atualiza o RegimeRegistry com log operacional
     * 2. Persiste o evento no arquivo JSON diário via RegimeHistoryService
     *
     * @param event evento de mudança de regime confirmado
     */
    private void onRegimeConfirmed(RegimeChangeEvent event) {
        regimeRegistry.updateRegime(event.symbol(), event.currentRegime());

        Thread.startVirtualThread(() ->
                regimeHistoryService.record(event));
    }

    // ═══════════════════════════════════════════════════════════════
    // Pipeline de avaliação — warm-up histórico
    // ═══════════════════════════════════════════════════════════════

    /**
     * Executa o pipeline de classificação de regime sem persistência.
     *
     * Idêntico a evaluateRegime() mas o callback de confirmação
     * usa updateRegimeSilently() — sem log e sem gravação no JSON.
     *
     * @param symbol   símbolo do ativo
     * @param snapshot histórico disponível para classificação
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
     *
     * Atualiza o RegimeRegistry silenciosamente para que o DerivTradeService
     * tenha o regime correto desde o primeiro trade, mas:
     * - NÃO grava no RegimeHistoryService (dados históricos)
     * - NÃO gera log de transição (seria ruído de dados históricos)
     *
     * O regime final é logado pelo StrategyEngine ao término do warm-up.
     *
     * @param event evento de mudança de regime confirmado no histórico
     */
    private void onRegimeConfirmedWarmUp(RegimeChangeEvent event) {
        regimeRegistry.updateRegimeSilently(event.symbol(), event.currentRegime());
    }

    // ═══════════════════════════════════════════════════════════════
    // Utilitários
    // ═══════════════════════════════════════════════════════════════

    /**
     * Extrai a janela de observação do snapshot completo.
     *
     * Se o histórico for maior que REGIME_LOOKBACK, retorna apenas
     * os últimos N candles. Se for menor, retorna tudo disponível.
     *
     * @param snapshot histórico completo do ativo
     * @return sublista com no máximo REGIME_LOOKBACK candles
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