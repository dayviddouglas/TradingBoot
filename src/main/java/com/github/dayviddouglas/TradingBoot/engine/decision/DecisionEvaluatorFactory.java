package com.github.dayviddouglas.TradingBoot.engine.decision;

import com.github.dayviddouglas.TradingBoot.engine.confluence.ConfluenceEvaluator;

/**
 * Responsável por criar o {@link DecisionEvaluator} correspondente ao {@link DecisionMode} informado.
 *
 * Centraliza a criação dos avaliadores, isolando o
 * {@link com.github.dayviddouglas.TradingBoot.engine.core.StrategyEngine} das implementações concretas.
 * Para adicionar um novo modo de decisão, basta criar a implementação de {@link DecisionEvaluator}
 * e registrar o {@code case} correspondente no método {@link #create}, sem alterar o engine.
 *
 * Os avaliadores são criados sem estado a cada chamada de {@link #create},
 * o que é seguro pois todas as implementações atuais são stateless.
 *
 * Esta é uma classe utilitária final e não instanciável.
 */
public final class DecisionEvaluatorFactory {

    private DecisionEvaluatorFactory() {
    }

    /**
     * Cria o {@link DecisionEvaluator} correspondente ao modo especificado.
     *
     * Mapeamento atual:
     * <ul>
     *   <li>{@link DecisionMode#SINGLE_STRATEGY} → {@code SingleStrategyEvaluator}</li>
     *   <li>{@link DecisionMode#VOTING} → {@code VotingEvaluator}</li>
     *   <li>{@link DecisionMode#CONFLUENCE} → {@code ConfluenceEvaluator}</li>
     * </ul>
     *
     * @param mode modo de decisão lido do strategies.json via {@link com.github.dayviddouglas.TradingBoot.config.strategy.StrategiesProfile}
     * @return implementação de {@link DecisionEvaluator} correspondente ao modo
     * @throws IllegalArgumentException se o modo não possuir implementação registrada
     */
    public static DecisionEvaluator create(DecisionMode mode) {
        return switch (mode) {
            case SINGLE_STRATEGY -> new SingleStrategyEvaluator();
            case VOTING          -> new VotingEvaluator();
            case CONFLUENCE      -> new ConfluenceEvaluator();
        };
    }
}