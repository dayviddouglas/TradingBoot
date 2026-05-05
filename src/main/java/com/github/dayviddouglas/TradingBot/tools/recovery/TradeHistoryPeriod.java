package com.github.dayviddouglas.TradingBot.tools.recovery;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Representa o período de consulta ao histórico de trades da Deriv.
 *
 * Encapsula tanto as datas legíveis (para logs e agrupamento) quanto
 * os epochs em segundos necessários para a requisição ao profit_table.
 *
 * O fromEpoch corresponde ao início do fromDate às 00:00 UTC.
 * O toEpoch corresponde ao início do dia SEGUINTE ao toDate às 00:00 UTC,
 * garantindo que todos os trades do último dia sejam incluídos na consulta.
 *
 * Exemplo:
 * fromDate = 2026-04-01 → fromEpoch = 1743465600
 * toDate   = 2026-04-23 → toEpoch   = 1745452800 (início de 2026-04-24)
 *
 * @param fromDate  data de início do período (inclusive)
 * @param toDate    data de fim do período (inclusive)
 * @param fromEpoch epoch seconds do início (para a API Deriv)
 * @param toEpoch   epoch seconds do fim exclusivo (para a API Deriv)
 */
public record TradeHistoryPeriod(
        LocalDate fromDate,
        LocalDate toDate,
        long fromEpoch,
        long toEpoch
) {

    /**
     * Cria um período a partir de duas datas.
     *
     * O toEpoch é calculado como o início do dia seguinte ao toDate
     * para garantir inclusão completa de todos os trades do último dia.
     *
     * @param fromDate data de início
     * @param toDate   data de fim (inclusive)
     * @return período configurado com epochs calculados
     */
    public static TradeHistoryPeriod of(LocalDate fromDate, LocalDate toDate) {
        long fromEpoch = fromDate
                .atStartOfDay(ZoneOffset.UTC)
                .toEpochSecond();

        // +1 dia para incluir o dia final completo na janela de consulta
        long toEpoch = toDate.plusDays(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toEpochSecond();

        return new TradeHistoryPeriod(fromDate, toDate, fromEpoch, toEpoch);
    }

    /**
     * Cria um período dos últimos N dias a partir de hoje.
     *
     * @param days quantidade de dias para retroagir
     * @return período dos últimos N dias
     */
    public static TradeHistoryPeriod lastDays(int days) {
        LocalDate today = LocalDate.now();
        return of(today.minusDays(days), today);
    }

    /**
     * Cria um período das últimas N semanas a partir de hoje.
     *
     * @param weeks quantidade de semanas para retroagir
     * @return período das últimas N semanas
     */
    public static TradeHistoryPeriod lastWeeks(int weeks) {
        LocalDate today = LocalDate.now();
        return of(today.minusWeeks(weeks), today);
    }

    /**
     * Cria um período dos últimos N meses a partir de hoje.
     *
     * @param months quantidade de meses para retroagir
     * @return período dos últimos N meses
     */
    public static TradeHistoryPeriod lastMonths(int months) {
        LocalDate today = LocalDate.now();
        return of(today.minusMonths(months), today);
    }

    /**
     * Cria o período padrão da ferramenta: últimos 7 dias.
     *
     * Valor conservador que cobre a maioria dos casos de recuperação
     * sem baixar dados em excesso.
     *
     * @return período dos últimos 7 dias
     */
    public static TradeHistoryPeriod defaultPeriod() {
        return lastDays(0);
    }
}