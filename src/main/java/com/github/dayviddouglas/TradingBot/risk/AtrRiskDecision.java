package com.github.dayviddouglas.TradingBot.risk;

/**
 * Record que representa a decisão de risco do ATR.
 *
 * Contém todas as informações sobre a avaliação de volatilidade
 * e a decisão tomada pelo AtrRiskManager.
 *
 * @param allowTrade Se o trade deve ser executado
 * @param adjustedAmount Valor ajustado da stake (pode ser reduzido ou zero se bloqueado)
 * @param riskMode Modo de risco aplicado: ALLOW, REDUCE_STAKE, BLOCK, INSUFFICIENT_DATA, NO_STRATEGIES, ZERO_BASELINE
 * @param atrFast ATR de curto prazo (14 períodos)
 * @param atrBaseline ATR de longo prazo baseline (50 períodos)
 * @param atrRatio Razão entre ATR fast e baseline (atrFast / atrBaseline)
 * @param confluenceType Tipo da confluência que gerou o sinal: TREND_BREAKOUT, REVERSAL_RANGE ou UNKNOWN
 * @param reason Razão da decisão em texto legível
 */
public record AtrRiskDecision(
        boolean allowTrade,
        double adjustedAmount,
        String riskMode,
        double atrFast,
        double atrBaseline,
        double atrRatio,
        String confluenceType,
        String reason
) {

    /**
     * Valida se a decisão permite execução do trade.
     *
     * @return true se allowTrade é true e adjustedAmount é maior que zero
     */
    public boolean canExecute() {
        return allowTrade && adjustedAmount > 0;
    }

    /**
     * Verifica se a stake foi reduzida.
     *
     * @return true se o riskMode for REDUCE_STAKE
     */
    public boolean isStakeReduced() {
        return "REDUCE_STAKE".equals(riskMode);
    }

    /**
     * Verifica se o trade foi bloqueado.
     *
     * @return true se o riskMode for BLOCK
     */
    public boolean isBlocked() {
        return "BLOCK".equals(riskMode);
    }

    /**
     * Verifica se a decisão foi tomada com dados insuficientes.
     *
     * @return true se o riskMode indicar falta de dados
     */
    public boolean hasInsufficientData() {
        return "INSUFFICIENT_DATA".equals(riskMode) ||
                "NO_STRATEGIES".equals(riskMode) ||
                "ZERO_BASELINE".equals(riskMode);
    }

    /**
     * Retorna descrição formatada da decisão para logs.
     *
     * @return String com resumo da decisão
     */
    public String toLogString() {
        return String.format(
                "AtrRiskDecision[mode=%s, allow=%s, amount=%.2f, type=%s, atrRatio=%.2f, reason=%s]",
                riskMode, allowTrade, adjustedAmount, confluenceType, atrRatio, reason
        );
    }
}