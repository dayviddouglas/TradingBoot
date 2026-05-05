package com.github.dayviddouglas.TradingBot.engine;

import java.time.Instant;

/**
 * Evento imutável que representa uma transição confirmada de regime de mercado.
 *
 * Uma mudança de regime só é registrada como evento após passar pelo
 * filtro de persistência do RegimeStateTracker: o novo regime deve ser
 * classificado consecutivamente por CONFIRMATION_THRESHOLD avaliações
 * (padrão: 3 avaliações × 15 candles = 45 minutos de confirmação).
 *
 * Fundamentação científica:
 * O filtro de persistência é baseado no teste de Hamilton [1989] para
 * reduzir a probabilidade de falsos positivos a α = 0.001. Uma mudança
 * de regime baseada em apenas uma classificação tem alta probabilidade
 * de ser ruído de microestrutura; três confirmações consecutivas indicam
 * uma mudança estrutural real.
 *
 * Referência: Hamilton, J.D. (1989). "A New Approach to the Economic
 * Analysis of Nonstationary Time Series and the Business Cycle."
 * Econometrica, 57(2), 357-384.
 *
 * Este evento é:
 * - Publicado pelo RegimeStateTracker quando a confirmação é alcançada
 * - Armazenado no RegimeRegistry como regime atual confirmado
 * - Persistido pelo RegimeHistoryService em arquivo JSON diário
 * - Usado pelo DerivTradeService para enriquecer o TradeReportEntry
 *
 * @param timestamp       momento exato da confirmação da transição
 * @param symbol          símbolo do ativo que mudou de regime
 * @param previousRegime  regime que estava confirmado antes da transição
 * @param currentRegime   novo regime confirmado após filtro de persistência
 * @param metrics         snapshot das métricas técnicas da avaliação decisiva
 *                        (a terceira avaliação consecutiva que confirmou a mudança)
 */
public record RegimeChangeEvent(
        Instant timestamp,
        String symbol,
        MarketRegime previousRegime,
        MarketRegime currentRegime,
        RegimeMetrics metrics
) {

    /**
     * Retorna descrição formatada para logs operacionais e auditoria.
     *
     * Inclui todos os campos relevantes para rastreabilidade completa
     * da transição, facilitando diagnóstico de decisões de trading
     * que ocorreram logo após a mudança de regime.
     *
     * @return string compacta com contexto completo da transição
     */
    public String toLogString() {
        return String.format(
                "REGIME CHANGE | symbol=%s | %s → %s | timestamp=%s | %s",
                symbol,
                previousRegime,
                currentRegime,
                timestamp,
                metrics.toLogString()
        );
    }
}