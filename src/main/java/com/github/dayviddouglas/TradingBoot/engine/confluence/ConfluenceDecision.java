package com.github.dayviddouglas.TradingBoot.engine.confluence;

import com.github.dayviddouglas.TradingBoot.engine.regime.MarketRegime;
import com.github.dayviddouglas.TradingBoot.model.Signal;

import java.util.List;

/**
 * Resultado imutável da avaliação de confluência ponderada realizada pelo
 * {@link WeightedConfluenceEvaluator}.
 *
 * Encapsula todos os dados da decisão tomada no modo {@code CONFLUENCE}:
 * o tipo do sinal final, o regime de mercado classificado, os scores ponderados
 * de compra e venda, as estratégias que votaram na direção vencedora e a lista
 * completa de votos individuais ponderados.
 *
 * O método {@link #isValid()} determina se a decisão atingiu os critérios mínimos
 * da {@link ConfluenceRule} e pode ser convertida em um sinal operacional.
 *
 * @param finalType          tipo do sinal final: {@code BUY}, {@code SELL} ou {@code NONE}
 * @param regime             regime de mercado classificado: {@code TRENDING}, {@code RANGING} ou {@code CHOPPY}
 * @param buyScore           soma ponderada dos votos de compra
 * @param sellScore          soma ponderada dos votos de venda
 * @param decisionStrategies nomes das estratégias que votaram na direção final
 * @param weightedVotes      lista de todos os votos ponderados individuais de cada estratégia
 */
public record ConfluenceDecision(
        Signal.Type finalType,
        MarketRegime regime,
        double buyScore,
        double sellScore,
        List<String> decisionStrategies,
        List<WeightedVote> weightedVotes
) {

    /**
     * Verifica se a decisão de confluência resultou em um sinal operacional válido.
     *
     * Uma decisão é considerada válida quando {@code finalType} é {@code BUY} ou {@code SELL},
     * indicando que os scores atingiram o limiar mínimo definido pela {@link ConfluenceRule}
     * sem violação dos limites de oposição.
     *
     * Retorna {@code false} quando {@code finalType} é {@code NONE}, o que indica:
     * <ul>
     *   <li>score insuficiente na direção predominante</li>
     *   <li>conflito entre estratégias (score oposto acima do limite)</li>
     *   <li>quantidade mínima de votos na direção não atingida</li>
     * </ul>
     *
     * @return {@code true} se a decisão é operável como {@code BUY} ou {@code SELL}
     */
    public boolean isValid() {
        return finalType != null && finalType != Signal.Type.NONE;
    }
}