package com.github.dayviddouglas.TradingBot.engine.regime;

import java.time.Instant;

/**
 * Snapshot imutável das métricas técnicas que fundamentaram uma classificação de regime.
 *
 * Serve como "prova de trabalho" da decisão: em vez de apenas registrar o regime resultante,
 * permite auditar exatamente quais valores de ATR, EMA e Efficiency Ratio levaram àquela
 * classificação. Utilizado pelo {@link RegimeChangeEvent} para registrar o estado no momento
 * da transição confirmada.
 *
 * O campo {@code marketTimestamp} representa o timestamp do último candle da janela de
 * classificação, ou seja, o momento real de mercado em que o regime foi detectado.
 * Esse campo é essencial para garantir que os relatórios de regime utilizem o tempo
 * de mercado em vez do tempo de processamento, especialmente durante o warm-up histórico.
 *
 * Utilizado por:
 * <ul>
 *   <li>{@link RegimeChangeEvent} — snapshot das métricas no momento da transição confirmada</li>
 *   <li>{@link RegimeStateTracker} — avaliação de persistência do regime candidato</li>
 *   <li>{@link MarketRegimeMonitor} — log de diagnóstico a cada classificação</li>
 * </ul>
 *
 * @param atrFast         ATR de curto prazo calculado sobre a janela recente
 * @param atrBase         ATR de referência calculado sobre a janela histórica mais longa
 * @param atrRatio        razão {@code atrFast / atrBase}; mede a intensidade relativa da volatilidade
 * @param emaDistance     distância absoluta entre EMA rápida e lenta; indica presença de momentum
 * @param efficiency      Efficiency Ratio do movimento; mede a linearidade do preço
 * @param regime          regime classificado com base nos valores acima
 * @param marketTimestamp timestamp do último candle da janela analisada; representa o momento
 *                        real de mercado da detecção
 */
public record RegimeMetrics(
        double       atrFast,
        double       atrBase,
        double       atrRatio,
        double       emaDistance,
        double       efficiency,
        MarketRegime regime,
        Instant      marketTimestamp
) {

    /**
     * Verifica se todas as métricas são válidas para análise.
     * Métricas inválidas ocorrem quando o histórico disponível é menor que o período
     * requerido pelos indicadores, resultando em valores {@code NaN} ou {@code Infinity}.
     *
     * @return {@code true} se todos os campos numéricos são finitos, {@code atrBase} é positivo
     *         e {@code marketTimestamp} não é nulo
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
     * Formata todas as métricas em string compacta para uso nos logs operacionais.
     *
     * @return string com todos os campos formatados, incluindo o timestamp de mercado
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