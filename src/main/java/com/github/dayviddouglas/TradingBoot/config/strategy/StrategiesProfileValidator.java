package com.github.dayviddouglas.TradingBoot.config.strategy;

import com.github.dayviddouglas.TradingBoot.engine.decision.DecisionMode;
import org.springframework.stereotype.Component;

/**
 * Responsável por validar as regras de negócio de um {@link StrategiesProfile}.
 *
 * Atua em conjunto com {@link StrategiesProfileParser}, que delega a validação
 * após concluir o parsing estrutural de cada profile.
 *
 * Validações aplicadas em camadas:
 * 1. Campos estruturais obrigatórios: symbol, granularitySeconds e strategies
 * 2. Configuração de trade quando o campo {@code enabled} estiver ativo
 * 3. Consistência entre o {@link DecisionMode} e a quantidade de estratégias habilitadas
 *
 * Todas as violações lançam {@link IllegalStateException}, impedindo que
 * configurações inválidas cheguem ao runtime.
 */
@Component
public class StrategiesProfileValidator {

    /**
     * Valida completamente um profile, aplicando as três camadas de validação em sequência.
     *
     * @param profile profile a ser validado
     * @throws IllegalStateException se qualquer regra de negócio for violada
     */
    public void validate(StrategiesProfile profile) {
        validateStructuralFields(profile);
        validateTradeConfig(profile);
        validateDecisionModeConsistency(profile);
    }

    // ═══════════════════════════════════════════════════════════════
    // Validações estruturais
    // ═══════════════════════════════════════════════════════════════

    /**
     * Verifica que os campos estruturais obrigatórios estão presentes e com valores válidos:
     * {@code symbol} não pode ser vazio, {@code granularitySeconds} deve ser positivo
     * e o mapa de {@code strategies} não pode ser nulo nem vazio.
     *
     * @param profile profile a ser verificado
     * @throws IllegalStateException se algum campo obrigatório for inválido
     */
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

    /**
     * Valida o bloco de configuração de trade.
     * Quando {@code trade} é nulo, inicializa com valores padrão e encerra a validação.
     * Quando {@code trade.enabled} é {@code false}, nenhuma validação adicional é aplicada.
     * Quando {@code trade.enabled} é {@code true}, delega para {@link #validateEnabledTradeConfig}.
     *
     * @param profile profile cujo trade config será validado
     * @throws IllegalStateException se o trade estiver habilitado com configuração inválida
     */
    private void validateTradeConfig(StrategiesProfile profile) {
        TradeConfig trade = profile.getTrade();

        if (trade == null) {
            profile.setTrade(new TradeConfig());
            return;
        }

        if (!trade.isEnabled()) return;

        validateEnabledTradeConfig(profile.getSymbol(), trade);
    }

    /**
     * Valida os campos obrigatórios de um trade habilitado:
     * {@code amount} deve ser positivo, {@code currency} não pode ser vazia,
     * {@code duration} deve ser no mínimo 1 e {@code durationUnit} deve ser
     * um dos valores aceitos pela Deriv: {@code s}, {@code m}, {@code h} ou {@code d}.
     *
     * @param symbol identificador do ativo, utilizado nas mensagens de erro
     * @param trade  configuração de trade a ser validada
     * @throws IllegalStateException se qualquer campo obrigatório for inválido
     */
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

    /**
     * Verifica se o {@code durationUnit} configurado corresponde a um dos valores
     * aceitos: {@code s} (segundos), {@code m} (minutos), {@code h} (horas)
     * ou {@code d} (dias). Trata nulo como string vazia antes da comparação.
     *
     * @param symbol       identificador do ativo, utilizado na mensagem de erro
     * @param durationUnit unidade de duração a ser validada
     * @throws IllegalStateException se o valor não corresponder a nenhum dos aceitos
     */
    private void validateDurationUnit(String symbol, String durationUnit) {
        String unit = durationUnit != null ? durationUnit.trim() : "";

        if (!isValidDurationUnit(unit)) {
            throw new IllegalStateException(
                    "Trade inválido: durationUnit deve ser um de [s,m,h,d] " +
                            "para symbol=" + symbol);
        }
    }

    /**
     * Verifica se a unidade informada é um dos valores aceitos.
     *
     * @param unit unidade já normalizada (sem espaços)
     * @return {@code true} se a unidade for {@code s}, {@code m}, {@code h} ou {@code d}
     */
    private boolean isValidDurationUnit(String unit) {
        return unit.equals("s")
                || unit.equals("m")
                || unit.equals("h")
                || unit.equals("d");
    }

    // ═══════════════════════════════════════════════════════════════
    // Validação de consistência DecisionMode vs estratégias
    // ═══════════════════════════════════════════════════════════════

    /**
     * Verifica a consistência entre o {@link DecisionMode} configurado e a quantidade
     * de estratégias habilitadas no profile.
     * {@link DecisionMode#SINGLE_STRATEGY} exige exatamente 1 estratégia habilitada.
     * {@link DecisionMode#CONFLUENCE} e {@link DecisionMode#VOTING} exigem ao menos 2.
     *
     * @param profile profile a ser verificado
     * @throws IllegalStateException se a quantidade de estratégias for incompatível com o modo
     */
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

    /**
     * Valida que exatamente 1 estratégia está habilitada para o modo
     * {@link DecisionMode#SINGLE_STRATEGY}.
     *
     * @param symbol       identificador do ativo
     * @param enabledCount total de estratégias habilitadas encontradas
     * @throws IllegalStateException se o total for diferente de 1
     */
    private void validateSingleStrategy(String symbol, int enabledCount) {
        if (enabledCount != 1) {
            throw new IllegalStateException(String.format(
                    "Invalid config for symbol=%s: SINGLE_STRATEGY mode " +
                            "requires exactly 1 enabled strategy, found %d",
                    symbol, enabledCount));
        }
    }

    /**
     * Valida que ao menos 2 estratégias estão habilitadas para os modos
     * {@link DecisionMode#CONFLUENCE} e {@link DecisionMode#VOTING}.
     *
     * @param symbol       identificador do ativo
     * @param mode         modo de decisão configurado, usado na mensagem de erro
     * @param enabledCount total de estratégias habilitadas encontradas
     * @throws IllegalStateException se o total for inferior a 2
     */
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