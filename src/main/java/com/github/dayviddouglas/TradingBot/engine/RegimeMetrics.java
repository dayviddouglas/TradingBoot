package com.github.dayviddouglas.TradingBot.engine;

/**
 * Snapshot imutável das métricas técnicas que fundamentaram uma classificação de regime.
 *
 * Este record serve como "prova de trabalho" da decisão de regime:
 * em vez de apenas saber que o regime é RANGING, é possível auditar
 * exatamente quais valores de ATR, EMA e Efficiency Ratio levaram
 * àquela classificação.
 *
 * Contexto científico:
 * Os campos refletem os três pilares da classificação de regime
 * definidos em [Andersen & Bollerslev, 1997] para redução de ruído
 * de microestrutura intraday:
 * - atrRatio: intensidade da volatilidade relativa ao baseline
 * - emaDistance: presença ou ausência de direção direcional
 * - efficiency: linearidade do movimento (Kaufman Efficiency Ratio)
 *
 * Usado por:
 * - RegimeChangeEvent: snapshot no momento da transição
 * - RegimeStateTracker: avaliação de persistência de candidato
 * - MarketRegimeMonitor: log de diagnóstico a cada classificação
 *
 * @param atrFast      ATR de curto prazo (período configurado no classifier)
 * @param atrBase      ATR de referência/baseline (período longo)
 * @param atrRatio     razão atrFast / atrBase (> 1 = volatilidade crescendo)
 * @param emaDistance  distância absoluta entre EMA rápida e EMA lenta
 * @param efficiency   Efficiency Ratio do movimento (0.0 = range puro, 1.0 = tendência pura)
 * @param regime       regime classificado com base nessas métricas
 */
public record RegimeMetrics(
        double atrFast,
        double atrBase,
        double atrRatio,
        double emaDistance,
        double efficiency,
        MarketRegime regime
) {

    /**
     * Verifica se todas as métricas são válidas para análise.
     *
     * Métricas inválidas ocorrem quando há dados insuficientes para
     * calcular os indicadores (histórico menor que o período requerido).
     * Nesse caso, o regime retornado é sempre CHOPPY por conservadorismo.
     *
     * @return true se todos os valores são finitos e o baseline é positivo
     */
    public boolean isValid() {
        return Double.isFinite(atrFast)
                && Double.isFinite(atrBase)
                && Double.isFinite(atrRatio)
                && Double.isFinite(emaDistance)
                && Double.isFinite(efficiency)
                && atrBase > 0.0;
    }

    /**
     * Retorna descrição compacta para logs operacionais.
     *
     * Formato otimizado para grep e análise de logs:
     * todos os campos em uma única linha com separadores padronizados.
     *
     * @return string formatada com todas as métricas
     */
    public String toLogString() {
        return String.format(
                "regime=%s | atrFast=%.6f | atrBase=%.6f | atrRatio=%.3f " +
                        "| emaDistance=%.6f | efficiency=%.3f",
                regime, atrFast, atrBase, atrRatio, emaDistance, efficiency
        );
    }
}