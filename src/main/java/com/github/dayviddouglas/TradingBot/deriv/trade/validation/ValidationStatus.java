package com.github.dayviddouglas.TradingBot.deriv.trade.validation;

/**
 * Status possíveis de uma validação de trade.
 *
 * PROCEED: todas as validações passaram, trade pode ser executado
 * SKIP: uma validação falhou, trade cancelado com motivo registrado
 * IGNORE: ativo já está com operação em andamento, sinal descartado
 */
public enum ValidationStatus {
    PROCEED,
    SKIP,
    IGNORE
}
