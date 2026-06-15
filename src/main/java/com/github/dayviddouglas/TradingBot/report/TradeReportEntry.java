package com.github.dayviddouglas.TradingBot.report;

import java.time.Instant;
import java.util.List;

/**
 * Registro imutável de uma operação de trade finalizada.
 *
 * Criado pelo {@link com.github.dayviddouglas.TradingBot.deriv.trade.monitor.TradeMonitor}
 * no momento do fechamento do contrato e encaminhado ao {@link TradeReportService}
 * para persistência em CSV, JSON e resumo diário.
 *
 * Os campos de tempo são mantidos como {@link Instant} para permitir conversão
 * flexível para qualquer fuso no momento da persistência. O {@link TradeReportService}
 * converte para horário de Brasília na escrita dos arquivos.
 *
 * @param entryTimestamp      momento da entrada na operação (UTC)
 * @param exitTimestamp       momento do fechamento do contrato (UTC)
 * @param symbol              símbolo do ativo operado (ex: {@code "frxEURUSD"})
 * @param decisionMode        modo de decisão utilizado: {@code VOTING}, {@code CONFLUENCE}
 *                            ou {@code SINGLE_STRATEGY}
 * @param strategy            nome da estratégia ou mecanismo que gerou o sinal final
 *                            (ex: {@code "VotingConsensus"}, {@code "WeightedConfluence"})
 * @param decisionStrategies  lista de estratégias que participaram da decisão
 * @param regime              regime de mercado confirmado no momento do sinal
 *                            (ex: {@code "RANGING"}, vazio quando não aplicável)
 * @param signalType          direção do sinal: {@code "BUY"} ou {@code "SELL"}
 * @param stake               valor apostado por operação
 * @param currency            moeda do stake (ex: {@code "USD"})
 * @param duration            duração configurada do contrato
 * @param durationUnit        unidade de duração: {@code "s"}, {@code "m"}, {@code "h"} ou {@code "d"}
 * @param durationMinutesReal duração real da operação em minutos, calculada entre entrada e saída
 * @param profit              lucro ou prejuízo líquido da operação
 * @param payout              valor total recebido: {@code stake + profit} em WIN, {@code 0.0} em LOSS
 * @param roiPct              retorno sobre investimento percentual: {@code profit / stake * 100}
 * @param result              classificação do resultado: {@code "WIN"} ou {@code "LOSS"}
 * @param contractId          ID do contrato na plataforma Deriv
 */
public record TradeReportEntry(
        Instant entryTimestamp,
        Instant exitTimestamp,
        String symbol,
        String decisionMode,
        String strategy,
        List<String> decisionStrategies,
        String regime,
        String signalType,
        double stake,
        String currency,
        int duration,
        String durationUnit,
        double durationMinutesReal,
        double profit,
        double payout,
        double roiPct,
        String result,
        long contractId
) {
}