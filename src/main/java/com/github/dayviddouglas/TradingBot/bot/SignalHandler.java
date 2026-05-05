package com.github.dayviddouglas.TradingBot.bot;

import com.github.dayviddouglas.TradingBot.config.StrategiesProfile;
import com.github.dayviddouglas.TradingBot.engine.StrategyEngine;
import com.github.dayviddouglas.TradingBot.model.Signal;

/**
 * Interface funcional para tratamento de sinais finais emitidos pelo StrategyEngine.
 *
 * Implementada pelo MultiSymbolDerivBotRunner para despachar sinais
 * ao DerivTradeService com o contexto completo da operação.
 *
 * @FunctionalInterface garante compatibilidade com lambdas e
 * method references, facilitando o registro no PipelineRegistry.
 */
@FunctionalInterface
public interface SignalHandler {

    /**
     * Chamado quando o StrategyEngine emite um sinal final operável.
     *
     * @param profile configuração do ativo que gerou o sinal
     * @param engine  engine que emitiu o sinal (fornece barras recentes)
     * @param signal  sinal final (BUY ou SELL)
     */
    void handle(StrategiesProfile profile, StrategyEngine engine, Signal signal);
}