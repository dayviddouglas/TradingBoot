package com.github.dayviddouglas.TradingBoot.deriv.trade.execution;

/**
 * Resultado imutável de uma tentativa de execução de trade pelo {@code TradeExecutor}.
 *
 * Representa um dos três desfechos possíveis de uma execução:
 * <ul>
 *   <li>{@link TradeExecutionStatus#SUCCESS}: contrato comprado com sucesso;
 *       {@code contractId} e {@code buyPrice} estão preenchidos</li>
 *   <li>{@link TradeExecutionStatus#SKIPPED_BY_ROI}: ROI retornado pelo proposal ficou
 *       abaixo do limiar mínimo configurado; {@code contractId} é {@code -1}
 *       e {@code buyPrice} é {@code NaN}</li>
 *   <li>{@link TradeExecutionStatus#FAILED}: falha durante a execução; resultado
 *       representado pela exceção lançada pelo {@code TradeExecutor},
 *       não por esta classe</li>
 * </ul>
 *
 * @param status      desfecho da tentativa de execução
 * @param contractId  ID do contrato aberto; presente apenas em {@code SUCCESS}, {@code -1} caso contrário
 * @param buyPrice    preço efetivo de compra; presente apenas em {@code SUCCESS}, {@code NaN} caso contrário
 */
public record TradeExecutionResult(
        TradeExecutionStatus status,
        long contractId,
        double buyPrice
) {
    /**
     * Cria um resultado de execução bem-sucedida com o contrato e preço de compra retornados pela API.
     *
     * @param contractId ID do contrato aberto
     * @param buyPrice   preço efetivo de compra
     * @return resultado com status {@link TradeExecutionStatus#SUCCESS}
     */
    public static TradeExecutionResult success(long contractId, double buyPrice) {
        return new TradeExecutionResult(TradeExecutionStatus.SUCCESS, contractId, buyPrice);
    }

    /**
     * Cria um resultado indicando que a execução foi cancelada por ROI insuficiente.
     * O {@code contractId} é definido como {@code -1} e {@code buyPrice} como {@code NaN}.
     *
     * @return resultado com status {@link TradeExecutionStatus#SKIPPED_BY_ROI}
     */
    public static TradeExecutionResult skippedByRoi() {
        return new TradeExecutionResult(TradeExecutionStatus.SKIPPED_BY_ROI, -1, Double.NaN);
    }

    /**
     * Verifica se o contrato foi comprado com sucesso.
     *
     * @return {@code true} se o status for {@link TradeExecutionStatus#SUCCESS}
     */
    public boolean isSuccess() {
        return status == TradeExecutionStatus.SUCCESS;
    }

    /**
     * Verifica se a execução foi cancelada por ROI abaixo do mínimo configurado.
     *
     * @return {@code true} se o status for {@link TradeExecutionStatus#SKIPPED_BY_ROI}
     */
    public boolean isSkippedByRoi() {
        return status == TradeExecutionStatus.SKIPPED_BY_ROI;
    }
}