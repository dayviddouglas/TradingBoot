package com.github.dayviddouglas.TradingBot.deriv.trade.validation;

/**
 * Representa os possíveis desfechos de uma validação de trade realizada pelo {@link TradeValidator}.
 *
 * <ul>
 *   <li>{@code PROCEED}: todas as camadas de validação foram aprovadas; o trade pode ser executado</li>
 *   <li>{@code SKIP}: uma validação rejeitou o sinal; o motivo é registrado em nível {@code debug}
 *       e o fluxo é interrompido sem lançar exceção</li>
 *   <li>{@code IGNORE}: o ativo já possui uma operação em andamento; o sinal é descartado
 *       silenciosamente sem impacto no estado do sistema</li>
 * </ul>
 */
public enum ValidationStatus {
    PROCEED,
    SKIP,
    IGNORE
}