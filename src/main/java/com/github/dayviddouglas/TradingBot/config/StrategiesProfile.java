package com.github.dayviddouglas.TradingBot.config;

import com.github.dayviddouglas.TradingBot.engine.DecisionMode;

import java.util.Map;

/**
 * Modelo de dados de um perfil de ativo no strategies.json.
 *
 * Encapsula todas as configurações de um perfil de operação:
 * - qual ativo operar (symbol)
 * - qual granularidade de candle usar
 * - configurações do engine (maxBars, decisionMode, rangeLookback, rangeMultiplier)
 * - configurações de trade (stake, duração, etc.)
 * - quais estratégias estão habilitadas e seus parâmetros
 *
 * Após refatoração, esta classe tem responsabilidade única:
 * ser um modelo de dados com helpers de leitura.
 * A validação foi delegada ao StrategiesProfileValidator.
 * O parsing foi delegado ao StrategiesProfileParser.
 */
public class StrategiesProfile {

    private String symbol;
    private int granularitySeconds;
    private Map<String, Object> engine;
    private TradeConfig trade;
    private Map<String, Map<String, Object>> strategies;

    // ═══════════════════════════════════════════════════════════════
    // Getters e Setters
    // ═══════════════════════════════════════════════════════════════

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public int getGranularitySeconds() { return granularitySeconds; }
    public void setGranularitySeconds(int granularitySeconds) {
        this.granularitySeconds = granularitySeconds;
    }

    public Map<String, Object> getEngine() { return engine; }
    public void setEngine(Map<String, Object> engine) { this.engine = engine; }

    public TradeConfig getTrade() { return trade; }
    public void setTrade(TradeConfig trade) { this.trade = trade; }

    public Map<String, Map<String, Object>> getStrategies() { return strategies; }
    public void setStrategies(Map<String, Map<String, Object>> strategies) {
        this.strategies = strategies;
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers de leitura do bloco engine
    // ═══════════════════════════════════════════════════════════════

    /**
     * Retorna o decisionMode como String normalizada.
     * Padrão: "CONFLUENCE" para compatibilidade retroativa.
     */
    public String getDecisionModeString() {
        if (engine == null) return DecisionMode.CONFLUENCE.name();
        Object raw = engine.get("decisionMode");
        if (raw == null) return DecisionMode.CONFLUENCE.name();
        return raw.toString().trim().toUpperCase();
    }

    /**
     * Retorna o decisionMode como enum tipado.
     * Fallback para CONFLUENCE se o valor for inválido.
     */
    public DecisionMode getDecisionMode() {
        try {
            return DecisionMode.valueOf(getDecisionModeString());
        } catch (IllegalArgumentException e) {
            return DecisionMode.CONFLUENCE;
        }
    }

    /**
     * Retorna o número máximo de barras do histórico local.
     * Padrão: 1500.
     */
    public int getMaxBars() {
        return getIntFromEngine("maxBars", 1500);
    }

    /**
     * Retorna o rangeLookback para o VolatilityFilter.
     * Padrão: 14.
     */
    public int getRangeLookback() {
        return getIntFromEngine("rangeLookback", 14);
    }

    /**
     * Retorna o rangeMultiplier para o VolatilityFilter.
     * Padrão: 1.10.
     */
    public double getRangeMultiplier() {
        return getDoubleFromEngine("rangeMultiplier", 1.10);
    }

    /**
     * Conta quantas estratégias estão habilitadas neste profile.
     */
    public int countEnabledStrategies() {
        if (strategies == null) return 0;

        return (int) strategies.values().stream()
                .filter(this::isStrategyEnabled)
                .count();
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers privados
    // ═══════════════════════════════════════════════════════════════

    private boolean isStrategyEnabled(Map<String, Object> cfg) {
        Object enabled = cfg.get("enabled");
        if (enabled instanceof Boolean bool) return bool;
        return enabled != null && "true".equalsIgnoreCase(enabled.toString());
    }

    private int getIntFromEngine(String key, int defaultValue) {
        if (engine == null) return defaultValue;
        Object raw = engine.get(key);
        if (raw == null) return defaultValue;
        if (raw instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(raw.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private double getDoubleFromEngine(String key, double defaultValue) {
        if (engine == null) return defaultValue;
        Object raw = engine.get(key);
        if (raw == null) return defaultValue;
        if (raw instanceof Number number) return number.doubleValue();
        try {
            return Double.parseDouble(raw.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}