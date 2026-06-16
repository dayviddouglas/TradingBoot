package com.github.dayviddouglas.TradingBoot.engine.regime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Implementa o filtro de persistência de Hamilton (1989) por símbolo, confirmando
 * mudanças de regime apenas após {@value CONFIRMATION_THRESHOLD} avaliações consecutivas
 * no mesmo estado candidato.
 *
 * Uma única classificação de regime tem alta probabilidade de ser ruído de microestrutura;
 * três classificações consecutivas indicam uma mudança estrutural real e persistente,
 * reduzindo a probabilidade de falso positivo para α ≈ 0.001.
 *
 * Com a decimação de 15 candles do {@link MarketRegimeMonitor}, cada avaliação equivale
 * a 15 minutos, totalizando 45 minutos de confirmação para cada transição de regime.
 *
 * Máquina de estados por símbolo:
 * <pre>
 * currentRegime (confirmado) │ candidateRegime + counter
 * ───────────────────────────┼─────────────────────────────
 * RANGING                    │ TRENDING / counter=1
 * RANGING                    │ TRENDING / counter=2
 * TRENDING (confirmado) ✅   │ TRENDING / counter=3
 *
 * Se o candidato mudar antes de atingir o threshold, o contador é resetado:
 * RANGING                    │ TRENDING / counter=2
 * RANGING                    │ CHOPPY   / counter=1  ← reset
 * </pre>
 *
 * Cada símbolo possui estado isolado via {@link ConcurrentHashMap}, garantindo
 * que a máquina de estados de um ativo não interfira com a de outro.
 * Os estados individuais ({@link SymbolRegimeState}) são acessados apenas pela
 * thread do {@link com.github.dayviddouglas.TradingBoot.engine.core.StrategyEngine}
 * do respectivo ativo, sem contenção entre símbolos.
 */
@Component
public class RegimeStateTracker {

    private static final Logger log = LoggerFactory.getLogger(RegimeStateTracker.class);

    /**
     * Número de avaliações consecutivas necessárias para confirmar uma mudança de regime.
     * Implementa o teste de persistência de Hamilton (1989):
     * 3 avaliações × 15 candles/avaliação = 45 minutos de confirmação.
     */
    private static final int CONFIRMATION_THRESHOLD = 3;

    /**
     * Estado de regime isolado por símbolo.
     * Cada ativo possui sua própria instância de {@link SymbolRegimeState}.
     */
    private final Map<String, SymbolRegimeState> stateBySymbol =
            new ConcurrentHashMap<>();

    /**
     * Processa uma nova classificação de regime para o símbolo informado,
     * aplicando o filtro de persistência.
     *
     * Fluxo de decisão:
     * <ol>
     *   <li>Regime classificado igual ao confirmado → reseta o candidato; nenhuma transição</li>
     *   <li>Regime classificado diferente do candidato atual → inicia novo candidato com contador 1</li>
     *   <li>Regime classificado igual ao candidato → incrementa o contador</li>
     *   <li>Contador atingiu {@value CONFIRMATION_THRESHOLD} → confirma a transição e
     *       invoca o {@code onConfirmed} de forma síncrona</li>
     * </ol>
     *
     * O {@link RegimeChangeEvent} gerado na confirmação utiliza o {@code marketTimestamp}
     * das métricas como referência temporal real de mercado; quando ausente, usa
     * {@code Instant.now()} como fallback.
     *
     * O callback {@code onConfirmed} é invocado de forma síncrona. O chamador
     * ({@link MarketRegimeMonitor}) é responsável por despachar para virtual thread
     * quando necessário.
     *
     * @param symbol      símbolo do ativo
     * @param newRegime   regime classificado na avaliação atual pelo {@link MarketRegimeClassifier}
     * @param metrics     métricas técnicas desta avaliação, incluindo o timestamp real de mercado
     * @param onConfirmed callback invocado com o {@link RegimeChangeEvent} quando a transição é confirmada
     */
    public void evaluate(
            String symbol,
            MarketRegime newRegime,
            RegimeMetrics metrics,
            Consumer<RegimeChangeEvent> onConfirmed
    ) {
        SymbolRegimeState state = stateBySymbol.computeIfAbsent(
                symbol, s -> new SymbolRegimeState(MarketRegime.CHOPPY));

        // Regime estável — o mercado permanece no regime confirmado; reseta qualquer candidato pendente
        if (newRegime == state.currentRegime) {
            if (state.candidateRegime != null) {
                log.debug("REGIME TRACKER | symbol={} | candidate={} reset "
                                + "| confirmed={} holds",
                        symbol, state.candidateRegime, state.currentRegime);
            }
            state.candidateRegime  = null;
            state.candidateCounter = 0;
            return;
        }

        // Novo candidato detectado — o regime classificado diverge do candidato anterior;
        // inicia acumulação a partir de 1
        if (newRegime != state.candidateRegime) {
            log.debug("REGIME TRACKER | symbol={} | new candidate={} | previous candidate={}",
                    symbol, newRegime, state.candidateRegime);
            state.candidateRegime  = newRegime;
            state.candidateCounter = 1;
            return;
        }

        // Candidato confirmando — incrementa o contador de avaliações consecutivas
        state.candidateCounter++;

        log.debug("REGIME TRACKER | symbol={} | candidate={} | counter={}/{}",
                symbol, state.candidateRegime, state.candidateCounter,
                CONFIRMATION_THRESHOLD);

        // Threshold atingido — transição confirmada; atualiza estado e dispara o callback
        if (state.candidateCounter >= CONFIRMATION_THRESHOLD) {
            MarketRegime previousRegime  = state.currentRegime;
            MarketRegime confirmedRegime = state.candidateRegime;

            state.currentRegime    = confirmedRegime;
            state.candidateRegime  = null;
            state.candidateCounter = 0;

            // Usa o timestamp real de mercado das métricas; fallback para Instant.now() se ausente
            Instant marketTimestamp = (metrics.marketTimestamp() != null)
                    ? metrics.marketTimestamp()
                    : Instant.now();

            RegimeChangeEvent event = new RegimeChangeEvent(
                    marketTimestamp,  // tempo real de mercado do último candle da janela
                    Instant.now(),    // tempo de processamento da confirmação
                    symbol,
                    previousRegime,
                    confirmedRegime,
                    metrics
            );

            log.info("REGIME CONFIRMED | {}", event.toLogString());

            onConfirmed.accept(event);
        }
    }

    /**
     * Retorna o regime atualmente confirmado para o símbolo informado.
     * Utilizado pelo {@link MarketRegimeMonitor#getConfirmedRegime} para diagnóstico
     * e para log ao término do warm-up histórico.
     * O caminho de produção para consulta de regime usa o {@link RegimeRegistry}.
     *
     * @param symbol símbolo do ativo
     * @return regime confirmado atual ou {@link MarketRegime#CHOPPY} se não inicializado
     */
    public MarketRegime getCurrentRegime(String symbol) {
        SymbolRegimeState state = stateBySymbol.get(symbol);
        return state != null ? state.currentRegime : MarketRegime.CHOPPY;
    }

    /**
     * Retorna o contador de persistência do candidato atual para o símbolo informado.
     * Utilizado em testes unitários para verificar o estado interno da máquina de estados.
     *
     * @param symbol símbolo do ativo
     * @return contador de avaliações consecutivas do candidato atual, ou {@code 0} se ausente
     */
    public int getCandidateCounter(String symbol) {
        SymbolRegimeState state = stateBySymbol.get(symbol);
        return state != null ? state.candidateCounter : 0;
    }

    // ═══════════════════════════════════════════════════════════════
    // Estado interno por símbolo
    // ═══════════════════════════════════════════════════════════════

    /**
     * Encapsula o estado da máquina de estados de regime para um único ativo.
     * Não é thread-safe: cada instância é acessada apenas pela thread do
     * {@link com.github.dayviddouglas.TradingBoot.engine.core.StrategyEngine}
     * do seu respectivo ativo, sem compartilhamento entre símbolos.
     */
    private static final class SymbolRegimeState {

        /** Regime atualmente confirmado; fonte de verdade para o {@link RegimeRegistry}. */
        MarketRegime currentRegime;

        /**
         * Regime candidato aguardando confirmação pelo filtro de persistência.
         * {@code null} indica que não há transição em andamento.
         */
        MarketRegime candidateRegime;

        /**
         * Número de avaliações consecutivas acumuladas para o candidato atual.
         * Quando atingir {@value CONFIRMATION_THRESHOLD}, a transição é confirmada.
         */
        int candidateCounter;

        /**
         * @param initialRegime regime inicial do ativo; padrão {@link MarketRegime#CHOPPY}
         */
        SymbolRegimeState(MarketRegime initialRegime) {
            this.currentRegime   = initialRegime;
            this.candidateRegime = null;
            this.candidateCounter = 0;
        }
    }
}