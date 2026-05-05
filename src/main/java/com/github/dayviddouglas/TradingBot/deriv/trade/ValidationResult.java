package com.github.dayviddouglas.TradingBot.deriv.trade;

/**
 * Resultado imutável de uma validação de trade.
 *
 * Três estados possíveis:
 * - PROCEED: validação aprovada, pode executar
 * - SKIP: validação rejeitou, motivo registrado em debug
 * - IGNORE: ativo ocupado, sinal descartado silenciosamente
 *
 * @param status resultado da validação
 * @param reason motivo da rejeição (null se PROCEED)
 */
public record ValidationResult(ValidationStatus status, String reason) {

    public static ValidationResult proceed() {
        return new ValidationResult(ValidationStatus.PROCEED, null);
    }

    public static ValidationResult skip(String reason) {
        return new ValidationResult(ValidationStatus.SKIP, reason);
    }

    public static ValidationResult ignore() {
        return new ValidationResult(ValidationStatus.IGNORE, null);
    }

    public boolean shouldProceed() {
        return status == ValidationStatus.PROCEED;
    }
}