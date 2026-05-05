package com.github.dayviddouglas.TradingBot.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serviço responsável pela persistência de eventos de mudança de regime em arquivos JSON diários.
 *
 * A anotação @Service registra esta classe como bean no container IoC do Spring,
 * permitindo injeção no MarketRegimeMonitor via construtor.
 *
 * Responsabilidade única: serializar RegimeChangeEvent em arquivos JSON organizados
 * por símbolo e data, criando auditoria completa da evolução de regime ao longo do tempo.
 *
 * Convenção de nomeação dos arquivos:
 * - Diretório: data/reports/
 * - Arquivo: regime_history_{symbol}_{date}.json
 * - Exemplo: data/reports/regime_history_frxEURUSD_2025-01-15.json
 *
 * Estrutura do arquivo JSON:
 * [
 *   {
 *     "timestamp": "2025-01-15T10:30:00Z",
 *     "timestampBrasilia": "2025-01-15T07:30:00-03:00",
 *     "symbol": "frxEURUSD",
 *     "previousRegime": "RANGING",
 *     "currentRegime": "TRENDING",
 *     "atrFast": 0.000123,
 *     "atrBase": 0.000098,
 *     "atrRatio": 1.255,
 *     "emaDistance": 0.000045,
 *     "efficiency": 0.342
 *   }
 * ]
 *
 * O método record() é synchronized para proteger contra escritas concorrentes.
 * Embora o MarketRegimeMonitor despache para virtual thread, dois símbolos
 * diferentes poderiam confirmar transição quase simultaneamente, e ambos
 * chamariam record() em threads diferentes.
 *
 * Alinhamento com TradeReportService:
 * - Mesmo diretório (data/reports/)
 * - Mesmo timezone de referência (America/Sao_Paulo para agrupamento por dia)
 * - Mesma estratégia de leitura + append + reescrita do arquivo JSON
 *
 * ⚠️ Ponto de atenção: Para dias com muitas transições de regime (ex: mercado
 * muito instável), a releitura do arquivo a cada evento pode impactar performance.
 * O cenário típico tem poucas transições por dia, tornando essa abordagem adequada.
 *
 * ⚠️ Ponto de atenção: Erros de escrita são logados mas não propagados,
 * para evitar que falhas de persistência interrompam o pipeline de trading.
 */
@Service
public class RegimeHistoryService {

    private static final Logger log = LoggerFactory.getLogger(RegimeHistoryService.class);

    /**
     * Timezone de Brasília para agrupamento por dia operacional.
     * Consistente com o TradeReportService para facilitar correlação entre
     * relatórios de regime e relatórios de trades.
     */
    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    /**
     * Diretório de saída dos arquivos de histórico de regime.
     * Mesmo diretório do TradeReportService para centralizar relatórios.
     */
    private static final Path REPORT_DIR = Path.of("data", "reports");

    /**
     * ObjectMapper configurado para JSON indentado (legível por humanos).
     *
     * Instância própria para evitar dependência do ObjectMapper global do Spring,
     * que pode ter configurações diferentes (ex: sem INDENT_OUTPUT).
     */
    private final ObjectMapper mapper;

    /**
     * Construtor que inicializa o ObjectMapper com formatação indentada.
     */
    public RegimeHistoryService() {
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Persiste um evento de mudança de regime no arquivo JSON diário correspondente.
     *
     * O agrupamento é por (symbol + data em Brasília), gerando um arquivo por
     * ativo por dia. Isso facilita análise de regime por sessão de trading:
     * - regime_history_frxEURUSD_2025-01-15.json (toda a sessão de 15/01)
     * - regime_history_frxXAUUSD_2025-01-15.json (toda a sessão de 15/01)
     *
     * O synchronized garante serialização das escritas quando múltiplos símbolos
     * confirmam transição de regime em instantes próximos (virtual threads distintas).
     *
     * Erros de I/O são capturados e logados sem propagar, para não interromper
     * o pipeline de monitoramento de regime.
     *
     * @param event evento de mudança de regime confirmada pelo RegimeStateTracker
     */
    public synchronized void record(RegimeChangeEvent event) {
        try {
            Files.createDirectories(REPORT_DIR);

            // Agrupa por dia em horário de Brasília para consistência operacional
            LocalDate date = event.timestamp()
                    .atZone(ZONE)
                    .toLocalDate();

            Path jsonFile = resolveFilePath(event.symbol(), date);

            // Lê eventos existentes do arquivo ou inicia lista vazia
            List<Map<String, Object>> events = loadExistingEvents(jsonFile);

            // Adiciona o novo evento convertido para Map
            events.add(toJsonMap(event));

            // Reescreve o arquivo com todos os eventos (incluindo o novo)
            mapper.writeValue(jsonFile.toFile(), events);

            log.debug("REGIME HISTORY SAVED | symbol={} | {} → {} | file={}",
                    event.symbol(),
                    event.previousRegime(),
                    event.currentRegime(),
                    jsonFile.getFileName());

        } catch (Exception e) {
            // Falha de persistência não deve interromper o pipeline de trading
            log.error("REGIME HISTORY WRITE FAILED | symbol={} | event={}",
                    event.symbol(), event.toLogString(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Leitura do arquivo existente
    // ═══════════════════════════════════════════════════════════════

    /**
     * Carrega os eventos existentes do arquivo JSON ou retorna lista vazia.
     *
     * O cast @SuppressWarnings("unchecked") é necessário porque o Jackson
     * deserializa Map[] sem informação genérica (type erasure em runtime).
     * O mesmo padrão é usado no TradeReportService por consistência.
     *
     * @param jsonFile caminho do arquivo JSON diário
     * @return lista mutável com os eventos já registrados
     * @throws IOException se houver erro na leitura do arquivo
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadExistingEvents(Path jsonFile) throws IOException {
        List<Map<String, Object>> events = new ArrayList<>();

        if (Files.exists(jsonFile)) {
            Map[] existing = mapper.readValue(jsonFile.toFile(), Map[].class);
            for (Map existingEvent : existing) {
                events.add((Map<String, Object>) existingEvent);
            }
        }

        return events;
    }

    // ═══════════════════════════════════════════════════════════════
    // Conversão para Map JSON
    // ═══════════════════════════════════════════════════════════════

    /**
     * Converte um RegimeChangeEvent para Map<String, Object> para serialização via Jackson.
     *
     * LinkedHashMap preserva a ordem de inserção dos campos no JSON gerado,
     * tornando os arquivos mais legíveis e previsíveis para inspeção manual.
     *
     * Inclui timestamp em UTC (referência técnica) e em Brasília (referência
     * operacional), seguindo o mesmo padrão do TradeReportService.
     *
     * @param event evento de mudança de regime
     * @return Map com todos os campos prontos para serialização JSON
     */
    private Map<String, Object> toJsonMap(RegimeChangeEvent event) {
        Map<String, Object> map = new LinkedHashMap<>();

        // Timestamps em UTC e Brasília para máxima flexibilidade de análise
        map.put("timestamp", event.timestamp().toString());
        map.put("timestampBrasilia",
                event.timestamp().atZone(ZONE).toOffsetDateTime().toString());
        map.put("dateBrasilia",
                event.timestamp().atZone(ZONE).toLocalDate().toString());

        // Contexto da transição
        map.put("symbol", event.symbol());
        map.put("previousRegime", event.previousRegime().name());
        map.put("currentRegime", event.currentRegime().name());

        // Métricas técnicas que fundamentaram a classificação decisiva
        // (a terceira avaliação consecutiva que confirmou a transição)
        RegimeMetrics metrics = event.metrics();
        map.put("atrFast", metrics.atrFast());
        map.put("atrBase", metrics.atrBase());
        map.put("atrRatio", metrics.atrRatio());
        map.put("emaDistance", metrics.emaDistance());
        map.put("efficiency", metrics.efficiency());
        map.put("metricsValid", metrics.isValid());

        return map;
    }

    // ═══════════════════════════════════════════════════════════════
    // Utilitários
    // ═══════════════════════════════════════════════════════════════

    /**
     * Resolve o caminho do arquivo JSON para um símbolo e data específicos.
     *
     * Formato: data/reports/regime_history_{symbol}_{date}.json
     *
     * @param symbol símbolo do ativo
     * @param date   data em horário de Brasília
     * @return caminho completo do arquivo
     */
    private Path resolveFilePath(String symbol, LocalDate date) {
        String fileName = "regime_history_" + symbol + "_" + date + ".json";
        return REPORT_DIR.resolve(fileName);
    }
}