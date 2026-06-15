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
 * <ul>
 *   <li>Carregar o {@code strategies.json} do classpath no startup ou
 *       do filesystem em execuções de backtest.</li>
 *   <li>Parsear e validar os {@link StrategiesProfile} via {@link StrategiesProfileParser}.</li>
 *   <li>Delegar a construção de instâncias de estratégias ao {@link StrategyBuilder}.</li>
 * </ul>
 *
 * O arquivo do classpath é carregado de forma antecipada no construtor (fail-fast),
 * garantindo que configurações inválidas ou ausentes sejam detectadas antes
 * do início da operação do bot.
 *
 * Fluxo de carregamento:
 * <ol>
 *   <li>Construtor carrega automaticamente do classpath.</li>
 *   <li>{@link #loadProfiles(String)} permite carregar de path externo para backtest.</li>
 *   <li>{@link #buildStrategies(StrategiesProfile)} delega a construção ao {@link StrategyBuilder}.</li>
 * </ol>
 */
@Component
public class StrategiesConfigLoader {

    private static final String DEFAULT_CLASSPATH = "configs/strategies.json";

    private final ObjectMapper mapper = new ObjectMapper();
    private final StrategyBuilder strategyBuilder;
    private final StrategiesProfileParser profileParser;

    /**
     * Profiles carregados e validados no startup a partir do classpath.
     * Fornecidos ao runtime via {@link #getProfiles()}.
     */
    private final List<StrategiesProfile> profiles;

    /**
     * Carrega e valida os profiles do {@code strategies.json} no classpath
     * durante a inicialização do contexto Spring.
     *
     * Falha imediatamente caso o arquivo não seja encontrado ou contenha
     * configurações inválidas.
     *
     * @param strategyBuilder builder responsável por instanciar as estratégias
     * @param profileParser   parser e validador de profiles do JSON
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
     * Retorna os profiles carregados no startup a partir do classpath.
     * Utilizado pelo {@code MultiSymbolDerivBotRunner} para configurar
     * os pipelines de cada ativo.
     *
     * @return lista imutável de profiles carregados
     */
    public List<StrategiesProfile> getProfiles() {
        return profiles;
    }

    /**
     * Carrega profiles de um caminho externo, com fallback para o classpath
     * caso o arquivo não exista no filesystem.
     *
     * Utilizado pelo {@code BacktestRunner} para execuções com arquivos
     * de configuração externos ao classpath padrão.
     *
     * @param path caminho do arquivo no filesystem ou no classpath
     * @return lista de profiles carregados e validados
     */
    public List<StrategiesProfile> loadProfiles(String path) {
        if (isValidFilesystemPath(path)) {
            return loadFromFilesystem(Path.of(path));
        }
        return loadFromClasspath(path);
    }

    /**
     * Localiza um profile pelo símbolo e granularidade, lançando exceção
     * caso não exista nos profiles carregados no startup.
     *
     * @param symbol             símbolo do ativo
     * @param granularitySeconds granularidade do candle em segundos
     * @return profile correspondente ao símbolo e granularidade informados
     * @throws IllegalStateException se o profile não for encontrado
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
     * Constrói as instâncias de estratégias habilitadas para o profile informado,
     * delegando a criação ao {@link StrategyBuilder}.
     *
     * @param profile profile do ativo cujas estratégias serão construídas
     * @return lista de estratégias habilitadas e instanciadas
     */
    public List<TradingStrategy> buildStrategies(StrategiesProfile profile) {
        return strategyBuilder.build(profile.getStrategies());
    }

    // ═══════════════════════════════════════════════════════════════
    // Carregamento
    // ═══════════════════════════════════════════════════════════════

    /**
     * Carrega e parseia o arquivo de configuração a partir do classpath.
     *
     * @param classpathLocation caminho relativo ao classpath
     * @return lista de profiles parseados e validados
     * @throws IllegalStateException se o arquivo não for encontrado ou falhar no parse
     */
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

    /**
     * Carrega e parseia o arquivo de configuração a partir do filesystem.
     *
     * @param path caminho absoluto ou relativo ao arquivo no filesystem
     * @return lista de profiles parseados e validados
     * @throws IllegalStateException se a leitura ou o parse falharem
     */
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

    /**
     * Verifica se o caminho informado corresponde a um arquivo existente no filesystem.
     *
     * Retorna {@code false} para caminhos nulos, em branco ou que lancem
     * exceção durante a verificação de existência.
     *
     * @param path caminho a ser verificado
     * @return {@code true} se o arquivo existir no filesystem, {@code false} caso contrário
     */
    private boolean isValidFilesystemPath(String path) {
        if (path == null || path.isBlank()) return false;
        try {
            return Files.exists(Path.of(path));
        } catch (Exception ignored) {
            return false;
        }
    }
}