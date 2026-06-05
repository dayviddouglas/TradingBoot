package com.github.dayviddouglas.TradingBot.engine.decision;

import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.strategy.TradingStrategy;

import java.util.List;

/**
 * Interface que define o contrato para avaliadores de decisão do StrategyEngine.
 *
 * Implementa o padrão Strategy: permite trocar a lógica de decisão
 * (SINGLE_STRATEGY, VOTING, CONFLUENCE) sem alterar o StrategyEngine.
 * Cada modo de decisão é uma implementação separada desta interface,
 * respeitando o princípio Open/Closed:
 * - Aberto para extensão: novo modo = nova implementação
 * - Fechado para modificação: StrategyEngine não precisa mudar
 *
 * Para adicionar um novo modo de decisão:
 * 1. Criar classe que implementa DecisionEvaluator
 * 2. Adicionar o modo no enum DecisionMode
 * 3. Registrar no DecisionEvaluatorFactory
 * 4. Nenhuma alteração no StrategyEngine é necessária
 *
 * Implementações disponíveis:
 * - SingleStrategyEvaluator → DecisionMode.SINGLE_STRATEGY
 * - VotingEvaluator         → DecisionMode.VOTING
 * - ConfluenceEvaluator     → DecisionMode.CONFLUENCE
 */
public interface DecisionEvaluator {

    /**
     * Avalia as estratégias habilitadas e retorna o resultado da decisão.
     *
     * O avaliador é responsável apenas por decidir o sinal.
     * O StrategyEngine é responsável por emitir o sinal via callback.
     * Essa separação respeita o SRP (Single Responsibility Principle).
     *
     * @param snapshot cópia imutável dos candles atuais para avaliação
     * @param strategies lista de estratégias habilitadas para este ativo
     * @param symbol símbolo do ativo para contexto de logs
     * @return EvaluationResult contendo o sinal, metadata e mensagem de log
     */
    EvaluationResult evaluate(List<Bar> snapshot, List<TradingStrategy> strategies, String symbol);
}