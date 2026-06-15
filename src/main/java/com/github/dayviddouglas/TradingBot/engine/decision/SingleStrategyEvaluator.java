package com.github.dayviddouglas.TradingBot.engine.decision;

import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.model.Signal;
import com.github.dayviddouglas.TradingBot.strategy.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Implementação de {@link DecisionEvaluator} para o modo {@link DecisionMode#SINGLE_STRATEGY}.
 *
 * O sinal da única estratégia habilitada é promovido diretamente ao sinal final,
 * sem confluência, pesos ou classificação de regime de mercado.
 * Ideal para testar o edge de uma estratégia de forma isolada ou para operar
 * com uma tese única validada por backtest.
 *
 * Pré-condição: a lista {@code strategies} deve conter exatamente 1 estratégia.
 * Essa invariante é validada pelo
 * {@link com.github.dayviddouglas.TradingBot.engine.core.StrategyEngine} no construtor,
 * antes de criar o avaliador.
 */
public class SingleStrategyEvaluator implements DecisionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(SingleStrategyEvaluator.class);

    /**
     * Avalia o sinal da única estratégia habilitada sobre o snapshot de candles.
     *
     * Fluxo:
     * <ol>
     *   <li>Obtém a única estratégia da lista</li>
     *   <li>Invoca {@code checkSignal} com o snapshot atual</li>
     *   <li>Quando o resultado for {@code null} ou {@code NONE}, retorna {@link EvaluationResult#noSignal()}</li>
     *   <li>Quando o resultado for {@code BUY} ou {@code SELL}, constrói e retorna o {@link EvaluationResult}</li>
     * </ol>
     *
     * @param snapshot   cópia imutável dos candles disponíveis para avaliação
     * @param strategies lista com exatamente 1 estratégia habilitada
     * @param symbol     símbolo do ativo, utilizado para os logs de diagnóstico
     * @return resultado com o sinal da estratégia ou {@link EvaluationResult#noSignal()}
     */
    @Override
    public EvaluationResult evaluate(List<Bar> snapshot, List<TradingStrategy> strategies, String symbol) {
        TradingStrategy strategy = strategies.get(0);
        Signal rawSignal = strategy.checkSignal(snapshot);

        if (isNoSignal(rawSignal)) {
            log.debug("No signal in SINGLE_STRATEGY | symbol={} | strategy={}", symbol, strategy.name());
            return EvaluationResult.noSignal();
        }

        String logMessage = buildLogMessage(rawSignal.getType(), symbol, strategy.name());

        return new EvaluationResult(
                rawSignal.getType(),
                strategy.name(),
                Map.of("decisionStrategies", List.of(strategy.name())),
                logMessage
        );
    }

    /**
     * Verifica se o sinal retornado pela estratégia é nulo ou do tipo {@code NONE}.
     *
     * @param signal sinal retornado pela estratégia
     * @return {@code true} se não há sinal operável
     */
    private boolean isNoSignal(Signal signal) {
        return signal == null || signal.getType() == Signal.Type.NONE;
    }

    /**
     * Formata a mensagem de log para o sinal final emitido no modo {@code SINGLE_STRATEGY}.
     *
     * @param type         tipo do sinal: {@code BUY} ou {@code SELL}
     * @param symbol       símbolo do ativo
     * @param strategyName nome da estratégia que gerou o sinal
     * @return mensagem formatada para registro no log operacional
     */
    private String buildLogMessage(Signal.Type type, String symbol, String strategyName) {
        return String.format(
                "FINAL SIGNAL %s | symbol=%s | decisionMode=SINGLE_STRATEGY | strategy=%s",
                type, symbol, strategyName
        );
    }
}