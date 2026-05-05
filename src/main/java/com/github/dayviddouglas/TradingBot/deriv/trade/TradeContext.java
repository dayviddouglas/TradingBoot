package com.github.dayviddouglas.TradingBot.deriv.trade;

import com.github.dayviddouglas.TradingBot.model.Signal;

import java.util.List;
import java.util.Objects;

/**
 * DTO imutável que encapsula o contexto completo de uma operação de trade.
 *
 * Carrega todos os dados necessários para:
 * - Executar a operação (contractType, amount, currency, duration)
 * - Filtrar por ROI mínimo (minRoiPercent)
 * - Registrar no relatório (decisionMode, strategy, regime, etc.)
 *
 * Criado a partir do Signal e do StrategiesProfile antes da execução,
 * eliminando a passagem de múltiplos parâmetros soltos entre métodos.
 *
 * Atualização v5.4:
 * Campo minRoiPercent adicionado para carregar o ROI mínimo configurado
 * por ativo no strategies.json via TradeConfig. Antes o valor era
 * hardcoded em 35.0 no TradeExecutor (SUG-02 implementado).
 *
 * @param symbol             símbolo do ativo
 * @param contractType       tipo de contrato Deriv (CALL ou PUT)
 * @param amount             stake normalizado para 2 casas decimais
 * @param currency           moeda do stake
 * @param duration           duração do contrato
 * @param durationUnit       unidade de duração (s, m, h, d)
 * @param decisionMode       modo de decisão usado (VOTING, CONFLUENCE, etc.)
 * @param strategy           nome da estratégia final
 * @param decisionStrategies estratégias que participaram da decisão
 * @param regime             regime de mercado no momento do sinal
 * @param signalType         tipo do sinal original (BUY ou SELL)
 * @param minRoiPercent      ROI mínimo aceitável configurado por ativo
 */
public record TradeContext(
        String symbol,
        String contractType,
        double amount,
        String currency,
        int duration,
        String durationUnit,
        String decisionMode,
        String strategy,
        List<String> decisionStrategies,
        String regime,
        Signal.Type signalType,
        double minRoiPercent
) {
    public TradeContext {
        Objects.requireNonNull(symbol,       "symbol is required");
        Objects.requireNonNull(contractType, "contractType is required");
        Objects.requireNonNull(currency,     "currency is required");
        Objects.requireNonNull(signalType,   "signalType is required");

        decisionStrategies = decisionStrategies != null
                ? List.copyOf(decisionStrategies)
                : List.of();

        // Garante que minRoiPercent nunca seja negativo ou zero
        if (minRoiPercent <= 0) {
            minRoiPercent = 70.0;
        }
    }
}