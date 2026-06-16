package com.github.dayviddouglas.TradingBoot.tools.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * Responsável pela persistência do histórico de candles em arquivos JSON locais.
 *
 * Cada ativo e granularidade é armazenado em um arquivo dedicado no diretório
 * {@code ./data/history/}, nomeado como {@code {symbol}_{granularitySeconds}.json}.
 * Exemplo: {@code data/history/frxEURUSD_60.json}.
 *
 * A escrita utiliza o padrão de arquivo temporário ({@code .tmp}) seguido de
 * renomeação atômica, garantindo que o arquivo de destino nunca fique em estado
 * parcialmente escrito em caso de falha durante a gravação.
 * Quando a renomeação atômica não é suportada pelo sistema de arquivos,
 * utiliza {@link StandardCopyOption#REPLACE_EXISTING} como fallback.
 */
public class HistoryFileStore {

    private static final ObjectMapper mapper = new ObjectMapper();

    /** Diretório base onde os arquivos de histórico são armazenados. */
    private final Path baseDir;

    /**
     * Inicializa o store com o diretório padrão {@code ./data/history/}.
     */
    public HistoryFileStore() {
        this.baseDir = Path.of("data", "history");
    }

    /**
     * Resolve o caminho completo do arquivo para o símbolo e granularidade informados.
     *
     * @param symbol             símbolo do ativo (ex: {@code "frxEURUSD"})
     * @param granularitySeconds granularidade dos candles em segundos (ex: {@code 60})
     * @return caminho relativo ao diretório base
     */
    public Path pathFor(String symbol, int granularitySeconds) {
        String fileName = symbol + "_" + granularitySeconds + ".json";
        return baseDir.resolve(fileName);
    }

    /**
     * Persiste o JSON do histórico no arquivo correspondente ao símbolo e granularidade.
     * Cria o diretório base se não existir. A escrita é feita em arquivo temporário
     * e concluída com renomeação atômica para evitar estados parcialmente escritos.
     *
     * @param symbol             símbolo do ativo
     * @param granularitySeconds granularidade dos candles em segundos
     * @param json               JSON do histórico a ser persistido
     * @throws RuntimeException se ocorrer falha na escrita ou renomeação do arquivo
     */
    public void write(String symbol, int granularitySeconds, JsonNode json) {
        Path outFile = pathFor(symbol, granularitySeconds);

        try {
            Files.createDirectories(outFile.getParent());

            String pretty = mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(json);

            // Escreve em arquivo temporário antes de renomear atomicamente
            Path tmp = outFile.resolveSibling(outFile.getFileName() + ".tmp");
            Files.writeString(tmp, pretty, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);

            try {
                Files.move(tmp, outFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Fallback quando o sistema de arquivos não suporta renomeação atômica
                Files.move(tmp, outFile, StandardCopyOption.REPLACE_EXISTING);
            }

        } catch (IOException e) {
            throw new RuntimeException(
                    "Falha ao salvar histórico em: " + outFile.toAbsolutePath(), e);
        }
    }
}