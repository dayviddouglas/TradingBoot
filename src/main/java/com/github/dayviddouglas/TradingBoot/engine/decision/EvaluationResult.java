package com.github.dayviddouglas.TradingBoot.engine.decision;

import com.github.dayviddouglas.TradingBoot.model.Signal;

import java.util.Map;

/**
 * Resultado imutável de uma avaliação de decisão produzida por um {@link DecisionEvaluator}.
 *
 * Encapsula tudo que o {@link com.github.dayviddouglas.TradingBoot.engine.core.StrategyEngine}
 * precisa para:
 * <ul>
 *   <li>Determinar se há sinal operável via {@link #hasSignal()}</li>
 *   <li>Construir o {@link Signal} final com tipo, nome da estratégia e metadata</li>
 *   <li>Registrar o log correspondente via {@link #logMessage()}</li>
 * </ul>
 *
 * Essa separação respeita o SRP: o avaliador decide o sinal e popula este resultado;
 * o {@link com.github.dayviddouglas.TradingBoot.engine.core.SignalEmitter} constrói
 * e emite o sinal a partir dele.
 *
 * @param signalType   tipo do sinal resultante: {@code BUY}, {@code SELL} ou {@code NONE}
 * @param strategyName nome da estratégia ou mecanismo que gerou o sinal;
 *                     exemplos: {@code "BollingerMeanReversion"}, {@code "VotingConsensus"},
 *                     {@code "WeightedConfluence"}
 * @param metadata     dados adicionais para rastreabilidade no {@link Signal} final;
 *                     exemplos: {@code regime}, {@code buyScore}, {@code decisionStrategies}
 * @param logMessage   mensagem de log pré-formatada para registro do sinal final
 */
public record EvaluationResult(
        Signal.Type signalType,
        String strategyName,
        Map<String, Object> metadata,
        String logMessage
) {

    /**
     * Verifica se a avaliação produziu um sinal operável.
     * Retorna {@code false} quando {@code signalType} for {@code null} ou {@code NONE}.
     *
     * @return {@code true} se o sinal for {@code BUY} ou {@code SELL}
     */
    public boolean hasSignal() {
        return signalType != null && signalType != Signal.Type.NONE;
    }

    /**
     * Cria um {@link EvaluationResult} representando ausência de sinal.
     * Utilizado pelos avaliadores quando nenhuma condição de entrada é identificada,
     * padronizando a criação do resultado vazio e evitando duplicação de código.
     *
     * @return resultado com tipo {@code NONE} e campos de texto e metadata vazios
     */
    public static EvaluationResult noSignal() {
        return new EvaluationResult(Signal.Type.NONE, "", Map.of(), "");
    }
}