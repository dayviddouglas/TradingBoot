package com.github.dayviddouglas.TradingBot.config;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.*;

@Component
public class StrategiesConfigLoader {

    private final List<StrategiesProfile> profiles;

    public StrategiesConfigLoader() {
        this.profiles = loadFromClasspath("configs/strategies.json");
    }

    public List<StrategiesProfile> getProfiles() {
        return profiles;
    }

    public StrategiesProfile requireProfile(String symbol, int granularitySeconds) {
        return profiles.stream()
                .filter(p -> Objects.equals(p.getSymbol(), symbol) && p.getGranularitySeconds() == granularitySeconds)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Não existe profile no strategies.json para symbol=" + symbol +
                                " granularitySeconds=" + granularitySeconds
                ));
    }

    private static List<StrategiesProfile> loadFromClasspath(String classpathLocation) {
        try {
            ObjectMapper mapper = new ObjectMapper();

            ClassPathResource res = new ClassPathResource(classpathLocation);
            if (!res.exists()) {
                throw new IllegalStateException("Arquivo não encontrado no classpath: " + classpathLocation);
            }

            try (InputStream in = res.getInputStream()) {
                JsonNode root = mapper.readTree(in);

                JsonNode profilesNode = root.get("profiles");
                if (profilesNode == null || !profilesNode.isArray() || profilesNode.isEmpty()) {
                    throw new IllegalStateException("strategies.json inválido: 'profiles' vazio ou ausente");
                }

                List<StrategiesProfile> list = new ArrayList<>();
                Set<String> uniqueKeys = new HashSet<>();

                for (JsonNode pNode : profilesNode) {
                    StrategiesProfile p = new StrategiesProfile();

                    String symbol = requiredText(pNode, "symbol");
                    int gran = requiredInt(pNode, "granularitySeconds");

                    p.setSymbol(symbol);
                    p.setGranularitySeconds(gran);

                    p.setEngine(toMap(mapper, pNode.get("engine")));

                    // NEW: trade config (optional)
                    p.setTrade(readTradeConfig(mapper, pNode.get("trade")));

                    JsonNode strategiesNode = pNode.get("strategies");
                    if (strategiesNode == null || !strategiesNode.isObject()) {
                        throw new IllegalStateException("Profile inválido (symbol=" + symbol + "): strategies ausente");
                    }

                    Map<String, Map<String, Object>> strategies = new HashMap<>();
                    Iterator<Map.Entry<String, JsonNode>> it = strategiesNode.fields();
                    while (it.hasNext()) {
                        Map.Entry<String, JsonNode> entry = it.next();
                        String strategyName = entry.getKey();
                        JsonNode cfgNode = entry.getValue();
                        if (cfgNode == null || !cfgNode.isObject()) continue;

                        strategies.put(strategyName, toMap(mapper, cfgNode));
                    }

                    p.setStrategies(strategies);

                    validate(p);

                    String key = p.getSymbol() + "_" + p.getGranularitySeconds();
                    if (!uniqueKeys.add(key)) {
                        throw new IllegalStateException("Profile duplicado no strategies.json para: " + key);
                    }

                    list.add(p);
                }

                list.sort(Comparator.comparing(StrategiesProfile::getSymbol)
                        .thenComparingInt(StrategiesProfile::getGranularitySeconds));

                return List.copyOf(list);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao carregar " + classpathLocation, e);
        }
    }

    private static TradeConfig readTradeConfig(ObjectMapper mapper, JsonNode tradeNode) {
        // Default: trade disabled
        if (tradeNode == null || tradeNode.isNull() || !tradeNode.isObject()) {
            return new TradeConfig();
        }
        TradeConfig cfg = mapper.convertValue(tradeNode, TradeConfig.class);
        if (cfg == null) cfg = new TradeConfig();
        return cfg;
    }

    private static void validate(StrategiesProfile p) {
        if (p.getSymbol() == null || p.getSymbol().isBlank()) {
            throw new IllegalStateException("Profile inválido: symbol vazio");
        }
        if (p.getGranularitySeconds() <= 0) {
            throw new IllegalStateException("Profile inválido: granularitySeconds <= 0 para symbol=" + p.getSymbol());
        }
        if (p.getStrategies() == null || p.getStrategies().isEmpty()) {
            throw new IllegalStateException("Profile inválido: strategies vazio para symbol=" + p.getSymbol());
        }

        // NEW: validate trade config
        TradeConfig t = p.getTrade();
        if (t == null) {
            p.setTrade(new TradeConfig());
            return;
        }

        if (t.isEnabled()) {
            if (!(t.getAmount() > 0.0)) {
                throw new IllegalStateException("Trade inválido: amount must be > 0 para symbol=" + p.getSymbol());
            }
            if (t.getCurrency() == null || t.getCurrency().isBlank()) {
                throw new IllegalStateException("Trade inválido: currency vazia para symbol=" + p.getSymbol());
            }
            if (t.getDuration() < 1) {
                throw new IllegalStateException("Trade inválido: duration < 1 para symbol=" + p.getSymbol());
            }
            String unit = (t.getDurationUnit() == null) ? "" : t.getDurationUnit().trim();
            if (!unit.equals("m") && !unit.equals("s") && !unit.equals("h") && !unit.equals("d")) {
                throw new IllegalStateException("Trade inválido: durationUnit deve ser um de [s,m,h,d] para symbol=" + p.getSymbol());
            }
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.isTextual() || v.asText().isBlank()) {
            throw new IllegalStateException("Campo obrigatório inválido: " + field);
        }
        return v.asText();
    }

    private static int requiredInt(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.canConvertToInt()) {
            throw new IllegalStateException("Campo obrigatório inválido: " + field);
        }
        return v.asInt();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(ObjectMapper mapper, JsonNode node) {
        if (node == null || node.isNull()) return Map.of();
        if (!node.isObject()) return Map.of();
        return mapper.convertValue(node, Map.class);
    }
}