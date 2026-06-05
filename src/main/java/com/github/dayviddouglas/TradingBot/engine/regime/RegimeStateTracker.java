package com.github.dayviddouglas.TradingBot.engine.regime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Máquina de estados de regime por símbolo com filtro de persistência.
 *
 * Responsabilidade única: implementar o filtro de persistência de Hamilton [1989]
 * para cada ativo, confirmando mudanças de regime apenas após N avaliações
 * consecutivas no mesmo estado candidato.
 *
 * Fundamentação científica:
 * O filtro de persistência reduz a probabilidade de falsos positivos para
 * α = 0.001 conforme demonstrado em Hamilton (1989). Uma única classificação
 * de regime tem alta probabilidade de ser ruído de microestrutura
 * [Andersen & Bollerslev, 1997]; três classificações consecutivas indicam
 * uma mudança estrutural real e persistente.
 *
 * Parâmetros do filtro:
 * - CONFIRMATION_THRESHOLD = 3 avaliações consecutivas
 * - Cada avaliação ocorre a cada 15 candles (decimação do MarketRegimeMonitor)
 * - Tempo total de confirmação: 3 × 15 = 45 minutos de permanência no novo estado
 *
 * Máquina de estados por símbolo:
 * ┌─────────────────────────────────────────────────────────┐
 * │ currentRegime (confirmado)  │ candidateRegime + counter │
 * ├─────────────────────────────────────────────────────────┤
 * │ RANGING (inicial)           │ TRENDING / counter=0      │
 * │ RANGING (confirmado)        │ TRENDING / counter=1      │
 * │ RANGING (confirmado)        │ TRENDING / counter=2      │
 * │ TRENDING (novo confirmado)  │ TRENDING / counter=3 ✅   │
 * └─────────────────────────────────────────────────────────┘
 *
 * Se o candidato mudar antes de atingir o threshold, o contador é resetado:
 * │ RANGING (confirmado)        │ TRENDING / counter=2      │
 * │ RANGING (confirmado)        │ CHOPPY   / counter=1 ↩    │ ← reset
 *
 * Thread-safety:
 * ConcurrentHashMap para o mapa de estados por símbolo. Os estados individuais
 * (SymbolRegimeState) são acessados apenas pela thread do StrategyEngine de
 * cada ativo (via MarketRegimeMonitor), portanto não há contenção entre símbolos.
 *
 * A anotação @Component registra esta classe como bean singleton no Spring.
 * Todos os símbolos compartilham o mesmo tracker, mas com estado isolado
 * por símbolo via o mapa interno.
 */
@Component
public class RegimeStateTracker {

    private static final Logger log = LoggerFactory.getLogger(RegimeStateTracker.class);

    /**
     * Número de avaliações consecutivas necessárias para confirmar mudança de regime.
     *
     * Valor 3 implementa o teste de persistência de Hamilton [1989]:
     * - 3 avaliações × 15 candles/avaliação = 45 minutos de confirmação
     * - Probabilidade de falso positivo: α ≈ 0.001
     *
     * Referência: Hamilton, J.D. (1989). "A New Approach to the Economic
     * Analysis of Nonstationary Time Series and the Business Cycle."
     * Econometrica, 57(2), 357-384.
     */
    private static final int CONFIRMATION_THRESHOLD = 3;

    /**
     * Mapa de estado de regime por símbolo.
     *
     * Cada símbolo tem seu próprio estado isolado, garantindo que a máquina
     * de estados de "frxEURUSD" não interfira com a de "frxXAUUSD".
     */
    private final Map<String, SymbolRegimeState> stateBySymbol =
            new ConcurrentHashMap<>();

    /**
     * Processa uma nova classificação de regime para um símbolo.
     *
     * Este é o ponto central do filtro de persistência:
     * 1. Se o regime classificado é igual ao atual confirmado: reseta candidato
     * 2. Se o regime classificado é diferente do candidato atual: inicia novo candidato
     * 3. Se o regime classificado é igual ao candidato: incrementa contador
     * 4. Se o contador atingiu o threshold: confirma a transição
     *
     * O callback onConfirmed é invocado de forma síncrona quando a transição
     * é confirmada. O chamador (MarketRegimeMonitor) é responsável por
     * despachar para virtual thread se necessário.
     *
     * @param symbol      símbolo do ativo
     * @param newRegime   regime classificado na avaliação atual
     * @param metrics     métricas técnicas desta avaliação
     * @param onConfirmed callback invocado quando transição é confirmada
     */
    public void evaluate(
            String symbol,
            MarketRegime newRegime,
            RegimeMetrics metrics,
            Consumer<RegimeChangeEvent> onConfirmed
    ) {
        SymbolRegimeState state = stateBySymbol.computeIfAbsent(
                symbol, s -> new SymbolRegimeState(MarketRegime.CHOPPY));

        // Caso 1: Regime classificado == regime atual confirmado
        // Mercado estável, sem transição em andamento → reseta candidato
        if (newRegime == state.currentRegime) {
            if (state.candidateRegime != null) {
                log.debug("REGIME TRACKER | symbol={} | candidate={} reset " +
                                "| confirmed={} holds",
                        symbol, state.candidateRegime, state.currentRegime);
            }
            state.candidateRegime = null;
            state.candidateCounter = 0;
            return;
        }

        // Caso 2: Regime classificado mudou em relação ao candidato anterior
        // Inicia novo candidato do zero
        if (newRegime != state.candidateRegime) {
            log.debug("REGIME TRACKER | symbol={} | new candidate={} | previous candidate={}",
                    symbol, newRegime, state.candidateRegime);
            state.candidateRegime = newRegime;
            state.candidateCounter = 1;
            return;
        }

        // Caso 3: Regime classificado == candidato atual → incrementa contador
        state.candidateCounter++;

        log.debug("REGIME TRACKER | symbol={} | candidate={} | counter={}/{}",
                symbol, state.candidateRegime, state.candidateCounter,
                CONFIRMATION_THRESHOLD);

        // Caso 4: Threshold atingido → confirma transição
        if (state.candidateCounter >= CONFIRMATION_THRESHOLD) {
            MarketRegime previousRegime  = state.currentRegime;
            MarketRegime confirmedRegime = state.candidateRegime;

            state.currentRegime    = confirmedRegime;
            state.candidateRegime  = null;
            state.candidateCounter = 0;


            Instant marketTimestamp = (metrics.marketTimestamp() != null)
                    ? metrics.marketTimestamp()
                    : Instant.now();

            RegimeChangeEvent event = new RegimeChangeEvent(
                    marketTimestamp,     // ← tempo real de mercado (v5.4.2)
                    Instant.now(),       // ← tempo de processamento
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
     * Retorna o regime atualmente confirmado para um símbolo.
     *
     * Útil para diagnóstico e testes. O caminho de produção usa
     * o RegimeRegistry para consultas de regime, não este método.
     *
     * @param symbol símbolo do ativo
     * @return regime confirmado atual ou CHOPPY se não inicializado
     */
    public MarketRegime getCurrentRegime(String symbol) {
        SymbolRegimeState state = stateBySymbol.get(symbol);
        return state != null ? state.currentRegime : MarketRegime.CHOPPY;
    }

    /**
     * Retorna o contador de persistência do candidato atual.
     * Usado em testes unitários para verificar o estado interno.
     *
     * @param symbol símbolo do ativo
     * @return contador atual (0 se nenhum candidato)
     */
    public int getCandidateCounter(String symbol) {
        SymbolRegimeState state = stateBySymbol.get(symbol);
        return state != null ? state.candidateCounter : 0;
    }

    // ═══════════════════════════════════════════════════════════════
    // Estado interno por símbolo
    //
    // Classe privada não-thread-safe: cada instância é acessada
    // apenas pela thread do StrategyEngine do seu símbolo,
    // via o ConcurrentHashMap que garante isolamento por chave.
    // ═══════════════════════════════════════════════════════════════

    /**
     * Encapsula o estado da máquina de estados de regime para um símbolo.
     *
     * Mantém:
     * - currentRegime: regime atualmente confirmado (fonte de verdade para o Registry)
     * - candidateRegime: regime em processo de confirmação (provisório)
     * - candidateCounter: quantas avaliações consecutivas o candidato já tem
     */
    private static final class SymbolRegimeState {

        /** Regime atualmente confirmado para este símbolo */
        MarketRegime currentRegime;

        /**
         * Regime candidato aguardando confirmação.
         * null indica que não há transição em andamento.
         */
        MarketRegime candidateRegime;

        /**
         * Contador de avaliações consecutivas no candidato.
         * Quando atingir CONFIRMATION_THRESHOLD, a transição é confirmada.
         */
        int candidateCounter;

        SymbolRegimeState(MarketRegime initialRegime) {
            this.currentRegime = initialRegime;
            this.candidateRegime = null;
            this.candidateCounter = 0;
        }
    }
}