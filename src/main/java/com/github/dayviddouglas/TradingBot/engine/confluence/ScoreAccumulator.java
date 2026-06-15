package com.github.dayviddouglas.TradingBot.engine.confluence;

import com.github.dayviddouglas.TradingBot.model.Signal;

import java.util.ArrayList;
import java.util.List;

/**
 * Responsável por acumular os votos ponderados das estratégias durante
 * uma avaliação do modo {@code CONFLUENCE}.
 *
 * Mantém os scores somados por direção ({@code buyScore} e {@code sellScore}),
 * as contagens de estratégias por direção ({@code buyCount} e {@code sellCount})
 * e a lista completa de votos individuais para rastreabilidade.
 *
 * Votos do tipo {@code NONE} não afetam os scores nem as contagens.
 *
 * Não é thread-safe: cada avaliação de confluência deve criar uma nova instância.
 */
public class ScoreAccumulator {

    private double buyScore  = 0.0;
    private double sellScore = 0.0;
    private int    buyCount  = 0;
    private int    sellCount = 0;

    /** Lista mutável de votos individuais; entregue como cópia imutável via {@link #getVotes()}. */
    private final List<WeightedVote> votes = new ArrayList<>();

    /**
     * Registra o voto de uma estratégia com seu peso no regime atual.
     * Votos {@code BUY} incrementam {@code buyScore} e {@code buyCount}.
     * Votos {@code SELL} incrementam {@code sellScore} e {@code sellCount}.
     * Votos {@code NONE} não afetam scores nem contagens.
     *
     * @param strategyName nome da estratégia que gerou o voto
     * @param signalType   tipo do sinal gerado pela estratégia
     * @param weight       peso da estratégia para o regime atual, obtido do {@link StrategyWeightProfile}
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
                // NONE: não afeta scores nem contagens
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Consultas
    // ═══════════════════════════════════════════════════════════════

    public double getBuyScore()  { return buyScore; }
    public double getSellScore() { return sellScore; }
    public int    getBuyCount()  { return buyCount; }
    public int    getSellCount() { return sellCount; }

    /**
     * Retorna cópia imutável dos votos acumulados para uso na construção do {@link ConfluenceDecision}.
     *
     * @return lista imutável de todos os votos ponderados registrados
     */
    public List<WeightedVote> getVotes() {
        return List.copyOf(votes);
    }

    /**
     * Extrai os nomes das estratégias que votaram na direção final da decisão.
     * Utilizado pelo {@link WeightedConfluenceEvaluator} para popular o campo
     * {@code decisionStrategies} do {@link ConfluenceDecision}.
     *
     * @param finalType direção final da decisão: {@code BUY} ou {@code SELL}
     * @return lista de nomes das estratégias que votaram na direção informada
     */
    public List<String> getDecisionStrategies(Signal.Type finalType) {
        return votes.stream()
                .filter(v -> v.signalType() == finalType)
                .map(WeightedVote::strategyName)
                .toList();
    }

    /**
     * Formata os scores e contagens em string compacta para logs de diagnóstico.
     *
     * @return string formatada com buyScore, buyCount, sellScore e sellCount
     */
    public String toLogString() {
        return String.format(
                "buyScore=%.2f buyCount=%d | sellScore=%.2f sellCount=%d",
                buyScore, buyCount, sellScore, sellCount
        );
    }
}