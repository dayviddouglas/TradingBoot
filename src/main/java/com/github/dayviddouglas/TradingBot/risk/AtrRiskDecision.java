package com.github.dayviddouglas.TradingBot.risk;

/**
 * Resultado imutável da avaliação de risco realizada pelo {@link AtrRiskManager}.
 *
 * Encapsula a decisão de permitir ou bloquear um trade com base na volatilidade
 * atual do ativo medida pelo ATR, incluindo os valores calculados e o motivo
 * legível da decisão.
 *
 * @param allowTrade      {@code true} se o trade pode ser executado
 * @param adjustedAmount  stake a ser utilizado; pode ser reduzido ou zero quando bloqueado
 * @param riskMode        modo de risco aplicado: {@code ALLOW}, {@code REDUCE_STAKE},
 *                        {@code BLOCK}, {@code INSUFFICIENT_DATA}, {@code NO_STRATEGIES}
 *                        ou {@code ZERO_BASELINE}
 * @param atrFast         ATR de curto prazo calculado sobre 14 períodos
 * @param atrBaseline     ATR de referência calculado sobre 50 períodos
 * @param atrRatio        razão {@code atrFast / atrBaseline}; mede a intensidade relativa
 *                        da volatilidade atual em relação à histórica
 * @param confluenceType  tipo de confluência que gerou o sinal:
 *                        {@code TREND_BREAKOUT}, {@code REVERSAL_RANGE} ou {@code UNKNOWN}
 * @param reason          descrição legível do motivo da decisão, utilizada nos logs operacionais
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
     * Verifica se a decisão autoriza a execução do trade com stake válido.
     *
     * @return {@code true} se {@code allowTrade} for {@code true}
     *         e {@code adjustedAmount} for maior que zero
     */
    public boolean canExecute() {
        return allowTrade && adjustedAmount > 0;
    }

    /**
     * Verifica se o stake foi reduzido pelo gerenciador de risco.
     *
     * @return {@code true} se o {@code riskMode} for {@code "REDUCE_STAKE"}
     */
    public boolean isStakeReduced() {
        return "REDUCE_STAKE".equals(riskMode);
    }

    /**
     * Verifica se o trade foi bloqueado pelo gerenciador de risco.
     *
     * @return {@code true} se o {@code riskMode} for {@code "BLOCK"}
     */
    public boolean isBlocked() {
        return "BLOCK".equals(riskMode);
    }

    /**
     * Verifica se a decisão foi tomada com dados insuficientes para avaliação completa.
     * Cobre os casos em que o histórico é curto demais, não há estratégias ou o ATR
     * baseline é zero, impossibilitando o cálculo do ratio.
     *
     * @return {@code true} se o {@code riskMode} indicar ausência de dados suficientes
     */
    public boolean hasInsufficientData() {
        return "INSUFFICIENT_DATA".equals(riskMode)
                || "NO_STRATEGIES".equals(riskMode)
                || "ZERO_BASELINE".equals(riskMode);
    }

    /**
     * Formata os campos principais da decisão em string compacta para uso nos logs operacionais.
     *
     * @return string com modo, autorização, stake, tipo de confluência, ATR ratio e motivo
     */
    public String toLogString() {
        return String.format(
                "AtrRiskDecision[mode=%s, allow=%s, amount=%.2f, type=%s, atrRatio=%.2f, reason=%s]",
                riskMode, allowTrade, adjustedAmount, confluenceType, atrRatio, reason
        );
    }
}