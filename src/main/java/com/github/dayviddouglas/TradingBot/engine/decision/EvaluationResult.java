package com.github.dayviddouglas.TradingBot.engine.decision;

import com.github.dayviddouglas.TradingBot.model.Signal;

import java.util.Map;

/**
 * Resultado imutável de uma avaliação de decisão do StrategyEngine.
 *
 * Encapsula tudo que o StrategyEngine precisa para:
 * - Construir o Signal final (tipo, nome da estratégia, metadata)
 * - Emitir o log correto (logMessage)
 * - Decidir se há sinal operável (hasSignal)
 *
 * Record Java (16+): imutável, thread-safe, com equals, hashCode
 * e toString automáticos.
 *
 * Usado como tipo de retorno de DecisionEvaluator.evaluate(), garantindo
 * que o StrategyEngine não precise conhecer os detalhes internos de cada
 * avaliador. Isso respeita o princípio de responsabilidade única (SRP):
 * - O avaliador decide o sinal
 * - O StrategyEngine emite o sinal
 *
 * @param signalType tipo do sinal resultante (BUY, SELL ou NONE)
 * @param strategyName nome da estratégia ou mecanismo gerador do sinal
 *                     (ex: "BollingerMeanReversion", "VotingConsensus", "WeightedConfluence")
 * @param metadata dados adicionais para rastreabilidade no Signal final
 *                 (ex: regime, scores, decisionStrategies)
 * @param logMessage mensagem de log pré-formatada para o sinal final
 */
public record EvaluationResult(
        Signal.Type signalType,
        String strategyName,
        Map<String, Object> metadata,
        String logMessage
) {

    /**
     * Verifica se a avaliação produziu um sinal operável.
     *
     * Retorna false quando:
     * - signalType é null
     * - signalType é NONE (nenhuma condição de entrada identificada)
     *
     * @return true se há sinal de BUY ou SELL para emitir
     */
    public boolean hasSignal() {
        return signalType != null && signalType != Signal.Type.NONE;
    }

    /**
     * Retorna uma instância de EvaluationResult representando ausência de sinal.
     *
     * Factory method estático para padronizar a criação do resultado vazio,
     * evitando duplicação de código nos avaliadores.
     *
     * @return EvaluationResult com tipo NONE e campos vazios
     */
    public static EvaluationResult noSignal() {
        return new EvaluationResult(Signal.Type.NONE, "", Map.of(), "");
    }
}
