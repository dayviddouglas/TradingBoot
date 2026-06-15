package com.github.dayviddouglas.TradingBot.engine.confluence;

import com.github.dayviddouglas.TradingBot.model.Signal;

/**
 * Encapsula os critérios de validação da decisão de confluência ponderada.
 *
 * Define os três limiares que devem ser simultaneamente satisfeitos para que o
 * {@link WeightedConfluenceEvaluator} emita um sinal operacional:
 * <ol>
 *   <li>Score da direção predominante {@code >=} {@code minDecisionScore}</li>
 *   <li>Score da direção oposta {@code <=} {@code maxOppositeScore}</li>
 *   <li>Quantidade de votos na direção predominante {@code >=} {@code minStrategiesInDirection}</li>
 * </ol>
 *
 * A instância {@link #DEFAULT} é calibrada para o estado atual do projeto:
 * com 2 estratégias de reversão em regime {@code RANGING} (peso 1.4 cada),
 * ambas precisam votar na mesma direção para atingir o score mínimo de 2.4.
 * Um único voto resulta em score 1.4, abaixo do limiar, e nenhum sinal é emitido.
 *
 * @param minDecisionScore         score mínimo necessário na direção predominante
 * @param maxOppositeScore         score máximo tolerado na direção oposta
 * @param minStrategiesInDirection quantidade mínima de estratégias votando na direção final
 */
public record ConfluenceRule(
        double minDecisionScore,
        double maxOppositeScore,
        int minStrategiesInDirection
) {
    /**
     * Regra padrão calibrada para o estado atual do projeto.
     * Com 2 estratégias de reversão em {@code RANGING} (peso 1.4 cada):
     * ambas {@code BUY} resultam em {@code buyScore = 2.8 >= 2.4} (aprovado);
     * apenas uma {@code BUY} resulta em {@code buyScore = 1.4 < 2.4} (rejeitado).
     */
    public static final ConfluenceRule DEFAULT =
            new ConfluenceRule(2.4, 0.9, 2);

    /**
     * Verifica se os scores e contagens do acumulador atendem aos critérios para emitir sinal {@code BUY}.
     *
     * @param accumulator acumulador com scores e contagens de votos por direção
     * @return {@code true} se os três critérios de BUY forem satisfeitos simultaneamente
     */
    public boolean isBuyValid(ScoreAccumulator accumulator) {
        return accumulator.getBuyScore() >= minDecisionScore
                && accumulator.getSellScore() <= maxOppositeScore
                && accumulator.getBuyCount() >= minStrategiesInDirection;
    }

    /**
     * Verifica se os scores e contagens do acumulador atendem aos critérios para emitir sinal {@code SELL}.
     *
     * @param accumulator acumulador com scores e contagens de votos por direção
     * @return {@code true} se os três critérios de SELL forem satisfeitos simultaneamente
     */
    public boolean isSellValid(ScoreAccumulator accumulator) {
        return accumulator.getSellScore() >= minDecisionScore
                && accumulator.getBuyScore() <= maxOppositeScore
                && accumulator.getSellCount() >= minStrategiesInDirection;
    }

    /**
     * Resolve o tipo final do sinal aplicando os critérios de {@code BUY} e {@code SELL} em sequência.
     * {@code BUY} tem prioridade sobre {@code SELL} em caso de empate, situação improvável
     * dado o critério de {@code maxOppositeScore} que limita o score da direção oposta.
     *
     * @param accumulator acumulador com scores e contagens de votos por direção
     * @return {@link Signal.Type#BUY} ou {@link Signal.Type#SELL} se os critérios forem atingidos,
     *         {@link Signal.Type#NONE} caso contrário
     */
    public Signal.Type resolve(ScoreAccumulator accumulator) {
        if (isBuyValid(accumulator)) return Signal.Type.BUY;
        if (isSellValid(accumulator)) return Signal.Type.SELL;
        return Signal.Type.NONE;
    }

    /**
     * Formata os parâmetros desta regra em string compacta para logs de diagnóstico.
     *
     * @return string formatada com os três parâmetros da regra
     */
    public String toLogString() {
        return String.format(
                "minDecisionScore=%.1f | maxOppositeScore=%.1f " +
                        "| minStrategiesInDirection=%d",
                minDecisionScore, maxOppositeScore, minStrategiesInDirection
        );
    }
}