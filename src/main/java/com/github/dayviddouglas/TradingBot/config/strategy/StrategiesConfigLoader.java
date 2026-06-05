package com.github.dayviddouglas.TradingBot.config.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dayviddouglas.TradingBot.strategy.TradingStrategy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Fonte única de verdade para carregamento de profiles de estratégias.
 *
 * Responsabilidades:
 * - Carregar strategies.json do classpath (startup) ou filesystem (backtest)
 * - Parsear e validar StrategiesProfile
 * - Delegar construção de estratégias ao StrategyBuilder
 *
 * Após refatoração, esta classe tem responsabilidade única:
 * carregar e fornecer profiles validados.
 * A construção de estratégias foi delegada ao StrategyBuilder.
 *
 * Fluxo de carregamento:
 * 1. Construtor carrega automaticamente do classpath (fail-fast)
 * 2. loadProfiles() permite carregar de path externo (backtest)
 * 3. buildStrategies() delega para StrategyBuilder
 */
@Component
public class StrategiesConfigLoader {

    private static final String DEFAULT_CLASSPATH = "configs/strategies.json";

    private final ObjectMapper mapper = new ObjectMapper();
    private final StrategyBuilder strategyBuilder;
    private final StrategiesProfileParser profileParser;
    private final List<StrategiesProfile> profiles;

    /**
     * Construtor invocado pelo Spring.
     * Carrega o strategies.json do classpath no startup (fail-fast).
     *
     * @param strategyBuilder builder de estratégias
     * @param profileParser   parser e validador de profiles
     */
    public StrategiesConfigLoader(
            StrategyBuilder strategyBuilder,
            StrategiesProfileParser profileParser
    ) {
        this.strategyBuilder = strategyBuilder;
        this.profileParser = profileParser;
        this.profiles = loadFromClasspath(DEFAULT_CLASSPATH);
    }

    /**
     * Retorna os profiles carregados no startup.
     * Utilizado pelo MultiSymbolDerivBotRunner.
     *
     * @return lista imutável de profiles
     */
    public List<StrategiesProfile> getProfiles() {
        return profiles;
    }

    /**
     * Carrega profiles de um path externo com fallback para classpath.
     * Utilizado pelo BacktestRunner.
     *
     * @param path caminho do arquivo (filesystem ou classpath)
     * @return lista de profiles carregados
     */
    public List<StrategiesProfile> loadProfiles(String path) {
        if (isValidFilesystemPath(path)) {
            return loadFromFilesystem(Path.of(path));
        }
        return loadFromClasspath(path);
    }

    /**
     * Busca um profile pelo símbolo e granularidade.
     *
     * @param symbol             símbolo do ativo
     * @param granularitySeconds granularidade em segundos
     * @return profile correspondente
     * @throws IllegalStateException se o profile não existir
     */
    public StrategiesProfile requireProfile(String symbol, int granularitySeconds) {
        return profiles.stream()
                .filter(p -> Objects.equals(p.getSymbol(), symbol)
                        && p.getGranularitySeconds() == granularitySeconds)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Profile not found in strategies.json for symbol="
                                + symbol + " granularitySeconds=" + granularitySeconds));
    }

    /**
     * Constrói estratégias para o profile delegando ao StrategyBuilder.
     *
     * @param profile profile do ativo
     * @return lista de estratégias habilitadas
     */
    public List<TradingStrategy> buildStrategies(StrategiesProfile profile) {
        return strategyBuilder.build(profile.getStrategies());
    }

    // ═══════════════════════════════════════════════════════════════
    // Carregamento
    // ═══════════════════════════════════════════════════════════════

    private List<StrategiesProfile> loadFromClasspath(String classpathLocation) {
        try {
            ClassPathResource resource = new ClassPathResource(classpathLocation);

            if (!resource.exists()) {
                throw new IllegalStateException(
                        "File not found in classpath: " + classpathLocation);
            }

            try (InputStream in = resource.getInputStream()) {
                JsonNode root = mapper.readTree(in);
                return profileParser.parse(root, mapper);
            }

        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load " + classpathLocation, e);
        }
    }

    private List<StrategiesProfile> loadFromFilesystem(Path path) {
        try {
            String json = Files.readString(path);
            JsonNode root = mapper.readTree(json);
            return profileParser.parse(root, mapper);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load file " + path, e);
        }
    }

    private boolean isValidFilesystemPath(String path) {
        if (path == null || path.isBlank()) return false;
        try {
            return Files.exists(Path.of(path));
        } catch (Exception ignored) {
            return false;
        }
    }
}