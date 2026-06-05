package com.github.dayviddouglas.TradingBot.engine.regime;

import java.time.Instant;

/**
 * Evento imutável que representa uma transição confirmada de regime.
 *
 * Uma mudança de regime só é registrada como evento após passar pelo
 * filtro de persistência do RegimeStateTracker: o novo regime deve ser
 * classificado consecutivamente por CONFIRMATION_THRESHOLD avaliações
 * (padrão: 3 avaliações × 15 candles = 45 minutos de confirmação).
 *
 * Atualização v5.4.2:
 * O campo timestamp agora representa o momento real de mercado da
 * confirmação, extraído do marketTimestamp do RegimeMetrics.
 * Anteriormente usava Instant.now() do processamento, o que gerava
 * timestamps artificiais durante o warm-up histórico e tornava o
 * relatório de regime incoerente com o tempo real de mercado.
 *
 * O campo processingTimestamp foi adicionado para preservar o momento
 * em que o sistema processou a confirmação, útil para diagnóstico.
 *
 * @param timestamp           momento real de mercado da confirmação
 *                            (timestamp do último candle da janela)
 * @param processingTimestamp momento em que o sistema processou
 *                            a confirmação (Instant.now())
 * @param symbol              símbolo do ativo que mudou de regime
 * @param previousRegime      regime confirmado antes da transição
 * @param currentRegime       novo regime confirmado
 * @param metrics             snapshot das métricas da avaliação decisiva
 */
public record RegimeChangeEvent(
        Instant      timestamp,
        Instant      processingTimestamp,
        String       symbol,
        MarketRegime previousRegime,
        MarketRegime currentRegime,
        RegimeMetrics metrics
) {

    /**
     * Retorna descrição formatada para logs operacionais.
     *
     * @return string compacta com contexto completo da transição
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