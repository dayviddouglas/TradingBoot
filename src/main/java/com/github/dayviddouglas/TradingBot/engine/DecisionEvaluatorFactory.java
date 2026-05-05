package com.github.dayviddouglas.TradingBot.engine;

/**
 * Factory responsável por criar o DecisionEvaluator correto para cada DecisionMode.
 *
 * Centraliza a criação dos avaliadores, seguindo o princípio Open/Closed:
 * para adicionar um novo modo de decisão, basta:
 * 1. Criar a implementação de DecisionEvaluator
 * 2. Adicionar o case no switch desta factory
 * O StrategyEngine não precisa ser alterado.
 *
 * Classe utilitária final (não instanciável):
 * - Construtor privado impede instanciação
 * - Método estático de fábrica acessível diretamente
 *
 * ⚠️ Ponto de atenção: Os avaliadores são criados a cada chamada de create().
 * Como são instâncias sem estado (stateless), isso é seguro.
 * Se no futuro houver estado, considere cache ou injeção via Spring.
 */
public final class DecisionEvaluatorFactory {

    /**
     * Construtor privado impede instanciação.
     * Esta classe segue o padrão Utility Class / Static Factory.
     */
    private DecisionEvaluatorFactory() {
    }

    /**
     * Cria o avaliador de decisão correspondente ao modo especificado.
     *
     * Mapeamento atual:
     * - SINGLE_STRATEGY → SingleStrategyEvaluator
     * - VOTING          → VotingEvaluator
     * - CONFLUENCE      → ConfluenceEvaluator
     *
     * @param mode modo de decisão configurado no strategies.json
     * @return implementação de DecisionEvaluator correspondente
     * @throws IllegalArgumentException se o modo não for suportado
     */
    public static DecisionEvaluator create(DecisionMode mode) {
        return switch (mode) {
            case SINGLE_STRATEGY -> new SingleStrategyEvaluator();
            case VOTING -> new VotingEvaluator();
            case CONFLUENCE -> new ConfluenceEvaluator();
        };
    }
}
