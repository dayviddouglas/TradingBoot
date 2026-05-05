package com.github.dayviddouglas.TradingBot.engine;

import com.github.dayviddouglas.TradingBot.model.Signal;

import java.util.List;

/**
 * Resultado final da avaliação de confluência ponderada.
 *
 * Este record encapsula todos os dados da decisão tomada pelo
 * WeightedConfluenceEvaluator quando o DecisionMode é CONFLUENCE.
 *
 * Records (Java 16+) geram automaticamente:
 * - Construtor canônico
 * - Getters (acessados pelo nome do campo: finalType(), regime(), etc.)
 * - equals(), hashCode() e toString()
 * - Imutabilidade (todos os campos são final)
 *
 * Isso garante thread-safety natural, já que a instância é imutável
 * após a criação e pode ser compartilhada entre threads sem sincronização.
 *
 * Campos:
 * @param finalType tipo do sinal final (BUY, SELL ou NONE)
 * @param regime regime de mercado classificado (TRENDING, RANGING, CHOPPY)
 * @param buyScore soma ponderada dos votos de compra
 * @param sellScore soma ponderada dos votos de venda
 * @param decisionStrategies nomes das estratégias que votaram na direção final
 * @param weightedVotes lista de todos os votos ponderados individuais
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
     * Verifica se a decisão de confluência resultou em um sinal válido.
     *
     * Uma decisão é considerada válida quando o finalType é BUY ou SELL,
     * indicando que a confluência atingiu o score mínimo necessário
     * sem violação dos limites de oposição.
     *
     * Retorna false para NONE, que indica:
     * - score insuficiente
     * - conflito entre estratégias
     * - regime CHOPPY (bloqueado antes da avaliação)
     *
     * @return true se a decisão é operável (BUY ou SELL)
     */
    public boolean isValid() {
        return finalType != null && finalType != Signal.Type.NONE;
    }
}