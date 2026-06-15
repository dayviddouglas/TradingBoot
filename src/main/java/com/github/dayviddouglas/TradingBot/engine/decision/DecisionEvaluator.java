package com.github.dayviddouglas.TradingBot.engine.decision;

import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.strategy.TradingStrategy;

import java.util.List;

/**
 * Contrato para avaliadores de decisão do {@link com.github.dayviddouglas.TradingBot.engine.core.StrategyEngine}.
 *
 * Cada implementação encapsula uma estratégia de decisão diferente,
 * permitindo que o engine troque o comportamento de avaliação sem modificação:
 * <ul>
 *   <li>{@code SingleStrategyEvaluator} — modo {@link DecisionMode#SINGLE_STRATEGY}</li>
 *   <li>{@code VotingEvaluator} — modo {@link DecisionMode#VOTING}</li>
 *   <li>{@code ConfluenceEvaluator} — modo {@link DecisionMode#CONFLUENCE}</li>
 * </ul>
 *
 * O avaliador é responsável apenas por decidir o sinal a partir das estratégias e candles.
 * A emissão do sinal final é responsabilidade do
 * {@link com.github.dayviddouglas.TradingBot.engine.core.SignalEmitter}.
 *
 * Para adicionar um novo modo de decisão:
 * <ol>
 *   <li>Criar classe que implementa esta interface</li>
 *   <li>Adicionar o valor no enum {@link DecisionMode}</li>
 *   <li>Registrar o novo {@code case} no {@link DecisionEvaluatorFactory}</li>
 * </ol>
 */
public interface DecisionEvaluator {

    /**
     * Avalia as estratégias habilitadas sobre o snapshot de candles e retorna o resultado da decisão.
     *
     * @param snapshot   cópia imutável dos candles atuais para avaliação
     * @param strategies lista de estratégias habilitadas para o ativo
     * @param symbol     símbolo do ativo, utilizado para contexto nos logs
     * @return {@link EvaluationResult} com o tipo do sinal, nome da estratégia,
     *         metadata e mensagem de log; nunca {@code null}
     */
    EvaluationResult evaluate(List<Bar> snapshot, List<TradingStrategy> strategies, String symbol);
}