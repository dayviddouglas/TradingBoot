package com.github.dayviddouglas.TradingBot.deriv.trade.execution;

/**
 * Resultado imutável de uma requisição de proposal à API Deriv.
 *
 * Encapsula os dados retornados pelo endpoint {@code proposal} antes da compra:
 * - {@code proposalId}: identificador necessário para submeter o {@code buy}
 * - {@code askPrice}: preço atual do contrato no momento do proposal
 * - {@code payout}: valor a receber em caso de vitória
 * - {@code expectedRoiPct}: ROI esperado calculado a partir do askPrice e payout,
 *   podendo ser {@code NaN} quando o cálculo não for possível
 *
 * Produzido pelo {@code TradeExecutor} após receber a resposta do proposal
 * e consumido internamente para validação de ROI mínimo e execução do buy.
 *
 * @param proposalId     identificador do proposal para uso no request de buy
 * @param askPrice       preço atual do contrato equivalente ao stake desembolsado
 * @param payout         valor a receber em caso de vitória
 * @param expectedRoiPct ROI esperado em percentual; pode ser {@code NaN} quando não calculável
 */
public record ProposalResult(
        String proposalId,
        double askPrice,
        double payout,
        double expectedRoiPct
) {
    /**
     * Verifica se o ROI esperado é um valor finito e utilizável na comparação com o limiar mínimo.
     *
     * @return {@code true} se {@code expectedRoiPct} for um número finito
     */
    public boolean hasValidRoi() {
        return Double.isFinite(expectedRoiPct);
    }

    /**
     * Verifica se o payout retornado pelo proposal é um valor finito e utilizável nos cálculos.
     *
     * @return {@code true} se {@code payout} for um número finito
     */
    public boolean hasValidPayout() {
        return Double.isFinite(payout);
    }

    /**
     * Formata os dados do proposal em uma string compacta para uso nos logs operacionais.
     * Campos inválidos ({@code NaN} ou infinito) são representados como {@code "N/A"}.
     *
     * @return string formatada com proposalId, askPrice, payout e roiPct
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