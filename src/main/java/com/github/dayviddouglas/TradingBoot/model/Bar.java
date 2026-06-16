package com.github.dayviddouglas.TradingBoot.model;

import java.time.Instant;

/**
 * Representa um candle OHLCV para um determinado timeframe.
 *
 * O {@code timestamp} corresponde ao epoch second do horário de abertura
 * do candle em UTC, conforme retornado pela API da Deriv.
 *
 * O campo {@code volume} é sempre {@code 0.0} pois a API da Deriv não
 * fornece volume real para forex e metais.
 *
 * @param timestamp epoch second do horário de abertura do candle (UTC)
 * @param open      preço de abertura
 * @param high      preço máximo atingido no período
 * @param low       preço mínimo atingido no período
 * @param close     preço de fechamento
 * @param volume    sempre {@code 0.0} — não fornecido pela API da Deriv
 */
public record Bar(
        Instant timestamp,
        double open,
        double high,
        double low,
        double close,
        double volume
) {
}