package com.github.dayviddouglas.TradingBot.config.strategy;

import com.github.dayviddouglas.TradingBot.engine.decision.DecisionMode;
import org.springframework.stereotype.Component;

/**
 * Responsável por validar as regras de negócio de um StrategiesProfile.
 *
 * Extraído do StrategiesConfigLoader para respeitar SRP:
 * - StrategiesConfigLoader carrega profiles
 * - StrategiesProfileParser parseia JSON
 * - StrategiesProfileValidator valida regras de negócio
 *
 * Validações aplicadas em camadas:
 * 1. Campos estruturais obrigatórios (symbol, granularity, strategies)
 * 2. Configuração de trade quando habilitado
 * 3. Consistência entre DecisionMode e quantidade de estratégias
 *
 * Todas as violações lançam IllegalStateException (fail-fast),
 * impedindo que configurações inválidas cheguem ao runtime.
 */
@Component
public class StrategiesProfileValidator {

    /**
     * Valida completamente um profile antes de registrá-lo.
     *
     * @param profile profile a ser validado
     * @throws IllegalStateException se qualquer regra for violada
     */
    public void validate(StrategiesProfile profile) {
        validateStructuralFields(profile);
        validateTradeConfig(profile);
        validateDecisionModeConsistency(profile);
    }

    // ═══════════════════════════════════════════════════════════════
    // Validações estruturais
    // ═══════════════════════════════════════════════════════════════

    private void validateStructuralFields(StrategiesProfile profile) {
        if (profile.getSymbol() == null || profile.getSymbol().isBlank()) {
            throw new IllegalStateException("Profile inválido: symbol vazio");
        }

        if (profile.getGranularitySeconds() <= 0) {
            throw new IllegalStateException(
                    "Profile inválido: granularitySeconds <= 0 para symbol="
                            + profile.getSymbol());
        }

        if (profile.getStrategies() == null || profile.getStrategies().isEmpty()) {
            throw new IllegalStateException(
                    "Profile inválido: strategies vazio para symbol="
                            + profile.getSymbol());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Validações de trade config
    // ═══════════════════════════════════════════════════════════════

    private void validateTradeConfig(StrategiesProfile profile) {
        TradeConfig trade = profile.getTrade();

        if (trade == null) {
            profile.setTrade(new TradeConfig());
            return;
        }

        if (!trade.isEnabled()) return;

        validateEnabledTradeConfig(profile.getSymbol(), trade);
    }

    private void validateEnabledTradeConfig(String symbol, TradeConfig trade) {
        if (!(trade.getAmount() > 0.0)) {
            throw new IllegalStateException(
                    "Trade inválido: amount must be > 0 para symbol=" + symbol);
        }

        if (trade.getCurrency() == null || trade.getCurrency().isBlank()) {
            throw new IllegalStateException(
                    "Trade inválido: currency vazia para symbol=" + symbol);
        }

        if (trade.getDuration() < 1) {
            throw new IllegalStateException(
                    "Trade inválido: duration < 1 para symbol=" + symbol);
        }

        validateDurationUnit(symbol, trade.getDurationUnit());
    }

    private void validateDurationUnit(String symbol, String durationUnit) {
        String unit = durationUnit != null ? durationUnit.trim() : "";

        if (!isValidDurationUnit(unit)) {
            throw new IllegalStateException(
                    "Trade inválido: durationUnit deve ser um de [s,m,h,d] " +
                            "para symbol=" + symbol);
        }
    }

    private boolean isValidDurationUnit(String unit) {
        return unit.equals("s")
                || unit.equals("m")
                || unit.equals("h")
                || unit.equals("d");
    }

    // ═══════════════════════════════════════════════════════════════
    // Validação de consistência DecisionMode vs estratégias
    // ═══════════════════════════════════════════════════════════════

    private void validateDecisionModeConsistency(StrategiesProfile profile) {
        DecisionMode mode = profile.getDecisionMode();
        int enabledCount = profile.countEnabledStrategies();
        String symbol = profile.getSymbol();

        switch (mode) {
            case SINGLE_STRATEGY -> validateSingleStrategy(symbol, enabledCount);
            case CONFLUENCE -> validateMultiStrategy(symbol, mode, enabledCount);
            case VOTING -> validateMultiStrategy(symbol, mode, enabledCount);
        }
    }

    private void validateSingleStrategy(String symbol, int enabledCount) {
        if (enabledCount != 1) {
            throw new IllegalStateException(String.format(
                    "Invalid config for symbol=%s: SINGLE_STRATEGY mode " +
                            "requires exactly 1 enabled strategy, found %d",
                    symbol, enabledCount));
        }
    }

    private void validateMultiStrategy(
            String symbol,
            DecisionMode mode,
            int enabledCount
    ) {
        if (enabledCount < 2) {
            throw new IllegalStateException(String.format(
                    "Invalid config for symbol=%s: %s mode " +
                            "requires at least 2 enabled strategies, found %d",
                    symbol, mode, enabledCount));
        }
    }
}
