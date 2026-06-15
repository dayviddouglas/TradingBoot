package com.github.dayviddouglas.TradingBot.config.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Responsável por parsear o JSON do strategies.json em objetos {@link StrategiesProfile}.
 *
 * Atua em conjunto com {@link StrategiesProfileValidator}, ao qual delega a validação
 * de regras de negócio de cada profile após o parsing estrutural.
 *
 * Fluxo de parsing:
 * 1. Valida estrutura raiz (campo "profiles" presente e não vazio)
 * 2. Para cada profile: extrai campos obrigatórios
 * 3. Garante unicidade por (symbol + granularidade)
 * 4. Ordena por símbolo e granularidade
 * 5. Retorna lista imutável
 *
 * Qualquer profile com estrutura inválida lança {@link IllegalStateException}
 * durante o startup, impedindo execução com configuração corrompida.
 */
@Component
public class StrategiesProfileParser {

    private final StrategiesProfileValidator validator;

    /**
     * @param validator responsável por validar as regras de negócio de cada profile
     */
    public StrategiesProfileParser(StrategiesProfileValidator validator) {
        this.validator = validator;
    }

    /**
     * Parseia o nó raiz do strategies.json e retorna lista imutável de profiles validados.
     * Os profiles são ordenados por símbolo e, dentro do mesmo símbolo, por granularidade.
     *
     * @param root   nó raiz do JSON já parseado
     * @param mapper instância do ObjectMapper utilizada nas conversões de tipo
     * @return lista imutável e ordenada de profiles válidos
     * @throws IllegalStateException se o campo "profiles" estiver ausente, não for array
     *                               ou estiver vazio, ou se qualquer profile for inválido
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

        // Retorna cópia imutável para evitar modificações externas após o carregamento
        return List.copyOf(list);
    }

    // ═══════════════════════════════════════════════════════════════
    // Parsing de profile individual
    // ═══════════════════════════════════════════════════════════════

    /**
     * Parseia um único nó JSON em um {@link StrategiesProfile} e o valida.
     * Os campos {@code symbol} e {@code granularitySeconds} são obrigatórios.
     * Os blocos {@code engine}, {@code trade} e {@code strategies} são extraídos
     * e convertidos para seus tipos correspondentes.
     *
     * @param node   nó JSON representando um profile
     * @param mapper instância do ObjectMapper para conversões
     * @return profile populado e validado
     * @throws IllegalStateException se campos obrigatórios estiverem ausentes ou inválidos
     */
    private StrategiesProfile parseProfile(JsonNode node, ObjectMapper mapper) {
        StrategiesProfile profile = new StrategiesProfile();

        String symbol = requireText(node, "symbol");
        int granularity = requireInt(node, "granularitySeconds");

        profile.setSymbol(symbol);
        profile.setGranularitySeconds(granularity);
        profile.setEngine(toMap(mapper, node.get("engine")));
        profile.setTrade(parseTradeConfig(mapper, node.get("trade")));
        profile.setStrategies(parseStrategies(mapper, node.get("strategies"), symbol));

        // Delega validação de regras de negócio ao validator após parsing estrutural
        validator.validate(profile);

        return profile;
    }

    /**
     * Extrai o bloco {@code strategies} do JSON e converte cada entrada em um mapa
     * de parâmetros. Entradas cujo valor não seja um objeto JSON são silenciosamente ignoradas.
     *
     * @param mapper        instância do ObjectMapper para conversões
     * @param strategiesNode nó JSON do bloco strategies
     * @param symbol        símbolo do ativo, usado apenas para mensagens de erro
     * @return mapa de nome da estratégia para seus parâmetros de configuração
     * @throws IllegalStateException se o bloco strategies estiver ausente ou não for objeto
     */
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

            // Ignora entradas cujo valor não seja um objeto JSON válido
            if (cfgNode == null || !cfgNode.isObject()) continue;

            strategies.put(strategyName, toMap(mapper, cfgNode));
        }

        return strategies;
    }

    /**
     * Converte o nó JSON do bloco {@code trade} em um {@link TradeConfig}.
     * Retorna uma instância com valores padrão quando o nó estiver ausente, nulo
     * ou não for um objeto, e também quando a conversão retornar nulo.
     *
     * @param mapper    instância do ObjectMapper para a conversão
     * @param tradeNode nó JSON do bloco trade
     * @return instância de {@link TradeConfig} com os valores lidos ou padrões
     */
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

    /**
     * Garante que não existam dois profiles com a mesma combinação de symbol e granularidade.
     * A chave de unicidade é composta por {@code symbol_granularitySeconds}.
     *
     * @param profile    profile recém-parseado
     * @param uniqueKeys conjunto acumulador de chaves já registradas
     * @throws IllegalStateException se a combinação symbol + granularidade já existir
     */
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

    /**
     * Lê um campo textual obrigatório do nó JSON.
     *
     * @param node  nó JSON de origem
     * @param field nome do campo a ser lido
     * @return valor textual não vazio do campo
     * @throws IllegalStateException se o campo estiver ausente, nulo, não textual ou em branco
     */
    private String requireText(JsonNode node, String field) {
        JsonNode value = node.get(field);

        if (value == null || value.isNull()
                || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException(
                    "Campo obrigatório ausente ou inválido: " + field);
        }

        return value.asText();
    }

    /**
     * Lê um campo inteiro obrigatório do nó JSON.
     *
     * @param node  nó JSON de origem
     * @param field nome do campo a ser lido
     * @return valor inteiro do campo
     * @throws IllegalStateException se o campo estiver ausente, nulo ou não conversível para int
     */
    private int requireInt(JsonNode node, String field) {
        JsonNode value = node.get(field);

        if (value == null || value.isNull() || !value.canConvertToInt()) {
            throw new IllegalStateException(
                    "Campo obrigatório ausente ou inválido: " + field);
        }

        return value.asInt();
    }

    /**
     * Converte um nó JSON objeto em {@code Map<String, Object>}.
     * Retorna mapa vazio imutável quando o nó estiver ausente, nulo ou não for objeto.
     *
     * @param mapper instância do ObjectMapper para a conversão
     * @param node   nó JSON a ser convertido
     * @return mapa de chave-valor ou mapa vazio imutável
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(ObjectMapper mapper, JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return Map.of();
        }
        return mapper.convertValue(node, Map.class);
    }
}