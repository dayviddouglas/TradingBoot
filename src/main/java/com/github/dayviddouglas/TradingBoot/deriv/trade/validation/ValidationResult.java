package com.github.dayviddouglas.TradingBoot.deriv.trade.validation;

/**
 * Resultado imutável de uma validação de trade realizada pelo {@link TradeValidator}.
 *
 * Representa um dos três desfechos possíveis de uma validação:
 * <ul>
 *   <li>{@link ValidationStatus#PROCEED}: todas as camadas de validação foram aprovadas;
 *       o fluxo de execução pode prosseguir</li>
 *   <li>{@link ValidationStatus#SKIP}: uma validação rejeitou o sinal; o motivo é
 *       registrado em nível {@code debug} pelo {@link TradeValidator}</li>
 *   <li>{@link ValidationStatus#IGNORE}: o ativo já possui uma operação em andamento;
 *       o sinal é descartado silenciosamente sem registro de erro</li>
 * </ul>
 *
 * @param status resultado da validação
 * @param reason motivo da rejeição; presente apenas quando o status for {@code SKIP},
 *               {@code null} nos demais casos
 */
public record ValidationResult(ValidationStatus status, String reason) {

    /**
     * Cria um resultado indicando que todas as validações foram aprovadas.
     *
     * @return resultado com status {@link ValidationStatus#PROCEED}
     */
    public static ValidationResult proceed() {
        return new ValidationResult(ValidationStatus.PROCEED, null);
    }

    /**
     * Cria um resultado indicando que o sinal foi rejeitado por uma validação,
     * com o motivo registrado para rastreabilidade nos logs.
     *
     * @param reason descrição da condição que rejeitou o sinal
     * @return resultado com status {@link ValidationStatus#SKIP}
     */
    public static ValidationResult skip(String reason) {
        return new ValidationResult(ValidationStatus.SKIP, reason);
    }

    /**
     * Cria um resultado indicando que o sinal foi descartado porque o ativo
     * já possui uma operação em andamento.
     *
     * @return resultado com status {@link ValidationStatus#IGNORE}
     */
    public static ValidationResult ignore() {
        return new ValidationResult(ValidationStatus.IGNORE, null);
    }

    /**
     * Verifica se o resultado autoriza a execução do trade.
     *
     * @return {@code true} se o status for {@link ValidationStatus#PROCEED}
     */
    public boolean shouldProceed() {
        return status == ValidationStatus.PROCEED;
    }
}