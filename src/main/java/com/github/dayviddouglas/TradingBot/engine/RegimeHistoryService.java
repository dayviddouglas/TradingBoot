package com.github.dayviddouglas.TradingBot.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serviço responsável pela persistência de eventos de mudança de regime
 * e geração de relatório diário de comportamento de mercado por ativo.
 *
 * Responsabilidades:
 * - Serializar RegimeChangeEvent em arquivo JSON diário por ativo
 * - Calcular duração de cada regime (tempo real entre candles de mercado)
 * - Gerar bloco de resumo consolidado dentro do mesmo arquivo JSON
 *
 * Arquivo gerado por ativo por dia (v5.4.4):
 * - regime_report_{symbol}_{date}.json → dados técnicos + resumo unificado
 *
 * Estrutura do arquivo JSON unificado:
 * {
 *   "symbol": "frxAUDUSD",
 *   "date": "2026-06-02",
 *   "events": [ ... ],
 *   "summary": {
 *     "totalMinutes": 135,
 *     "distribution": {
 *       "RANGING":  { "minutes": 58, "percent": 42.5 },
 *       "TRENDING": { "minutes": 45, "percent": 33.0 },
 *       "CHOPPY":   { "minutes": 32, "percent": 23.5 }
 *     },
 *     "currentRegime": "CHOPPY",
 *     "currentRegimeSince": "12:46"
 *   }
 * }
 *
 * Atualização v5.4.3:
 * - Timestamps agora usam o tempo real de mercado (Bar.timestamp())
 *   em vez do Instant.now() do processamento.
 * - Campo durationMinutes calculado com base nos timestamps de mercado.
 * - Campo processingTimestamp mantido no JSON como metadado de diagnóstico.
 *
 * Atualização v5.4.4:
 * - Arquivo TXT removido. Resumo incorporado diretamente no JSON como
 *   bloco "summary" calculado após cada transição de regime.
 * - Duração negativa corrigida via Math.max(0, minutes) em vez de
 *   assumir ordenação garantida dos marketTimestamps durante o warm-up.
 * - Arquivo renomeado de regime_history_{symbol}_{date}.json para
 *   regime_report_{symbol}_{date}.json.
 *
 * Thread-safety:
 * synchronized em record() protege contra escritas concorrentes de
 * múltiplos símbolos confirmando transição quase simultaneamente.
 */
@Service
public class RegimeHistoryService {

    private static final Logger log =
            LoggerFactory.getLogger(RegimeHistoryService.class);

    private static final ZoneId ZONE =
            ZoneId.of("America/Sao_Paulo");

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm");

    private static final Path REPORT_DIR =
            Path.of("data", "reports");

    private final ObjectMapper mapper;

    public RegimeHistoryService() {
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    // ═══════════════════════════════════════════════════════════════
    // API pública
    // ═══════════════════════════════════════════════════════════════

    /**
     * Persiste um evento de mudança de regime e atualiza o relatório.
     *
     * Usa event.timestamp() como tempo real de mercado para durações.
     * Usa event.processingTimestamp() como metadado técnico no JSON.
     *
     * @param event evento de mudança de regime confirmada
     */
    public synchronized void record(RegimeChangeEvent event) {
        try {
            Files.createDirectories(REPORT_DIR);

            LocalDate date = event.timestamp()
                    .atZone(ZONE)
                    .toLocalDate();

            Path reportFile = resolveReportPath(event.symbol(), date);

            List<Map<String, Object>> events = loadExistingEvents(reportFile);

            // Calcula duração do regime anterior usando tempo de mercado.
            // Math.max(0, ...) garante que durações negativas — possíveis
            // durante o warm-up histórico por sobreposição de janelas —
            // sejam tratadas como zero em vez de valores inválidos.
            updatePreviousRegimeDuration(events, event.timestamp());

            events.add(toJsonMap(event));

            Map<String, Object> report = buildReport(
                    event.symbol(), date, events);

            mapper.writeValue(reportFile.toFile(), report);

            log.debug("REGIME REPORT SAVED | symbol={} | {} → {} | file={}",
                    event.symbol(),
                    event.previousRegime(),
                    event.currentRegime(),
                    reportFile.getFileName());

        } catch (Exception e) {
            log.error("REGIME REPORT WRITE FAILED | symbol={} | event={}",
                    event.symbol(), event.toLogString(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Construção do relatório unificado
    // ═══════════════════════════════════════════════════════════════

    /**
     * Constrói o Map raiz do relatório JSON unificado.
     *
     * Estrutura:
     * - symbol, date: identificação do relatório
     * - events: lista de transições de regime com métricas técnicas
     * - summary: bloco calculado com distribuição e regime atual
     *
     * @param symbol símbolo do ativo
     * @param date   data do relatório
     * @param events lista de eventos de transição já serializada
     * @return Map pronto para serialização JSON
     */
    private Map<String, Object> buildReport(
            String symbol,
            LocalDate date,
            List<Map<String, Object>> events
    ) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("symbol",  symbol);
        report.put("date",    date.toString());
        report.put("summary", buildSummary(events));
        report.put("events",  events);

        return report;
    }

    /**
     * Calcula o bloco de resumo com distribuição de regimes e regime atual.
     *
     * Distribuição calculada com base nas durações reais de mercado.
     * O regime atual usa o tempo decorrido desde o marketTimestamp
     * do último evento até o momento da geração do relatório.
     *
     * @param events lista de eventos serializada
     * @return Map com totalMinutes, distribution, currentRegime,
     *         currentRegimeSince
     */
    private Map<String, Object> buildSummary(
            List<Map<String, Object>> events
    ) {
        Map<String, Object> summary = new LinkedHashMap<>();

        if (events.isEmpty()) {
            summary.put("totalMinutes",      0L);
            summary.put("distribution",      Map.of());
            summary.put("currentRegime",     null);
            summary.put("currentRegimeSince", null);
            return summary;
        }

        Map<String, Long> minutesByRegime = new LinkedHashMap<>();
        minutesByRegime.put("RANGING",  0L);
        minutesByRegime.put("TRENDING", 0L);
        minutesByRegime.put("CHOPPY",   0L);

        long totalMinutes = 0L;

        for (int i = 0; i < events.size(); i++) {
            Map<String, Object> event  = events.get(i);
            String              regime = (String) event.get("currentRegime");
            boolean             isLast = (i == events.size() - 1);

            long minutes = isLast
                    ? calcOngoingMinutesFromMarket(event)
                    : toLong(event.get("durationMinutes"));

            minutesByRegime.merge(regime, minutes, Long::sum);
            totalMinutes += minutes;
        }

        // Bloco distribution — ordenado do mais frequente para o menos
        Map<String, Object> distribution = new LinkedHashMap<>();
        long finalTotal = totalMinutes;

        minutesByRegime.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> {
                    String regime  = e.getKey();
                    long   minutes = e.getValue();
                    double percent = finalTotal > 0
                            ? (minutes * 100.0) / finalTotal
                            : 0.0;

                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("minutes", minutes);
                    entry.put("percent", Math.round(percent * 10.0) / 10.0);
                    distribution.put(regime, entry);
                });

        // Regime atual
        Map<String, Object> lastEvent = events.get(events.size() - 1);
        String currentRegime     = (String) lastEvent.get("currentRegime");
        String currentRegimeSince = formatMarketTime(
                lastEvent.get("marketTimestampBrasilia"));

        summary.put("totalMinutes",       totalMinutes);
        summary.put("distribution",       distribution);
        summary.put("currentRegime",      currentRegime);
        summary.put("currentRegimeSince", currentRegimeSince);

        return summary;
    }

    // ═══════════════════════════════════════════════════════════════
    // Cálculo de duração
    // ═══════════════════════════════════════════════════════════════

    /**
     * Atualiza a duração do último evento com base no timestamp
     * de mercado do próximo regime.
     *
     * Durante o warm-up histórico, a janela deslizante de 200 candles
     * pode gerar marketTimestamps com sobreposição, resultando em
     * diferença negativa entre eventos consecutivos.
     * Math.max(0, minutes) trata esse caso como duração zero,
     * preservando a integridade do relatório sem mascarar o dado bruto.
     *
     * @param events    lista de eventos existentes
     * @param nextStart timestamp de mercado do início do próximo regime
     */
    private void updatePreviousRegimeDuration(
            List<Map<String, Object>> events,
            Instant nextStart
    ) {
        if (events.isEmpty()) return;

        Map<String, Object> last = events.get(events.size() - 1);

        String prevTimestampStr = (String) last.get("marketTimestamp");
        if (prevTimestampStr == null) return;

        Instant prevStart = Instant.parse(prevTimestampStr);
        long    minutes   = Duration.between(prevStart, nextStart)
                .toMinutes();

        // Garante duração não-negativa.
        // Valores negativos ocorrem durante o warm-up por sobreposição
        // de janelas deslizantes no replay do histórico.
        last.put("durationMinutes", Math.max(0L, minutes));
    }

    // ═══════════════════════════════════════════════════════════════
    // Conversão para Map JSON
    // ═══════════════════════════════════════════════════════════════

    /**
     * Converte RegimeChangeEvent para Map para serialização JSON.
     *
     * Campos de tempo:
     * - marketTimestamp: tempo real do candle que gerou a confirmação
     * - marketTimestampBrasilia: idem em horário de Brasília
     * - processingTimestamp: quando o sistema processou (diagnóstico)
     *
     * @param event evento de mudança de regime
     * @return Map pronto para serialização JSON
     */
    private Map<String, Object> toJsonMap(RegimeChangeEvent event) {
        Map<String, Object> map = new LinkedHashMap<>();

        map.put("marketTimestamp",
                event.timestamp().toString());
        map.put("marketTimestampBrasilia",
                event.timestamp().atZone(ZONE)
                        .toOffsetDateTime().toString());
        map.put("dateBrasilia",
                event.timestamp().atZone(ZONE)
                        .toLocalDate().toString());
        map.put("processingTimestamp",
                event.processingTimestamp().toString());

        map.put("symbol",         event.symbol());
        map.put("previousRegime", event.previousRegime().name());
        map.put("currentRegime",  event.currentRegime().name());
        map.put("durationMinutes", null);

        RegimeMetrics metrics = event.metrics();
        map.put("atrFast",      metrics.atrFast());
        map.put("atrBase",      metrics.atrBase());
        map.put("atrRatio",     metrics.atrRatio());
        map.put("emaDistance",  metrics.emaDistance());
        map.put("efficiency",   metrics.efficiency());
        map.put("metricsValid", metrics.isValid());

        return map;
    }

    // ═══════════════════════════════════════════════════════════════
    // Leitura do arquivo existente
    // ═══════════════════════════════════════════════════════════════

    /**
     * Carrega a lista de eventos do arquivo JSON existente.
     *
     * Lê o campo "events" do relatório unificado se o arquivo já existir.
     * Retorna lista vazia se o arquivo não existir ou o campo
     * "events" não estiver presente.
     *
     * @param reportFile caminho do arquivo de relatório
     * @return lista mutável de eventos existentes
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadExistingEvents(Path reportFile)
            throws IOException {
        List<Map<String, Object>> events = new ArrayList<>();

        if (!Files.exists(reportFile)) return events;

        Map<String, Object> existing = mapper.readValue(
                reportFile.toFile(), Map.class);

        Object eventsNode = existing.get("events");
        if (eventsNode instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    events.add((Map<String, Object>) map);
                }
            }
        }

        return events;
    }

    // ═══════════════════════════════════════════════════════════════
    // Utilitários
    // ═══════════════════════════════════════════════════════════════

    /**
     * Calcula minutos decorridos desde o marketTimestamp do regime
     * atual até o momento da geração do relatório.
     *
     * @param event mapa de dados do evento
     * @return minutos decorridos, ou 0 se o timestamp for inválido
     */
    private long calcOngoingMinutesFromMarket(
            Map<String, Object> event
    ) {
        String ts = (String) event.get("marketTimestamp");
        if (ts == null) return 0L;

        try {
            Instant start = Instant.parse(ts);
            return Math.max(0L,
                    Duration.between(start, Instant.now()).toMinutes());
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Formata o timestamp de Brasília como HH:mm para o resumo.
     *
     * @param timestampBrasilia string ISO com offset de Brasília
     * @return string HH:mm ou "--:--" se inválido
     */
    private String formatMarketTime(Object timestampBrasilia) {
        if (timestampBrasilia == null) return "--:--";
        try {
            ZonedDateTime zdt = ZonedDateTime.parse(
                    timestampBrasilia.toString(),
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME
                            .withZone(ZONE));
            return zdt.format(TIME_FMT);
        } catch (Exception e) {
            return "--:--";
        }
    }

    private long toLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number n) return n.longValue();
        return 0L;
    }

    private Path resolveReportPath(String symbol, LocalDate date) {
        return REPORT_DIR.resolve(
                "regime_report_" + symbol + "_" + date + ".json");
    }
}