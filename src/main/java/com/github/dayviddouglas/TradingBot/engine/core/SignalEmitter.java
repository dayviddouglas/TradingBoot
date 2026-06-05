package com.github.dayviddouglas.TradingBot.engine.core;

import com.github.dayviddouglas.TradingBot.engine.decision.DecisionMode;
import com.github.dayviddouglas.TradingBot.engine.decision.EvaluationResult;
import com.github.dayviddouglas.TradingBot.engine.filter.VolatilityFilter;
import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.model.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Responsável por construir e emitir o sinal final do StrategyEngine.
 *
 * Extraído do StrategyEngine para respeitar SRP:
 * - StrategyEngine orquestra a avaliação
 * - SignalEmitter constrói e emite o sinal final
 *
 * Responsabilidades:
 * - Construir o Signal final com metadata completa
 * - Aplicar anti-repetição de sinal consecutivo
 * - Despachar para o callback registrado
 * - Proteger contra erros no callback externo
 */
public class SignalEmitter {

    private static final Logger log = LoggerFactory.getLogger(SignalEmitter.class);

    private final String symbol;
    private final DecisionMode decisionMode;
    private final VolatilityFilter volatilityFilter;

    private volatile Consumer<Signal> onFinalSignal;
    private Signal.Type lastEmittedType = Signal.Type.NONE;

    public SignalEmitter(
            String symbol,
            DecisionMode decisionMode,
            VolatilityFilter volatilityFilter
    ) {
        this.symbol = symbol;
        this.decisionMode = decisionMode;
        this.volatilityFilter = volatilityFilter;
    }

    /**
     * Registra o callback que receberá os sinais finais.
     *
     * @param handler consumidor do sinal final
     */
    public void onFinalSignal(Consumer<Signal> handler) {
        this.onFinalSignal = handler;
    }

    /**
     * Tenta emitir o sinal resultante da avaliação.
     *
     * Aplica anti-repetição: suprime sinal consecutivo do mesmo tipo
     * para evitar múltiplas emissões enquanto o mercado permanece
     * na mesma condição.
     *
     * Reseta o último tipo emitido para NONE quando o filtro de
     * volatilidade bloqueia, permitindo que o próximo sinal válido
     * seja emitido normalmente.
     *
     * @param result   resultado da avaliação do DecisionEvaluator
     * @param snapshot snapshot dos candles no momento da avaliação
     */
    public void tryEmit(EvaluationResult result, List<Bar> snapshot) {
        if (!result.hasSignal()) return;

        Signal.Type signalType = result.signalType();

        if (isRepeated(signalType, snapshot)) return;

        lastEmittedType = signalType;

        Signal signal = buildSignal(result, snapshot);

        log.info(result.logMessage());

        dispatch(signal, snapshot);
    }

    /**
     * Reseta o controle de anti-repetição.
     * Chamado quando o filtro de volatilidade bloqueia.
     */
    public void resetLastEmitted() {
        lastEmittedType = Signal.Type.NONE;
    }

    // ═══════════════════════════════════════════════════════════════
    // Construção do sinal
    // ═══════════════════════════════════════════════════════════════

    private Signal buildSignal(EvaluationResult result, List<Bar> snapshot) {
        Bar last = snapshot.get(snapshot.size() - 1);
        Map<String, Object> metadata = buildMetadata(result, snapshot);

        return result.signalType() == Signal.Type.BUY
                ? Signal.buy(result.strategyName(), last.timestamp(),
                last.close(), metadata)
                : Signal.sell(result.strategyName(), last.timestamp(),
                last.close(), metadata);
    }

    private Map<String, Object> buildMetadata(
            EvaluationResult result,
            List<Bar> snapshot
    ) {
        Map<String, Object> metadata = new HashMap<>();

        metadata.put("symbol", symbol);
        metadata.put("decisionMode", decisionMode.name());
        metadata.put("range", volatilityFilter.getCurrentRange(snapshot));
        metadata.put("avgRange", volatilityFilter.getAverageRange(snapshot));
        metadata.put("rangeMultiplier", volatilityFilter.getRangeMultiplier());

        metadata.putAll(result.metadata());

        return Map.copyOf(metadata);
    }

    // ═══════════════════════════════════════════════════════════════
    // Anti-repetição
    // ═══════════════════════════════════════════════════════════════

    private boolean isRepeated(Signal.Type type, List<Bar> snapshot) {
        if (type != lastEmittedType) return false;

        Bar last = snapshot.get(snapshot.size() - 1);
        log.debug("SIGNAL SUPPRESSED (repeated) | symbol={} | type={} | time={}",
                symbol, type, last.timestamp());
        return true;
    }

    // ═══════════════════════════════════════════════════════════════
    // Despacho
    // ═══════════════════════════════════════════════════════════════

    private void dispatch(Signal signal, List<Bar> snapshot) {
        Consumer<Signal> callback = onFinalSignal;
        if (callback == null) return;

        try {
            callback.accept(signal);
        } catch (Exception e) {
            Bar last = snapshot.get(snapshot.size() - 1);
            log.warn("SIGNAL CALLBACK ERROR | symbol={} | time={}",
                    symbol, last.timestamp(), e);
        }
    }
}
