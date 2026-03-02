package com.github.dayviddouglas.TradingBot.tools.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * Salva histórico em arquivo JSON:
 * - 1 arquivo por (symbol + granularitySeconds)
 * - diretório: ./data/history/
 *
 * Exemplo:
 *   data/history/frxEURUSD_60.json
 */
public class HistoryFileStore {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final Path baseDir;

    public HistoryFileStore() {
        this.baseDir = Path.of("data", "history");
    }

    public Path pathFor(String symbol, int granularitySeconds) {
        String fileName = symbol + "_" + granularitySeconds + ".json";
        return baseDir.resolve(fileName);
    }

    public void write(String symbol, int granularitySeconds, JsonNode json) {
        Path outFile = pathFor(symbol, granularitySeconds);

        try {
            Files.createDirectories(outFile.getParent());

            String pretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(json);

            Path tmp = outFile.resolveSibling(outFile.getFileName() + ".tmp");
            Files.writeString(tmp, pretty, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            try {
                Files.move(tmp, outFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, outFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RuntimeException("Falha ao salvar histórico em: " + outFile.toAbsolutePath(), e);
        }
    }
}