package com.github.dayviddouglas.TradingBot.engine;

import com.github.dayviddouglas.TradingBot.model.Signal;

/**
 * Encapsula as regras de validação da decisão de confluência.
 *
 * Define os critérios mínimos que devem ser satisfeitos para que
 * o WeightedConfluenceEvaluator emita um sinal final.
 *
 * Critérios (tripla validação):
 * 1. Score da direção >= minDecisionScore
 * 2. Score da direção oposta <= maxOppositeScore
 * 3. Quantidade de votos na direção >= minStrategiesInDirection
 *
 * Extraído do WeightedConfluenceEvaluator para respeitar SRP:
 * - WeightedConfluenceEvaluator orquestra a avaliação
 * - ConfluenceRule define e verifica os critérios de decisão
 *
 * @param minDecisionScore         score mínimo para aceitar sinal
 * @param maxOppositeScore         score máximo permitido do lado oposto
 * @param minStrategiesInDirection mínimo de estratégias na direção final
 */
public record ConfluenceRule(
        double minDecisionScore,
        double maxOppositeScore,
        int minStrategiesInDirection
) {
    /**
     * Regra padrão calibrada para o estado atual do projeto.
     *
     * Com 2 estratégias de reversão em RANGING (peso 1.4 cada):
     * - Ambas BUY: buyScore = 2.8 ≥ 2.4 ✅
     * - Apenas uma BUY: buyScore = 1.4 < 2.4 ❌
     */
    public static final ConfluenceRule DEFAULT =
            new ConfluenceRule(2.4, 0.9, 2);

    /**
     * Verifica se os scores atendem aos critérios para BUY.
     *
     * @param accumulator acumulador com scores e contagens
     * @return true se pode emitir sinal BUY
     */
    public boolean isBuyValid(ScoreAccumulator accumulator) {
        return accumulator.getBuyScore() >= minDecisionScore
                && accumulator.getSellScore() <= maxOppositeScore
                && accumulator.getBuyCount() >= minStrategiesInDirection;
    }

    /**
     * Verifica se os scores atendem aos critérios para SELL.
     *
     * @param accumulator acumulador com scores e contagens
     * @return true se pode emitir sinal SELL
     */
    public boolean isSellValid(ScoreAccumulator accumulator) {
        return accumulator.getSellScore() >= minDecisionScore
                && accumulator.getBuyScore() <= maxOppositeScore
                && accumulator.getSellCount() >= minStrategiesInDirection;
    }

    /**
     * Resolve o tipo final do sinal aplicando as regras de decisão.
     *
     * Prioridade: BUY sobre SELL em caso de empate (situação improvável
     * dado o critério de maxOppositeScore).
     *
     * @param accumulator acumulador com scores e contagens
     * @return tipo final do sinal ou NONE se nenhuma condição for satisfeita
     */
    public Signal.Type resolve(ScoreAccumulator accumulator) {
        if (isBuyValid(accumulator)) return Signal.Type.BUY;
        if (isSellValid(accumulator)) return Signal.Type.SELL;
        return Signal.Type.NONE;
    }

    /**
     * Retorna descrição dos parâmetros para logs de diagnóstico.
     */
    public String toLogString() {
        return String.format(
                "minDecisionScore=%.1f | maxOppositeScore=%.1f " +
                        "| minStrategiesInDirection=%d",
                minDecisionScore, maxOppositeScore, minStrategiesInDirection
        );
    }
}
