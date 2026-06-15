package com.github.dayviddouglas.TradingBot.deriv.trade.context;

import com.github.dayviddouglas.TradingBot.model.Signal;

import java.util.List;
import java.util.Objects;

/**
 * DTO imutável que encapsula o contexto completo de uma operação de trade.
 *
 * Carrega todos os dados necessários para:
 * - Executar a operação ({@code contractType}, {@code amount}, {@code currency}, {@code duration})
 * - Filtrar por ROI mínimo ({@code minRoiPercent})
 * - Registrar no relatório ({@code decisionMode}, {@code strategy}, {@code regime}, etc.)
 *
 * Construído pelo {@link TradeContextFactory} a partir do {@code Signal} emitido pelo engine
 * e do {@code StrategiesProfile} do ativo, eliminando a passagem de múltiplos parâmetros
 * soltos entre os componentes do fluxo de execução.
 *
 * O canonical constructor aplica validações de não-nulidade nos campos obrigatórios,
 * garante imutabilidade da lista {@code decisionStrategies} e aplica fallback de
 * {@code 70.0} para {@code minRoiPercent} quando o valor recebido for inválido.
 *
 * @param symbol             símbolo do ativo
 * @param contractType       tipo de contrato Deriv: {@code CALL} ou {@code PUT}
 * @param amount             stake normalizado para 2 casas decimais
 * @param currency           moeda do stake
 * @param duration           duração numérica do contrato
 * @param durationUnit       unidade de duração: {@code s}, {@code m}, {@code h} ou {@code d}
 * @param decisionMode       modo de decisão utilizado: {@code VOTING}, {@code CONFLUENCE}, etc.
 * @param strategy           nome da estratégia que gerou o sinal final
 * @param decisionStrategies estratégias que participaram da decisão
 * @param regime             regime de mercado confirmado no momento do sinal
 * @param signalType         tipo do sinal original: {@code BUY} ou {@code SELL}
 * @param minRoiPercent      ROI mínimo aceitável configurado por ativo no strategies.json
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

        // Garante lista imutável; substitui null por lista vazia
        decisionStrategies = decisionStrategies != null
                ? List.copyOf(decisionStrategies)
                : List.of();

        // Garante que minRoiPercent nunca seja negativo ou zero
        if (minRoiPercent <= 0) {
            minRoiPercent = 70.0;
        }
    }
}