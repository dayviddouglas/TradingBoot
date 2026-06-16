package com.github.dayviddouglas.TradingBoot.bot;

import com.github.dayviddouglas.TradingBoot.config.strategy.StrategiesProfile;
import com.github.dayviddouglas.TradingBoot.engine.core.StrategyEngine;
import com.github.dayviddouglas.TradingBoot.model.Signal;

/**
 * Interface funcional para tratamento de sinais finais emitidos pelo {@link StrategyEngine}.
 *
 * Implementada pelo {@code MultiSymbolDerivBotRunner} para receber sinais gerados
 * pelos engines de cada ativo e despachá-los ao {@code DerivTradeService} com
 * o contexto completo da operação: profile do ativo, engine e sinal produzido.
 *
 * Registrada no {@link PipelineRegistry} durante a construção dos pipelines,
 * sendo invocada via method reference ou lambda a cada sinal final emitido.
 */
@FunctionalInterface
public interface SignalHandler {

    /**
     * Invocado quando o {@link StrategyEngine} emite um sinal final operável.
     *
     * @param profile configuração do ativo que originou o sinal
     * @param engine  engine que emitiu o sinal, utilizado para obter barras recentes
     * @param signal  sinal final gerado (BUY ou SELL)
     */
    void handle(StrategiesProfile profile, StrategyEngine engine, Signal signal);
}