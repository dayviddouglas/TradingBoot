package com.github.dayviddouglas.TradingBot.engine.confluence;

import com.github.dayviddouglas.TradingBot.model.Signal;

/**
 * Representa o voto ponderado de uma estratégia na avaliação de confluência.
 *
 * Cada instância registra:
 * - Qual estratégia votou
 * - Em qual direção votou (BUY ou SELL)
 * - Qual peso foi aplicado ao voto (baseado no regime de mercado)
 *
 * Record Java (16+): gera automaticamente construtor, getters, equals,
 * hashCode e toString. A imutabilidade natural dos records garante
 * thread-safety sem necessidade de sincronização.
 *
 * Usado pelo WeightedConfluenceEvaluator para:
 * 1. Acumular votos durante a avaliação
 * 2. Registrar no metadata do sinal final para rastreabilidade
 * 3. Logs de diagnóstico (toString automático mostra todos os campos)
 *
 * Exemplo de uso no contexto de avaliação em regime RANGING:
 * - WeightedVote("BollingerMeanReversion", BUY, 1.4)
 * - WeightedVote("ZScoreMeanReversion", BUY, 1.4)
 * → buyScore = 2.8, decisão = BUY (se minDecisionScore <= 2.8)
 *
 * @param strategyName nome da estratégia (retorno de strategy.name())
 * @param signalType tipo do sinal gerado pela estratégia (BUY, SELL)
 * @param weight peso aplicado ao voto conforme o regime e StrategyWeightProfile
 */
public record WeightedVote(
        String strategyName,
        Signal.Type signalType,
        double weight
) {
}