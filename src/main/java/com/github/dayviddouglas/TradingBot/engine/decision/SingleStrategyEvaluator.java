package com.github.dayviddouglas.TradingBot.engine.decision;

import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.model.Signal;
import com.github.dayviddouglas.TradingBot.strategy.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Avaliador de decisão para o modo SINGLE_STRATEGY.
 *
 * O sinal da única estratégia habilitada vira o sinal final diretamente,
 * sem confluência, pesos ou regime de mercado.
 *
 * Ideal para:
 * - Testar o edge de uma estratégia de forma isolada
 * - Operar com tese única e claramente validada por backtest
 *
 * Pré-condição: strategies deve conter exatamente 1 estratégia.
 * Essa validação é feita pelo StrategyEngine no construtor.
 *
 * Implementa DecisionEvaluator seguindo o padrão Strategy, permitindo
 * que o StrategyEngine delegue a avaliação sem conhecer os detalhes
 * do modo SINGLE_STRATEGY.
 */
public class SingleStrategyEvaluator implements DecisionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(SingleStrategyEvaluator.class);

    /**
     * Avalia o sinal da única estratégia habilitada.
     *
     * Fluxo:
     * 1. Obtém a única estratégia da lista
     * 2. Chama checkSignal com o snapshot atual
     * 3. Se NONE: retorna EvaluationResult.noSignal()
     * 4. Se BUY/SELL: retorna EvaluationResult com o sinal e metadata
     *
     * @param snapshot cópia imutável dos candles
     * @param strategies lista com exatamente 1 estratégia
     * @param symbol símbolo do ativo para logs
     * @return resultado da avaliação
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
     * Verifica se o sinal é nulo ou do tipo NONE.
     *
     * @param signal sinal retornado pela estratégia
     * @return true se não há sinal operável
     */
    private boolean isNoSignal(Signal signal) {
        return signal == null || signal.getType() == Signal.Type.NONE;
    }

    /**
     * Formata a mensagem de log para o sinal final.
     *
     * @param type tipo do sinal
     * @param symbol símbolo do ativo
     * @param strategyName nome da estratégia
     * @return mensagem formatada para log
     */
    private String buildLogMessage(Signal.Type type, String symbol, String strategyName) {
        return String.format(
                "FINAL SIGNAL %s | symbol=%s | decisionMode=SINGLE_STRATEGY | strategy=%s",
                type, symbol, strategyName
        );
    }
}