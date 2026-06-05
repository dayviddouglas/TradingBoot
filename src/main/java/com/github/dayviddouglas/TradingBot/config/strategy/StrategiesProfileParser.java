package com.github.dayviddouglas.TradingBot.config.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Responsável por parsear o JSON do strategies.json em StrategiesProfile.
 *
 * Extraído do StrategiesConfigLoader para respeitar SRP:
 * - StrategiesConfigLoader carrega o arquivo
 * - StrategiesProfileParser converte JSON em objetos
 * - StrategiesProfileValidator valida as regras de negócio
 *
 * Fluxo de parsing:
 * 1. Valida estrutura raiz (campo "profiles" presente e não vazio)
 * 2. Para cada profile: extrai campos obrigatórios
 * 3. Garante unicidade por (symbol + granularidade)
 * 4. Ordena por símbolo e granularidade
 * 5. Retorna lista imutável
 *
 * Fail-fast: qualquer profile inválido lança IllegalStateException
 * durante o startup, impedindo execução com configuração corrompida.
 */
@Component
public class StrategiesProfileParser {

    private final StrategiesProfileValidator validator;

    public StrategiesProfileParser(StrategiesProfileValidator validator) {
        this.validator = validator;
    }

    /**
     * Parseia o JSON raiz e retorna lista imutável de profiles validados.
     *
     * @param root   raiz do JSON parseado
     * @param mapper instância do ObjectMapper para conversões
     * @return lista imutável e ordenada de profiles
     * @throws IllegalStateException se o JSON for inválido
     */
    public List<StrategiesProfile> parse(JsonNode root, ObjectMapper mapper) {
        JsonNode profilesNode = root.get("profiles");

        if (profilesNode == null || !profilesNode.isArray() || profilesNode.isEmpty()) {
            throw new IllegalStateException(
                    "strategies.json inválido: 'profiles' vazio ou ausente");
        }

        List<StrategiesProfile> list = new ArrayList<>();
        Set<String> uniqueKeys = new HashSet<>();

        for (JsonNode profileNode : profilesNode) {
            StrategiesProfile profile = parseProfile(profileNode, mapper);
            ensureUniqueness(profile, uniqueKeys);
            list.add(profile);
        }

        list.sort(Comparator
                .comparing(StrategiesProfile::getSymbol)
                .thenComparingInt(StrategiesProfile::getGranularitySeconds));

        return List.copyOf(list);
    }

    // ═══════════════════════════════════════════════════════════════
    // Parsing de profile individual
    // ═══════════════════════════════════════════════════════════════

    private StrategiesProfile parseProfile(JsonNode node, ObjectMapper mapper) {
        StrategiesProfile profile = new StrategiesProfile();

        String symbol = requireText(node, "symbol");
        int granularity = requireInt(node, "granularitySeconds");

        profile.setSymbol(symbol);
        profile.setGranularitySeconds(granularity);
        profile.setEngine(toMap(mapper, node.get("engine")));
        profile.setTrade(parseTradeConfig(mapper, node.get("trade")));
        profile.setStrategies(parseStrategies(mapper, node.get("strategies"), symbol));

        validator.validate(profile);

        return profile;
    }

    private Map<String, Map<String, Object>> parseStrategies(
            ObjectMapper mapper,
            JsonNode strategiesNode,
            String symbol
    ) {
        if (strategiesNode == null || !strategiesNode.isObject()) {
            throw new IllegalStateException(
                    "Profile inválido (symbol=" + symbol + "): strategies ausente");
        }

        Map<String, Map<String, Object>> strategies = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = strategiesNode.fields();

        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String strategyName = entry.getKey();
            JsonNode cfgNode = entry.getValue();

            if (cfgNode == null || !cfgNode.isObject()) continue;

            strategies.put(strategyName, toMap(mapper, cfgNode));
        }

        return strategies;
    }

    private TradeConfig parseTradeConfig(ObjectMapper mapper, JsonNode tradeNode) {
        if (tradeNode == null || tradeNode.isNull() || !tradeNode.isObject()) {
            return new TradeConfig();
        }

        TradeConfig cfg = mapper.convertValue(tradeNode, TradeConfig.class);
        return cfg != null ? cfg : new TradeConfig();
    }

    // ═══════════════════════════════════════════════════════════════
    // Unicidade
    // ═══════════════════════════════════════════════════════════════

    private void ensureUniqueness(StrategiesProfile profile, Set<String> uniqueKeys) {
        String key = profile.getSymbol() + "_" + profile.getGranularitySeconds();

        if (!uniqueKeys.add(key)) {
            throw new IllegalStateException(
                    "Profile duplicado no strategies.json para: " + key);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers de extração de campos obrigatórios
    // ═══════════════════════════════════════════════════════════════

    private String requireText(JsonNode node, String field) {
        JsonNode value = node.get(field);

        if (value == null || value.isNull()
                || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException(
                    "Campo obrigatório ausente ou inválido: " + field);
        }

        return value.asText();
    }

    private int requireInt(JsonNode node, String field) {
        JsonNode value = node.get(field);

        if (value == null || value.isNull() || !value.canConvertToInt()) {
            throw new IllegalStateException(
                    "Campo obrigatório ausente ou inválido: " + field);
        }

        return value.asInt();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(ObjectMapper mapper, JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return Map.of();
        }
        return mapper.convertValue(node, Map.class);
    }
}
