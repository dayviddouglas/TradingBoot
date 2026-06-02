package com.github.dayviddouglas.TradingBot.engine;

import java.time.Instant;

/**
 * Snapshot imutável das métricas técnicas que fundamentaram uma
 * classificação de regime.
 *
 * Este record serve como "prova de trabalho" da decisão de regime:
 * em vez de apenas saber que o regime é RANGING, é possível auditar
 * exatamente quais valores de ATR, EMA e Efficiency Ratio levaram
 * àquela classificação.
 *
 * Atualização v5.4.2:
 * Campo marketTimestamp adicionado para registrar o timestamp do
 * último candle da janela de classificação. Esse campo representa
 * o momento REAL de mercado em que o regime foi detectado, em vez
 * do Instant.now() do processamento que gerava timestamps artificiais
 * durante o warm-up histórico.
 *
 * Contexto científico:
 * Os campos refletem os três pilares da classificação de regime
 * definidos em [Andersen & Bollerslev, 1997]:
 * - atrRatio: intensidade da volatilidade relativa ao baseline
 * - emaDistance: presença ou ausência de direção direcional
 * - efficiency: linearidade do movimento (Kaufman Efficiency Ratio)
 *
 * Usado por:
 * - RegimeChangeEvent: snapshot no momento da transição
 * - RegimeStateTracker: avaliação de persistência de candidato
 * - MarketRegimeMonitor: log de diagnóstico a cada classificação
 *
 * @param atrFast          ATR de curto prazo
 * @param atrBase          ATR de referência/baseline
 * @param atrRatio         razão atrFast / atrBase
 * @param emaDistance      distância absoluta entre EMA rápida e lenta
 * @param efficiency       Efficiency Ratio do movimento
 * @param regime           regime classificado com base nessas métricas
 * @param marketTimestamp  timestamp do último candle da janela de
 *                         classificação — representa o momento real
 *                         de mercado da detecção do regime (v5.4.2)
 */
public record RegimeMetrics(
        double      atrFast,
        double      atrBase,
        double      atrRatio,
        double      emaDistance,
        double      efficiency,
        MarketRegime regime,
        Instant     marketTimestamp
) {

    /**
     * Verifica se todas as métricas são válidas para análise.
     *
     * Métricas inválidas ocorrem quando há dados insuficientes para
     * calcular os indicadores (histórico menor que o período requerido).
     *
     * @return true se todos os valores são finitos e o baseline é positivo
     */
    public boolean isValid() {
        return Double.isFinite(atrFast)
                && Double.isFinite(atrBase)
                && Double.isFinite(atrRatio)
                && Double.isFinite(emaDistance)
                && Double.isFinite(efficiency)
                && atrBase > 0.0
                && marketTimestamp != null;
    }

    /**
     * Retorna descrição compacta para logs operacionais.
     *
     * @return string formatada com todas as métricas
     */
    public String toLogString() {
        return String.format(
                "regime=%s | atrFast=%.6f | atrBase=%.6f | atrRatio=%.3f "
                        + "| emaDistance=%.6f | efficiency=%.3f "
                        + "| marketTime=%s",
                regime, atrFast, atrBase, atrRatio,
                emaDistance, efficiency,
                marketTimestamp != null ? marketTimestamp : "N/A"
        );
    }
}