package com.github.dayviddouglas.TradingBoot.engine.decision;

import com.github.dayviddouglas.TradingBoot.model.Bar;
import com.github.dayviddouglas.TradingBoot.model.Signal;
import com.github.dayviddouglas.TradingBoot.strategy.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Implementação de {@link DecisionEvaluator} para o modo {@link DecisionMode#VOTING}.
 *
 * Aplica votação conservadora por unanimidade: emite sinal final apenas quando
 * todas as estratégias habilitadas geram sinal não-{@code NONE} e concordam
 * na mesma direção. Qualquer divergência ou ausência de sinal em qualquer
 * estratégia resulta em {@link EvaluationResult#noSignal()}.
 *
 * Regras de decisão:
 * <ul>
 *   <li>Todas as estratégias devem gerar sinal não-{@code NONE}</li>
 *   <li>Todos os sinais devem concordar na mesma direção</li>
 *   <li>{@code BUY + BUY} → sinal final {@code BUY}</li>
 *   <li>{@code SELL + SELL} → sinal final {@code SELL}</li>
 *   <li>Qualquer divergência → {@code NONE}</li>
 * </ul>
 *
 * Este modo é mais conservador que {@code SINGLE_STRATEGY} (exige confirmação de todas
 * as estratégias) e mais simples que {@code CONFLUENCE} (sem pesos ou regime de mercado).
 */
public class VotingEvaluator implements DecisionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(VotingEvaluator.class);

    /**
     * Avalia os sinais de todas as estratégias habilitadas aplicando a regra de unanimidade.
     *
     * Fluxo:
     * <ol>
     *   <li>Coleta sinais válidos (não-{@code NONE}) de todas as estratégias</li>
     *   <li>Verifica se todas geraram sinal — quantidade deve ser igual a {@code strategies.size()}</li>
     *   <li>Verifica se todos os sinais concordam na mesma direção</li>
     *   <li>Retorna {@link EvaluationResult} com o sinal de unanimidade ou {@link EvaluationResult#noSignal()}</li>
     * </ol>
     *
     * @param snapshot   cópia imutável dos candles disponíveis para avaliação
     * @param strategies lista de estratégias habilitadas; mínimo de 2 para o modo VOTING
     * @param symbol     símbolo do ativo, utilizado para os logs de diagnóstico
     * @return resultado com o sinal de unanimidade ou {@link EvaluationResult#noSignal()} quando
     *         alguma estratégia não gera sinal ou há divergência de direção
     */
    @Override
    public EvaluationResult evaluate(List<Bar> snapshot, List<TradingStrategy> strategies, String symbol) {
        List<Signal> validSignals = collectValidSignals(snapshot, strategies);

        // Todas as estratégias devem gerar sinal — ausência de qualquer uma invalida a votação
        if (validSignals.size() != strategies.size()) {
            log.debug("No unanimous voting signal | symbol={} | validSignals={} enabledStrategies={}",
                    symbol, validSignals.size(), strategies.size());
            return EvaluationResult.noSignal();
        }

        Signal.Type firstType = validSignals.get(0).getType();

        // Qualquer divergência de direção invalida a votação
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
     * Coleta apenas os sinais válidos (não-{@code NONE}) retornados pelas estratégias.
     * Sinais nulos são silenciosamente ignorados.
     *
     * @param snapshot   candles disponíveis para avaliação
     * @param strategies estratégias a serem avaliadas
     * @return lista de sinais válidos coletados
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
     * @param signals      lista de sinais válidos coletados
     * @param expectedType tipo esperado para que a votação seja unânime
     * @return {@code true} se todos os sinais forem do tipo informado
     */
    private boolean isUnanimous(List<Signal> signals, Signal.Type expectedType) {
        return signals.stream().allMatch(s -> s.getType() == expectedType);
    }

    /**
     * Extrai os nomes das estratégias a partir da lista de sinais,
     * para inclusão na metadata do {@link EvaluationResult}.
     *
     * @param signals lista de sinais com estratégias identificadas
     * @return lista com os nomes das estratégias que votaram
     */
    private List<String> extractStrategyNames(List<Signal> signals) {
        return signals.stream()
                .map(Signal::getStrategy)
                .collect(Collectors.toList());
    }

    /**
     * Formata a mensagem de log para o sinal final emitido no modo {@code VOTING}.
     *
     * @param type               tipo do sinal unânime: {@code BUY} ou {@code SELL}
     * @param symbol             símbolo do ativo
     * @param decisionStrategies nomes das estratégias que votaram na direção vencedora
     * @return mensagem formatada para registro no log operacional
     */
    private String buildLogMessage(Signal.Type type, String symbol, List<String> decisionStrategies) {
        return String.format(
                "FINAL SIGNAL %s | symbol=%s | decisionMode=VOTING | decisionStrategies=%s",
                type, symbol, decisionStrategies
        );
    }
}