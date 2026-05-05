package com.github.dayviddouglas.TradingBot.report;

import java.time.Instant;
import java.util.List;

/**
 * Registro imutável de uma operação de trade finalizada.
 *
 * Este record representa todos os dados de uma operação completa,
 * desde a entrada até o fechamento, incluindo contexto de decisão,
 * parâmetros operacionais e resultado financeiro.
 *
 * Record Java (16+): gera automaticamente construtor canônico, getters
 * (acessados pelo nome do campo), equals(), hashCode() e toString().
 * Todos os campos são final e imutáveis.
 *
 * Criado pelo DerivTradeService no momento do fechamento do contrato
 * (handleClosedContractFromPoc) e passado para o TradeReportService
 * para persistência em CSV, JSON e resumo diário.
 *
 * O Instant é mantido como tipo temporal (não String) para permitir
 * conversão flexível para qualquer timezone no momento da persistência.
 * O TradeReportService converte para horário de Brasília na escrita.
 *
 * Campos organizados por categoria:
 *
 * ── Tempo ──
 * @param entryTimestamp momento da entrada (Instant UTC)
 * @param exitTimestamp momento do fechamento (Instant UTC)
 *
 * ── Contexto da decisão ──
 * @param symbol símbolo do ativo operado (ex: "frxEURUSD")
 * @param decisionMode modo de decisão usado (VOTING, CONFLUENCE, SINGLE_STRATEGY)
 * @param strategy nome da estratégia final (ex: "VotingConsensus", "WeightedConfluence")
 * @param decisionStrategies lista de estratégias que participaram da decisão
 * @param regime regime de mercado no momento (ex: "RANGING", "" se não aplicável)
 * @param signalType tipo do sinal (BUY ou SELL)
 *
 * ── Parâmetros operacionais ──
 * @param stake valor apostado (pode ser reduzido pelo ATR risk)
 * @param currency moeda do stake (ex: "USD")
 * @param duration duração configurada do contrato
 * @param durationUnit unidade de duração (s, m, h, d)
 * @param durationMinutesReal duração real da operação em minutos (entrada→saída)
 *
 * ── Resultado financeiro ──
 * @param profit lucro ou prejuízo líquido
 * @param payout valor total recebido (stake + profit se WIN, 0.0 se LOSS)
 * @param roiPct retorno sobre investimento percentual (profit/stake * 100)
 * @param result classificação do resultado ("WIN" ou "LOSS")
 * @param contractId ID do contrato na plataforma Deriv
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