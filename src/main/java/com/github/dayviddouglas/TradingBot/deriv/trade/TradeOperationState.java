package com.github.dayviddouglas.TradingBot.deriv.trade;

/**
 * Estados possíveis de uma operação de trade por ativo.
 *
 * IDLE    → disponível para nova operação
 * PENDING → proposal/buy em andamento
 * OPEN    → contrato comprado, aguardando resultado
 *
 * A transição válida é sempre:
 * IDLE → PENDING → OPEN → IDLE
 */
public enum TradeOperationState {
    IDLE,
    PENDING,
    OPEN
}
