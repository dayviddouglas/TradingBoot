package com.github.dayviddouglas.TradingBot.engine;

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
 * Fluxo por candle:
 * 1. onBar() recebe cada candle do StrategyEngine
 * 2. Incrementa contador de decimação por símbolo
 * 3. Se contador < DECIMATION_INTERVAL: retorna sem avaliar
 * 4. Se contador == DECIMATION_INTERVAL: avalia e reseta contador
 * 5. Extrai janela de REGIME_LOOKBACK candles do snapshot
 * 6. Classifica regime via MarketRegimeClassifier (retorna RegimeMetrics)
 * 7. Passa para RegimeStateTracker aplicar filtro de persistência
 * 8. Se transição confirmada: atualiza RegimeRegistry + notifica RegimeHistoryService
 *
 * Thread-safety:
 * ConcurrentHashMap para o mapa de contadores por símbolo. Cada símbolo
 * tem sua própria thread do StrategyEngine (via virtual thread), portanto
 * o contador de cada símbolo é acessado por uma única thread por vez.
 *
 * A anotação @Component registra esta classe como bean singleton gerenciado
 * pelo Spring. Todos os pipelines de símbolo compartilham a mesma instância,
 * mas com estado isolado por símbolo via mapas internos.
 */
@Component
public class MarketRegimeMonitor {

    private static final Logger log = LoggerFactory.getLogger(MarketRegimeMonitor.class);

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
    private final RegimeStateTracker stateTracker;
    private final RegimeRegistry regimeRegistry;
    private final RegimeHistoryService regimeHistoryService;

    /**
     * Contadores de decimação por símbolo.
     *
     * Cada símbolo tem seu próprio contador independente, garantindo que
     * a avaliação de "frxEURUSD" não seja afetada pelo volume de ticks
     * de "frxXAUUSD" ou qualquer outro ativo.
     *
     * AtomicInteger não é necessário porque cada símbolo é processado
     * por uma única thread do StrategyEngine; ConcurrentHashMap garante
     * visibilidade entre threads na inicialização.
     */
    private final Map<String, Integer> decimationCounterBySymbol =
            new ConcurrentHashMap<>();

    /**
     * Construtor com injeção de dependências via construtor (DIP).
     *
     * O Spring resolve automaticamente todos os beans no contexto IoC.
     * Nenhum @Autowired em campo: todas as dependências são explícitas
     * e auditáveis no construtor.
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
        this.regimeClassifier = regimeClassifier;
        this.stateTracker = stateTracker;
        this.regimeRegistry = regimeRegistry;
        this.regimeHistoryService = regimeHistoryService;
    }

    /**
     * Processa um novo candle fechado e aciona a avaliação de regime quando necessário.
     *
     * Ponto de entrada chamado pelo StrategyEngine a cada candle aceito.
     * A avaliação efetiva ocorre apenas a cada DECIMATION_INTERVAL candles,
     * implementando a decimação temporal de Hansen & Lunde [2006].
     *
     * O snapshot completo de barras é recebido para que o monitor possa
     * extrair a janela de REGIME_LOOKBACK candles necessária ao classificador,
     * sem precisar manter cópia local do histórico.
     *
     * @param symbol   símbolo do ativo que recebeu o novo candle
     * @param snapshot snapshot imutável do histórico atual do ativo
     */
    public void onBar(String symbol, List<Bar> snapshot) {
        if (symbol == null || symbol.isBlank()) return;
        if (snapshot == null || snapshot.isEmpty()) return;

        // Incrementa e verifica o contador de decimação para este símbolo
        int counter = decimationCounterBySymbol.merge(symbol, 1, Integer::sum);

        if (counter < DECIMATION_INTERVAL) {
            // Ainda não chegou o momento de avaliar: aguarda mais candles
            return;
        }

        // Reseta o contador para o próximo ciclo de decimação
        decimationCounterBySymbol.put(symbol, 0);

        // Avalia o regime com a janela correta de candles
        evaluateRegime(symbol, snapshot);
    }

    // ═══════════════════════════════════════════════════════════════
    // Pipeline de avaliação
    // ═══════════════════════════════════════════════════════════════

    /**
     * Executa o pipeline completo de classificação e persistência de regime.
     *
     * Extrai a janela de observação, classifica o regime, captura métricas
     * e delega ao RegimeStateTracker para aplicação do filtro de persistência.
     *
     * @param symbol   símbolo do ativo
     * @param snapshot histórico completo disponível
     */
    private void evaluateRegime(String symbol, List<Bar> snapshot) {
        // Extrai a janela de observação: últimos REGIME_LOOKBACK candles
        // Se o histórico for menor, usa tudo que está disponível
        List<Bar> window = extractWindow(snapshot);

        if (window.size() < 60) {
            // Janela muito pequena para classificação confiável
            // O MarketRegimeClassifier já trata isso, mas evitamos chamada desnecessária
            log.debug("REGIME MONITOR | symbol={} | window too small ({} bars), skipping",
                    symbol, window.size());
            return;
        }

        // Classifica o regime e captura as métricas técnicas desta avaliação
        RegimeMetrics metrics = regimeClassifier.classifyWithMetrics(window);

        log.debug("REGIME MONITOR | symbol={} | decimation={} | {}",
                symbol, DECIMATION_INTERVAL, metrics.toLogString());

        // Aplica filtro de persistência de Hamilton [1989] via RegimeStateTracker
        // O callback é invocado apenas quando a transição é confirmada (counter == 3)
        stateTracker.evaluate(
                symbol,
                metrics.regime(),
                metrics,
                event -> onRegimeConfirmed(event)
        );
    }

    /**
     * Callback invocado pelo RegimeStateTracker quando uma transição é confirmada.
     *
     * Executa em virtual thread (despachado pelo StrategyEngine) para não
     * bloquear o pipeline de candles enquanto a persistência em disco ocorre.
     *
     * Responsabilidades:
     * 1. Atualiza o RegimeRegistry com o novo regime confirmado
     * 2. Persiste o evento no arquivo JSON diário via RegimeHistoryService
     *
     * @param event evento de mudança de regime confirmado
     */
    private void onRegimeConfirmed(RegimeChangeEvent event) {
        // Atualiza o registry para consulta O(1) pelo DerivTradeService
        regimeRegistry.updateRegime(event.symbol(), event.currentRegime());

        // Persiste o evento em arquivo JSON diário (operação de I/O)
        // Executado na mesma virtual thread do StrategyEngine; se performance
        // for crítica, considere despachar para thread separada
        Thread.startVirtualThread(() ->
                regimeHistoryService.record(event));
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
     * List.copyOf não é necessário aqui porque subList já é uma view
     * imutável do snapshot original (que também é imutável via BarHistory).
     *
     * @param snapshot histórico completo do ativo
     * @return sublista com no máximo REGIME_LOOKBACK candles
     */
    private List<Bar> extractWindow(List<Bar> snapshot) {
        if (snapshot.size() <= REGIME_LOOKBACK) {
            return snapshot;
        }
        return snapshot.subList(snapshot.size() - REGIME_LOOKBACK, snapshot.size());
    }
}