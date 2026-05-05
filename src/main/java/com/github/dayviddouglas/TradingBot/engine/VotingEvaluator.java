package com.github.dayviddouglas.TradingBot.engine;

import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.model.Signal;
import com.github.dayviddouglas.TradingBot.strategy.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Avaliador de decisão para o modo VOTING (votação conservadora por unanimidade).
 *
 * Todas as estratégias devem gerar sinal E concordar na mesma direção
 * para que o sinal final seja emitido.
 *
 * Regras de decisão:
 * - Todas as estratégias devem gerar sinal não-NONE
 * - Todos os sinais devem ser na mesma direção
 * - BUY + BUY → BUY final
 * - SELL + SELL → SELL final
 * - Qualquer divergência → NONE
 *
 * Este modo é mais conservador que SINGLE_STRATEGY (exige confirmação de todas)
 * e mais simples que CONFLUENCE (sem pesos ou regime de mercado).
 *
 * Implementa DecisionEvaluator seguindo o padrão Strategy.
 */
public class VotingEvaluator implements DecisionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(VotingEvaluator.class);

    /**
     * Avalia sinais de todas as estratégias com regra de unanimidade.
     *
     * Fluxo:
     * 1. Coleta sinais válidos (não-NONE) de todas as estratégias
     * 2. Verifica se TODAS geraram sinal (quantidade == strategies.size())
     * 3. Verifica se TODOS concordam na mesma direção
     * 4. Retorna EvaluationResult com o sinal de unanimidade ou noSignal()
     *
     * @param snapshot cópia imutável dos candles
     * @param strategies lista de estratégias habilitadas (mínimo 2)
     * @param symbol símbolo do ativo para logs
     * @return resultado da avaliação por unanimidade
     */
    @Override
    public EvaluationResult evaluate(List<Bar> snapshot, List<TradingStrategy> strategies, String symbol) {
        List<Signal> validSignals = collectValidSignals(snapshot, strategies);

        // Regra 1: TODAS as estratégias devem gerar sinal não-NONE
        if (validSignals.size() != strategies.size()) {
            log.debug("No unanimous voting signal | symbol={} | validSignals={} enabledStrategies={}",
                    symbol, validSignals.size(), strategies.size());
            return EvaluationResult.noSignal();
        }

        Signal.Type firstType = validSignals.get(0).getType();

        // Regra 2: Todos os sinais devem concordar na mesma direção
        if (!isUnanimous(validSignals, firstType)) {
            log.debug("Voting conflict | symbol={} | votes={}",
                    symbol,
                    validSignals.stream().map(s -> s.getStrategy() + ":" + s.getType()).toList());
            return EvaluationResult.noSignal();
        }

        List<String> decisionStrategies = extractStrategyNames(validSignals);

        String logMessage = buildLogMessage(firstType, symbol, decisionStrategies);

        return new EvaluationResult(
                firstType,
                "VotingConsensus",
                Map.of("decisionStrategies", decisionStrategies),
                logMessage
        );
    }

    /**
     * Coleta sinais válidos (não-NONE) de todas as estratégias habilitadas.
     *
     * @param snapshot candles para avaliação
     * @param strategies estratégias a avaliar
     * @return lista de sinais válidos
     */
    private List<Signal> collectValidSignals(List<Bar> snapshot, List<TradingStrategy> strategies) {
        List<Signal> validSignals = new ArrayList<>();
        for (TradingStrategy strategy : strategies) {
            Signal signal = strategy.checkSignal(snapshot);
            if (signal != null && signal.getType() != Signal.Type.NONE) {
                validSignals.add(signal);
            }
        }
        return validSignals;
    }

    /**
     * Verifica se todos os sinais da lista concordam com o tipo esperado.
     *
     * @param signals lista de sinais a verificar
     * @param expectedType tipo esperado para unanimidade
     * @return true se todos os sinais são do tipo esperado
     */
    private boolean isUnanimous(List<Signal> signals, Signal.Type expectedType) {
        return signals.stream().allMatch(s -> s.getType() == expectedType);
    }

    /**
     * Extrai os nomes das estratégias de uma lista de sinais.
     *
     * @param signals lista de sinais com estratégias identificadas
     * @return lista de nomes de estratégias
     */
    private List<String> extractStrategyNames(List<Signal> signals) {
        return signals.stream()
                .map(Signal::getStrategy)
                .collect(Collectors.toList());
    }

    /**
     * Formata a mensagem de log para o sinal final de votação.
     *
     * @param type tipo do sinal
     * @param symbol símbolo do ativo
     * @param decisionStrategies estratégias que votaram
     * @return mensagem formatada para log
     */
    private String buildLogMessage(Signal.Type type, String symbol, List<String> decisionStrategies) {
        return String.format(
                "FINAL SIGNAL %s | symbol=%s | decisionMode=VOTING | decisionStrategies=%s",
                type, symbol, decisionStrategies
        );
    }
}
