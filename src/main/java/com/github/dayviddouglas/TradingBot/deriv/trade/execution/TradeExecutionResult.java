package com.github.dayviddouglas.TradingBot.deriv.trade.execution;

/**
 * Resultado imutável de uma tentativa de execução de trade.
 *
 * Três estados possíveis:
 * - SUCCESS: contrato comprado com sucesso
 * - SKIPPED_BY_ROI: ROI abaixo do mínimo aceitável
 * - FAILED: erro na execução (exceção lançada pelo TradeExecutor)
 *
 * @param status      resultado da execução
 * @param contractId  ID do contrato (presente apenas em SUCCESS)
 * @param buyPrice    preço de compra (presente apenas em SUCCESS)
 */
public record TradeExecutionResult(
        TradeExecutionStatus status,
        long contractId,
        double buyPrice
) {
    public static TradeExecutionResult success(long contractId, double buyPrice) {
        return new TradeExecutionResult(TradeExecutionStatus.SUCCESS, contractId, buyPrice);
    }

    public static TradeExecutionResult skippedByRoi() {
        return new TradeExecutionResult(TradeExecutionStatus.SKIPPED_BY_ROI, -1, Double.NaN);
    }

    public boolean isSuccess() {
        return status == TradeExecutionStatus.SUCCESS;
    }

    public boolean isSkippedByRoi() {
        return status == TradeExecutionStatus.SKIPPED_BY_ROI;
    }
}
