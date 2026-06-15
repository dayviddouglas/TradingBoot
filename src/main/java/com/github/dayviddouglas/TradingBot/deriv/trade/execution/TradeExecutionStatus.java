package com.github.dayviddouglas.TradingBot.deriv.trade.execution;

/**
 * Representa os possíveis desfechos de uma tentativa de execução de trade.
 *
 * <ul>
 *   <li>{@code SUCCESS} — contrato comprado com sucesso; {@code contractId} e
 *       {@code buyPrice} estão disponíveis no {@link TradeExecutionResult}</li>
 *   <li>{@code SKIPPED_BY_ROI} — execução cancelada porque o ROI retornado pelo
 *       proposal ficou abaixo do limiar mínimo configurado no {@code TradeConfig}</li>
 *   <li>{@code FAILED} — falha durante a execução; representada pela exceção lançada
 *       pelo {@code TradeExecutor}, não por um {@link TradeExecutionResult}</li>
 * </ul>
 */
public enum TradeExecutionStatus {
    SUCCESS,
    SKIPPED_BY_ROI,
    FAILED
}