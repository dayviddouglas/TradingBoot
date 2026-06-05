package com.github.dayviddouglas.TradingBot.engine.confluence;

import com.github.dayviddouglas.TradingBot.model.Signal;

import java.util.ArrayList;
import java.util.List;

/**
 * Responsável por acumular votos ponderados das estratégias.
 *
 * Extraído do WeightedConfluenceEvaluator para respeitar SRP:
 * - WeightedConfluenceEvaluator orquestra a avaliação
 * - ScoreAccumulator acumula e fornece os scores
 *
 * Mantém internamente:
 * - buyScore: soma dos pesos das estratégias que votaram BUY
 * - sellScore: soma dos pesos das estratégias que votaram SELL
 * - weightedVotes: lista de votos individuais para rastreabilidade
 * - buyCount: quantidade de estratégias que votaram BUY
 * - sellCount: quantidade de estratégias que votaram SELL
 *
 * Não é thread-safe: cada avaliação deve criar uma nova instância.
 */
public class ScoreAccumulator {

    private double buyScore = 0.0;
    private double sellScore = 0.0;
    private int buyCount = 0;
    private int sellCount = 0;

    private final List<WeightedVote> votes = new ArrayList<>();

    /**
     * Registra o voto de uma estratégia com seu peso.
     *
     * Votos NONE são registrados apenas para diagnóstico
     * sem afetar os scores.
     *
     * @param strategyName nome da estratégia
     * @param signalType   tipo do sinal gerado
     * @param weight       peso da estratégia no regime atual
     */
    public void accumulate(String strategyName, Signal.Type signalType, double weight) {
        switch (signalType) {
            case BUY -> {
                buyScore += weight;
                buyCount++;
                votes.add(new WeightedVote(strategyName, Signal.Type.BUY, weight));
            }
            case SELL -> {
                sellScore += weight;
                sellCount++;
                votes.add(new WeightedVote(strategyName, Signal.Type.SELL, weight));
            }
            default -> {
                // NONE: registra apenas para diagnóstico, sem afetar scores
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Consultas
    // ═══════════════════════════════════════════════════════════════

    public double getBuyScore() { return buyScore; }
    public double getSellScore() { return sellScore; }
    public int getBuyCount() { return buyCount; }
    public int getSellCount() { return sellCount; }

    /**
     * Retorna cópia imutável dos votos para uso no ConfluenceDecision.
     */
    public List<WeightedVote> getVotes() {
        return List.copyOf(votes);
    }

    /**
     * Extrai os nomes das estratégias que votaram na direção final.
     *
     * @param finalType direção final da decisão
     * @return lista de nomes das estratégias que votaram nesta direção
     */
    public List<String> getDecisionStrategies(Signal.Type finalType) {
        return votes.stream()
                .filter(v -> v.signalType() == finalType)
                .map(WeightedVote::strategyName)
                .toList();
    }

    /**
     * Retorna resumo compacto para logs de diagnóstico.
     */
    public String toLogString() {
        return String.format(
                "buyScore=%.2f buyCount=%d | sellScore=%.2f sellCount=%d",
                buyScore, buyCount, sellScore, sellCount
        );
    }
}