package com.github.dayviddouglas.TradingBot.deriv.trade;

/**
 * Status possíveis de uma execução de trade.
 *
 * SUCCESS        → contrato comprado com sucesso
 * SKIPPED_BY_ROI → ROI abaixo do mínimo aceitável, trade cancelado
 * FAILED         → erro na execução (exceção lançada pelo TradeExecutor)
 */
public enum TradeExecutionStatus {
    SUCCESS,
    SKIPPED_BY_ROI,
    FAILED
}