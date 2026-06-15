package com.github.dayviddouglas.TradingBot.engine.regime;

import java.time.Instant;

/**
 * Evento imutável que representa uma transição confirmada de regime de mercado.
 *
 * Uma mudança de regime só é registrada como evento após passar pelo filtro de
 * persistência do {@link RegimeStateTracker}: o novo regime deve ser classificado
 * consecutivamente por {@code CONFIRMATION_THRESHOLD} avaliações (padrão: 3 avaliações
 * × 15 candles = 45 minutos de confirmação).
 *
 * Contém dois timestamps com propósitos distintos:
 * <ul>
 *   <li>{@code timestamp} — momento real de mercado da confirmação, extraído do
 *       {@link RegimeMetrics#marketTimestamp()} do último candle da janela analisada.
 *       Garante que os relatórios de regime utilizem o tempo real de mercado da detecção,
 *       em vez do tempo de processamento, tornando o relatório coerente durante o
 *       warm-up histórico</li>
 *   <li>{@code processingTimestamp} — momento em que o sistema processou a confirmação,
 *       útil para diagnóstico da latência entre o evento de mercado e o processamento</li>
 * </ul>
 *
 * @param timestamp           momento real de mercado da confirmação (timestamp do último candle)
 * @param processingTimestamp momento em que o sistema processou a confirmação
 * @param symbol              símbolo do ativo que mudou de regime
 * @param previousRegime      regime confirmado imediatamente antes da transição
 * @param currentRegime       novo regime confirmado após o filtro de persistência
 * @param metrics             snapshot das métricas da avaliação decisiva que completou a confirmação
 */
public record RegimeChangeEvent(
        Instant       timestamp,
        Instant       processingTimestamp,
        String        symbol,
        MarketRegime  previousRegime,
        MarketRegime  currentRegime,
        RegimeMetrics metrics
) {

    /**
     * Formata os dados do evento em string compacta para uso nos logs operacionais.
     *
     * @return string com símbolo, direção da transição, timestamps e métricas da avaliação decisiva
     */
    public String toLogString() {
        return String.format(
                "REGIME CHANGE | symbol=%s | %s → %s "
                        + "| marketTime=%s | processingTime=%s | %s",
                symbol,
                previousRegime,
                currentRegime,
                timestamp,
                processingTimestamp,
                metrics.toLogString()
        );
    }
}