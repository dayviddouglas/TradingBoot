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
 * Responsável por construir e emitir o sinal final produzido pelo {@link StrategyEngine}.
 *
 * Após o {@link StrategyEngine} obter o {@link EvaluationResult} do avaliador de decisão,
 * este componente:
 * <ul>
 *   <li>Aplica anti-repetição de sinal consecutivo do mesmo tipo, suprimindo emissões
 *       redundantes enquanto o mercado permanece na mesma condição</li>
 *   <li>Constrói o {@link Signal} final com metadata completa: símbolo, modo de decisão,
 *       dados do {@link VolatilityFilter} e campos adicionais do {@link EvaluationResult}</li>
 *   <li>Despacha o sinal ao callback registrado via {@link #onFinalSignal}, protegendo
 *       o engine contra exceções lançadas pelo callback externo</li>
 * </ul>
 *
 * O controle de anti-repetição é resetado para {@code NONE} quando o {@link VolatilityFilter}
 * bloqueia, permitindo que o próximo sinal válido seja emitido normalmente após a retomada
 * da volatilidade.
 */
public class SignalEmitter {

    private static final Logger log = LoggerFactory.getLogger(SignalEmitter.class);

    private final String symbol;
    private final DecisionMode decisionMode;
    private final VolatilityFilter volatilityFilter;

    /** Callback que recebe os sinais finais; pode ser nulo se nenhum handler foi registrado. */
    private volatile Consumer<Signal> onFinalSignal;

    /** Tipo do último sinal emitido; usado para suprimir sinais consecutivos do mesmo tipo. */
    private Signal.Type lastEmittedType = Signal.Type.NONE;

    /**
     * @param symbol          símbolo do ativo associado a este emitter, incluído na metadata do sinal
     * @param decisionMode    modo de decisão ativo, incluído na metadata do sinal
     * @param volatilityFilter filtro de volatilidade, utilizado para incluir dados de range na metadata
     */
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
     * Registra o callback que receberá os sinais finais emitidos por este componente.
     *
     * @param handler consumer do sinal final; normalmente implementado pelo {@code SignalHandler}
     */
    public void onFinalSignal(Consumer<Signal> handler) {
        this.onFinalSignal = handler;
    }

    /**
     * Tenta emitir o sinal resultante da avaliação, aplicando o controle de anti-repetição.
     * Sinais consecutivos do mesmo tipo são suprimidos para evitar múltiplas emissões
     * enquanto o mercado permanece na mesma condição técnica.
     * Quando o resultado não contém sinal ({@link EvaluationResult#hasSignal()} retorna
     * {@code false}), o método retorna imediatamente sem nenhuma ação.
     *
     * @param result   resultado da avaliação produzido pelo {@code DecisionEvaluator}
     * @param snapshot snapshot imutável dos candles no momento da avaliação
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
     * Reseta o controle de anti-repetição para {@link Signal.Type#NONE}.
     * Invocado pelo {@link StrategyEngine} quando o {@link VolatilityFilter} bloqueia,
     * garantindo que o próximo sinal válido após a retomada da volatilidade seja emitido.
     */
    public void resetLastEmitted() {
        lastEmittedType = Signal.Type.NONE;
    }

    // ═══════════════════════════════════════════════════════════════
    // Construção do sinal
    // ═══════════════════════════════════════════════════════════════

    /**
     * Constrói o {@link Signal} final com timestamp e preço do candle mais recente
     * do snapshot e com a metadata completa da avaliação.
     *
     * @param result   resultado da avaliação com tipo, nome da estratégia e metadata parcial
     * @param snapshot candles disponíveis no momento da avaliação
     * @return sinal final imutável pronto para despacho
     */
    private Signal buildSignal(EvaluationResult result, List<Bar> snapshot) {
        Bar last = snapshot.get(snapshot.size() - 1);
        Map<String, Object> metadata = buildMetadata(result, snapshot);

        return result.signalType() == Signal.Type.BUY
                ? Signal.buy(result.strategyName(), last.timestamp(),
                last.close(), metadata)
                : Signal.sell(result.strategyName(), last.timestamp(),
                last.close(), metadata);
    }

    /**
     * Constrói o mapa de metadata do sinal combinando os campos base do engine
     * com os campos específicos do {@link EvaluationResult}.
     * Os campos do resultado sobrescrevem campos base de mesmo nome quando há conflito.
     * O mapa resultante é imutável.
     *
     * @param result   resultado da avaliação com metadata específica do modo de decisão
     * @param snapshot candles utilizados para extrair os dados do {@link VolatilityFilter}
     * @return mapa imutável com todos os campos de metadata do sinal
     */
    private Map<String, Object> buildMetadata(
            EvaluationResult result,
            List<Bar> snapshot
    ) {
        Map<String, Object> metadata = new HashMap<>();

        metadata.put("symbol",          symbol);
        metadata.put("decisionMode",    decisionMode.name());
        metadata.put("range",           volatilityFilter.getCurrentRange(snapshot));
        metadata.put("avgRange",        volatilityFilter.getAverageRange(snapshot));
        metadata.put("rangeMultiplier", volatilityFilter.getRangeMultiplier());

        // Campos do resultado sobrescrevem os base em caso de conflito de chave
        metadata.putAll(result.metadata());

        return Map.copyOf(metadata);
    }

    // ═══════════════════════════════════════════════════════════════
    // Anti-repetição
    // ═══════════════════════════════════════════════════════════════

    /**
     * Verifica se o sinal é do mesmo tipo que o último emitido, configurando supressão.
     * Registra log de debug quando o sinal é suprimido.
     *
     * @param type     tipo do sinal candidato à emissão
     * @param snapshot candles utilizados para extrair o timestamp para o log
     * @return {@code true} se o sinal deve ser suprimido por repetição
     */
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

    /**
     * Despacha o sinal ao callback registrado, isolando o engine de exceções
     * lançadas pelo código externo do callback.
     *
     * @param signal  sinal final construído para despacho
     * @param snapshot candles utilizados para extrair o timestamp em caso de erro no callback
     */
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