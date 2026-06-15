package com.github.dayviddouglas.TradingBot.deriv.trade.monitor;

/**
 * Representa os estados possíveis de uma operação de trade por ativo.
 *
 * A transição válida segue a sequência linear:
 * {@code IDLE → PENDING → OPEN → IDLE}
 *
 * <ul>
 *   <li>{@code IDLE} — ativo disponível para receber novo sinal e iniciar nova operação</li>
 *   <li>{@code PENDING} — proposal ou buy em andamento; novas operações são bloqueadas</li>
 *   <li>{@code OPEN} — contrato comprado e aguardando fechamento pelo {@code TradeMonitor}</li>
 * </ul>
 */
public enum TradeOperationState {
    IDLE,
    PENDING,
    OPEN
}