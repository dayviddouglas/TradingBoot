package com.github.dayviddouglas.TradingBot.backtest;



/**
 * Modo de execução do backtest.
 *
 * CONFLUENCE:
 * - usa StrategyEngine
 * - exige confluência / score / regime
 *
 * SINGLE_STRATEGY:
 * - testa cada estratégia diretamente
 * - sem passar pelo motor de confluência
 */
public enum BacktestMode {
    CONFLUENCE,
    SINGLE_STRATEGY
}