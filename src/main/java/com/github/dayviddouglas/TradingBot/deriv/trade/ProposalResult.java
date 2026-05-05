package com.github.dayviddouglas.TradingBot.deriv.trade;

/**
 * Resultado imutável de uma requisição de proposal à API Deriv.
 *
 * Encapsula os dados retornados pelo proposal antes da compra:
 * - proposalId: ID necessário para executar o buy
 * - askPrice: preço atual do contrato
 * - payout: valor a receber em caso de vitória
 * - expectedRoiPct: ROI esperado calculado antes da compra
 *
 * Criado pelo TradeExecutor após receber a resposta do proposal
 * e usado internamente para validação de ROI e execução do buy.
 *
 * @param proposalId     ID do proposal para uso no buy
 * @param askPrice       preço atual do contrato (stake)
 * @param payout         valor a receber em caso de vitória
 * @param expectedRoiPct ROI esperado em percentual (pode ser NaN)
 */
public record ProposalResult(
        String proposalId,
        double askPrice,
        double payout,
        double expectedRoiPct
) {
    /**
     * Verifica se o ROI esperado é um valor finito e calculável.
     *
     * @return true se expectedRoiPct é um número válido
     */
    public boolean hasValidRoi() {
        return Double.isFinite(expectedRoiPct);
    }

    /**
     * Verifica se o payout é um valor finito e calculável.
     *
     * @return true se payout é um número válido
     */
    public boolean hasValidPayout() {
        return Double.isFinite(payout);
    }

    /**
     * Retorna descrição formatada para logs operacionais.
     *
     * @return string compacta com os dados do proposal
     */
    public String toLogString() {
        return String.format(
                "proposalId=%s | askPrice=%.5f | payout=%s | roiPct=%s",
                proposalId,
                askPrice,
                hasValidPayout() ? String.format("%.5f", payout) : "N/A",
                hasValidRoi() ? String.format("%.2f%%", expectedRoiPct) : "N/A"
        );
    }
}
