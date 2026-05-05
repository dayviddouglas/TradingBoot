package com.github.dayviddouglas.TradingBot.config;

/**
 * POJO de configuração de trade por ativo.
 *
 * Deserializado automaticamente pelo Jackson a partir do bloco
 * "trade" no strategies.json via ObjectMapper.convertValue().
 *
 * Atualização v5.4:
 * Campo minRoiPercent adicionado para permitir configuração do
 * retorno mínimo aceitável por ativo diretamente no strategies.json.
 * Antes o valor era hardcoded em 35.0 no TradeExecutor (SUG-02).
 * Agora cada ativo pode ter seu próprio filtro de ROI mínimo.
 *
 * Exemplo de configuração no strategies.json:
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
 */
public class TradeConfig {

    /**
     * Controla se o trade está habilitado para este ativo.
     * Padrão false garante segurança: o sistema não opera
     * sem habilitar explicitamente.
     */
    private boolean enabled = false;

    /**
     * Valor do stake por operação em moeda corrente.
     * Padrão 1.0 como valor mínimo seguro.
     * ⚠️ A Deriv exige que o stake tenha no máximo 2 casas decimais.
     */
    private double amount = 1.0;

    /**
     * Moeda do stake (ex: "USD").
     * Deve ser uma moeda suportada pela Deriv.
     */
    private String currency = "USD";

    /**
     * Duração do contrato no valor numérico.
     * Interpretado em conjunto com durationUnit.
     */
    private int duration = 15;

    /**
     * Unidade da duração do contrato.
     * Valores aceitos: "s" (segundos), "m" (minutos),
     * "h" (horas), "d" (dias).
     */
    private String durationUnit = "m";

    /**
     * ROI mínimo aceitável para executar a operação.
     *
     * Representa o retorno percentual mínimo sobre o stake
     * em caso de win. Operações com payout abaixo deste valor
     * são descartadas antes da compra.
     *
     * Atualização v5.4: migrado de hardcoded (35.0) no TradeExecutor
     * para configurável por ativo via strategies.json (SUG-02).
     *
     * Exemplo: 70.0 significa que o contrato só é comprado se o
     * retorno esperado em caso de win for >= 70% do stake.
     *
     * Padrão 70.0 — valor conservador que garante edge mínimo
     * compatível com break-even acima de 58% de win rate.
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
     * Construtor completo para criação programática (ex: testes).
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
     * Retorna o amount com fallback para 1.0 caso seja inválido.
     */
    public double getAmount() {
        return amount > 0 ? amount : 1.0;
    }

    /**
     * Retorna a currency com fallback para "USD".
     */
    public String getCurrency() {
        return currency != null && !currency.isBlank()
                ? currency : "USD";
    }

    /**
     * Retorna a duração com fallback para 15 minutos.
     */
    public int getDuration() {
        return duration > 0 ? duration : 15;
    }

    /**
     * Retorna a unidade de duração com fallback para "m".
     */
    public String getDurationUnit() {
        return durationUnit != null && !durationUnit.isBlank()
                ? durationUnit : "m";
    }

    /**
     * Retorna o ROI mínimo com fallback para 70.0.
     *
     * Fallback para 70.0 garante que ativos sem minRoiPercent
     * configurado no JSON operem com o novo padrão conservador,
     * sem quebrar profiles existentes que não têm o campo.
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