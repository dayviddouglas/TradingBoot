package com.github.dayviddouglas.TradingBoot.exceptions;

/**
 * Lançada pelo {@link com.github.dayviddouglas.TradingBoot.deriv.trade.execution.TradeExecutor}
 * quando a execução de um contrato falha definitivamente.
 *
 * Encapsula erros da API Deriv, timeouts e respostas inválidas ocorridos
 * durante o ciclo {@code proposal → ROI check → buy}. Após o número máximo
 * de retentativas para erros recuperáveis (como {@code PriceMoved}),
 * esta exceção é lançada para sinalizar falha definitiva ao
 * {@link com.github.dayviddouglas.TradingBoot.deriv.DerivTradeService},
 * que a encaminha ao
 * {@link com.github.dayviddouglas.TradingBoot.deriv.trade.context.TradeErrorHandler}
 * para classificação e tratamento.
 *
 * Estende {@link RuntimeException} pois erros de execução são imprevisíveis
 * e não fazem parte do fluxo normal de operação.
 */
public class TradeExecutionException extends RuntimeException {

    /**
     * @param message descrição do erro que causou a falha definitiva da execução
     */
    public TradeExecutionException(String message) {
        super(message);
    }

    /**
     * @param message descrição do erro que causou a falha definitiva da execução
     * @param cause   exceção original que originou esta falha
     */
    public TradeExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}