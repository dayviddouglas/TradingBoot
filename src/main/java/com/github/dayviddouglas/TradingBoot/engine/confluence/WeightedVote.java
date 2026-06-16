package com.github.dayviddouglas.TradingBoot.engine.confluence;

import com.github.dayviddouglas.TradingBoot.model.Signal;

/**
 * Representa o voto ponderado de uma estratégia individual durante a avaliação
 * de confluência realizada pelo {@link WeightedConfluenceEvaluator}.
 *
 * Cada instância registra qual estratégia votou, em qual direção e qual peso
 * foi aplicado ao voto conforme o regime de mercado classificado e o
 * {@link StrategyWeightProfile}.
 *
 * Utilizado pelo {@link ScoreAccumulator} para:
 * <ul>
 *   <li>Acumular votos durante a avaliação e calcular os scores ponderados</li>
 *   <li>Registrar no metadata do {@link ConfluenceDecision} para rastreabilidade operacional</li>
 *   <li>Gerar logs de diagnóstico via {@code toString()} automático do record</li>
 * </ul>
 *
 * Exemplo em regime {@code RANGING} com duas estratégias de reversão (peso 1.4 cada):
 * {@code WeightedVote("BollingerMeanReversion", BUY, 1.4)} e
 * {@code WeightedVote("ZScoreMeanReversion", BUY, 1.4)} resultam em
 * {@code buyScore = 2.8}, suficiente para superar o {@code minDecisionScore}
 * da {@link ConfluenceRule#DEFAULT}.
 *
 * @param strategyName nome da estratégia; corresponde ao retorno de {@code strategy.name()}
 * @param signalType   tipo do sinal gerado pela estratégia: {@code BUY} ou {@code SELL}
 * @param weight       peso aplicado ao voto conforme o regime e o {@link StrategyWeightProfile}
 */
public record WeightedVote(
        String strategyName,
        Signal.Type signalType,
        double weight
) {
}