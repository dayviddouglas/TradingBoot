package com.github.dayviddouglas.TradingBoot.config.strategy;

/**
 * POJO de configuração de trade por ativo.
 *
 * Deserializado pelo Jackson a partir do bloco {@code trade} no strategies.json
 * via {@code ObjectMapper.convertValue()}. Cada campo possui um valor padrão seguro
 * aplicado tanto na inicialização do campo quanto nos getters com validação defensiva.
 *
 * O campo {@code enabled} controla globalmente se o ativo opera em modo de execução real.
 * O campo {@code minRoiPercent} permite que cada ativo defina seu próprio limiar mínimo
 * de retorno, evitando execuções com payout desfavorável.
 *
 * Exemplo de configuração no strategies.json:
 * <pre>
 * {
 *   "trade": {
 *     "enabled": true,
 *     "amount": 10,
 *     "currency": "USD",
 *     "duration": 15,
 *     "durationUnit": "m",
 *     "minRoiPercent": 70.0
 *   }
 * }
 * </pre>
 */
public class TradeConfig {

    /**
     * Controla se o trade está habilitado para este ativo.
     * Padrão {@code false} garante que nenhuma operação seja executada
     * sem habilitação explícita no JSON.
     */
    private boolean enabled = false;

    /**
     * Valor do stake por operação em moeda corrente.
     * A Deriv exige que o stake tenha no máximo 2 casas decimais.
     * Padrão {@code 1.0} como valor mínimo seguro.
     */
    private double amount = 1.0;

    /**
     * Moeda do stake. Deve ser uma moeda suportada pela Deriv.
     * Exemplo: {@code "USD"}.
     */
    private String currency = "USD";

    /**
     * Valor numérico da duração do contrato.
     * Interpretado em conjunto com {@link #durationUnit}.
     */
    private int duration = 15;

    /**
     * Unidade da duração do contrato.
     * Valores aceitos pela Deriv: {@code "s"} (segundos), {@code "m"} (minutos),
     * {@code "h"} (horas), {@code "d"} (dias).
     */
    private String durationUnit = "m";

    /**
     * ROI mínimo aceitável, em percentual, para que a operação seja executada.
     * Representa o retorno percentual esperado sobre o stake em caso de win.
     * Operações com payout abaixo deste valor são descartadas pelo {@code TradeExecutor}
     * antes da compra do contrato.
     *
     * O padrão {@code 70.0} garante que ativos sem o campo configurado no JSON
     * operem com um limiar conservador, compatível com break-even acima de 58% de win rate.
     */
    private double minRoiPercent = 70.0;

    // ═══════════════════════════════════════════════════════════════
    // Construtores
    // ═══════════════════════════════════════════════════════════════

    /**
     * Construtor padrão requerido pelo Jackson para deserialização.
     */
    public TradeConfig() {
    }

    /**
     * Construtor completo para criação programática, como em testes.
     *
     * @param enabled        indica se o trade está habilitado
     * @param amount         stake por operação
     * @param currency       moeda do stake
     * @param duration       duração numérica do contrato
     * @param durationUnit   unidade da duração
     * @param minRoiPercent  ROI mínimo aceitável em percentual
     */
    public TradeConfig(
            boolean enabled,
            double amount,
            String currency,
            int duration,
            String durationUnit,
            double minRoiPercent
    ) {
        this.enabled       = enabled;
        this.amount        = amount;
        this.currency      = currency;
        this.duration      = duration;
        this.durationUnit  = durationUnit;
        this.minRoiPercent = minRoiPercent;
    }

    // ═══════════════════════════════════════════════════════════════
    // Getters com validação defensiva
    // ═══════════════════════════════════════════════════════════════

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Retorna o stake por operação.
     * Aplica fallback para {@code 1.0} caso o valor deserializado seja inválido.
     *
     * @return amount configurado ou {@code 1.0} como fallback
     */
    public double getAmount() {
        return amount > 0 ? amount : 1.0;
    }

    /**
     * Retorna a moeda do stake.
     * Aplica fallback para {@code "USD"} caso o valor esteja nulo ou em branco.
     *
     * @return currency configurada ou {@code "USD"} como fallback
     */
    public String getCurrency() {
        return currency != null && !currency.isBlank()
                ? currency : "USD";
    }

    /**
     * Retorna a duração numérica do contrato.
     * Aplica fallback para {@code 15} caso o valor seja inválido.
     *
     * @return duration configurada ou {@code 15} como fallback
     */
    public int getDuration() {
        return duration > 0 ? duration : 15;
    }

    /**
     * Retorna a unidade de duração do contrato.
     * Aplica fallback para {@code "m"} caso o valor esteja nulo ou em branco.
     *
     * @return durationUnit configurada ou {@code "m"} como fallback
     */
    public String getDurationUnit() {
        return durationUnit != null && !durationUnit.isBlank()
                ? durationUnit : "m";
    }

    /**
     * Retorna o ROI mínimo aceitável em percentual.
     * Aplica fallback para {@code 70.0} garantindo que ativos sem o campo configurado
     * no JSON não operem sem limiar de retorno definido.
     *
     * @return minRoiPercent configurado ou {@code 70.0} como fallback
     */
    public double getMinRoiPercent() {
        return minRoiPercent > 0 ? minRoiPercent : 70.0;
    }

    // ═══════════════════════════════════════════════════════════════
    // Setters
    // ═══════════════════════════════════════════════════════════════

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setAmount(double amount) {
        this.amount = amount;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public void setDuration(int duration) {
        this.duration = duration;
    }

    public void setDurationUnit(String durationUnit) {
        this.durationUnit = durationUnit;
    }

    public void setMinRoiPercent(double minRoiPercent) {
        this.minRoiPercent = minRoiPercent;
    }

    @Override
    public String toString() {
        return String.format(
                "TradeConfig{enabled=%s, amount=%.2f, currency='%s', "
                        + "duration=%d, durationUnit='%s', minRoiPercent=%.1f}",
                enabled, amount, currency,
                duration, durationUnit, minRoiPercent
        );
    }
}