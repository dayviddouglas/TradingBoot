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
 * - Gerar relatório textual legível com resumo do comportamento do ativo
 *
 * Arquivos gerados por ativo por dia:
 * - regime_history_{symbol}_{date}.json  → dados técnicos estruturados
 * - regime_summary_{symbol}_{date}.txt   → relatório legível para humanos
 *
 * Atualização v5.4.2:
 * - Timestamps agora usam o tempo real de mercado (Bar.timestamp())
 *   em vez do Instant.now() do processamento, garantindo que os
 *   relatórios reflitam o comportamento real do mercado.
 * - Campo durationMinutes calculado com base nos timestamps de mercado.
 * - Campo processingTimestamp mantido no JSON como metadado de diagnóstico.
 * - Arquivo regime_summary_{symbol}_{date}.txt gerado após cada transição.
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

    private static final int SEPARATOR_WIDTH = 52;

    private final ObjectMapper mapper;

    public RegimeHistoryService() {
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    // ═══════════════════════════════════════════════════════════════
    // API pública
    // ═══════════════════════════════════════════════════════════════

    /**
     * Persiste um evento de mudança de regime e atualiza os relatórios.
     *
     * Usa event.timestamp() como tempo real de mercado para o relatório.
     * Usa event.processingTimestamp() como metadado técnico no JSON.
     *
     * @param event evento de mudança de regime confirmada
     */
    public synchronized void record(RegimeChangeEvent event) {
        try {
            Files.createDirectories(REPORT_DIR);

            // Agrupa por dia usando o timestamp REAL de mercado
            LocalDate date = event.timestamp()
                    .atZone(ZONE)
                    .toLocalDate();

            Path jsonFile    = resolveJsonPath(event.symbol(), date);
            Path summaryFile = resolveSummaryPath(event.symbol(), date);

            List<Map<String, Object>> events = loadExistingEvents(jsonFile);

            // Calcula duração do regime anterior usando tempo de mercado
            updatePreviousRegimeDuration(events, event.timestamp());

            events.add(toJsonMap(event));

            mapper.writeValue(jsonFile.toFile(), events);

            writeSummary(summaryFile, event.symbol(), date, events);

            log.debug("REGIME HISTORY SAVED | symbol={} | {} → {} | file={}",
                    event.symbol(),
                    event.previousRegime(),
                    event.currentRegime(),
                    jsonFile.getFileName());

        } catch (Exception e) {
            log.error("REGIME HISTORY WRITE FAILED | symbol={} | event={}",
                    event.symbol(), event.toLogString(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Cálculo de duração
    // ═══════════════════════════════════════════════════════════════

    /**
     * Atualiza a duração do último evento com base no timestamp
     * de mercado do próximo regime.
     *
     * A duração representa o tempo REAL de mercado que o ativo
     * ficou naquele regime, calculado pela diferença entre os
     * timestamps dos candles que geraram cada confirmação.
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

        // Usa marketTimestamp para cálculo de duração real (v5.4.2)
        String prevTimestampStr = (String) last.get("marketTimestamp");
        if (prevTimestampStr == null) return;

        Instant prevStart = Instant.parse(prevTimestampStr);
        long    minutes   = Duration.between(prevStart, nextStart)
                .toMinutes();

        last.put("durationMinutes", minutes);
    }

    // ═══════════════════════════════════════════════════════════════
    // Geração do relatório textual
    // ═══════════════════════════════════════════════════════════════

    /**
     * Gera o arquivo de resumo textual do comportamento de mercado.
     *
     * Regenerado completamente a cada nova transição para garantir
     * que durações e percentuais estejam sempre atualizados.
     */
    private void writeSummary(
            Path summaryFile,
            String symbol,
            LocalDate date,
            List<Map<String, Object>> events
    ) throws IOException {
        if (events.isEmpty()) return;

        StringBuilder sb = new StringBuilder();

        appendHeader(sb, symbol, date);
        appendTimeline(sb, events);
        appendSummary(sb, events);
        appendCurrentRegime(sb, events);

        Files.writeString(summaryFile, sb.toString());
    }

    private void appendHeader(
            StringBuilder sb,
            String symbol,
            LocalDate date
    ) {
        sb.append("\n")
                .append(symbol).append(" — ").append(date).append("\n")
                .append("━".repeat(SEPARATOR_WIDTH)).append("\n");
    }

    /**
     * Escreve a timeline de regimes com horários reais de mercado,
     * saída e duração.
     *
     * O horário exibido é o do último candle que gerou a confirmação,
     * representando quando o regime foi detectado no mercado real.
     */
    private void appendTimeline(
            StringBuilder sb,
            List<Map<String, Object>> events
    ) {
        sb.append("\nTIMELINE DE REGIMES\n")
                .append("─".repeat(SEPARATOR_WIDTH)).append("\n");

        for (int i = 0; i < events.size(); i++) {
            Map<String, Object> event  = events.get(i);
            boolean             isLast = (i == events.size() - 1);

            String regime    = (String) event.get("currentRegime");
            String entryTime = formatMarketTime(
                    event.get("marketTimestampBrasilia"));
            String regimePadded = String.format("%-9s", regime);

            if (isLast) {
                long minutesOngoing = calcOngoingMinutesFromMarket(event);
                sb.append(String.format(
                        "  %s → entrou %s → em andamento (%dmin)\n",
                        regimePadded, entryTime, minutesOngoing));
            } else {
                Map<String, Object> nextEvent = events.get(i + 1);
                String exitTime = formatMarketTime(
                        nextEvent.get("marketTimestampBrasilia"));
                long duration = toLong(event.get("durationMinutes"));

                sb.append(String.format(
                        "  %s → entrou %s → saiu %s → durou %4dmin\n",
                        regimePadded, entryTime, exitTime, duration));
            }
        }
    }

    /**
     * Escreve o resumo percentual do tempo em cada regime.
     *
     * O regime atual é contabilizado com o tempo decorrido
     * desde sua confirmação até agora em tempo de mercado.
     */
    private void appendSummary(
            StringBuilder sb,
            List<Map<String, Object>> events
    ) {
        sb.append("\nRESUMO DO DIA\n")
                .append("─".repeat(SEPARATOR_WIDTH)).append("\n");

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

        if (totalMinutes == 0) {
            sb.append("  Dados insuficientes para resumo.\n");
            return;
        }

        long finalTotal = totalMinutes;
        minutesByRegime.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<String, Long>comparingByValue()
                        .reversed())
                .forEach(e -> {
                    String regime  = e.getKey();
                    long   minutes = e.getValue();
                    double pct     = (minutes * 100.0) / finalTotal;

                    sb.append(String.format(
                            "  %-9s → %5.1f%% do tempo  (%dmin)\n",
                            regime, pct, minutes));
                });

        sb.append(String.format(
                "\n  Total observado: %dmin (~%.1fh)\n",
                totalMinutes, totalMinutes / 60.0));
    }

    /**
     * Escreve o regime atual com horário de mercado de início
     * e tempo decorrido desde a confirmação.
     */
    private void appendCurrentRegime(
            StringBuilder sb,
            List<Map<String, Object>> events
    ) {
        if (events.isEmpty()) return;

        Map<String, Object> last      = events.get(events.size() - 1);
        String              regime    = (String) last.get("currentRegime");
        String              entryTime = formatMarketTime(
                last.get("marketTimestampBrasilia"));
        long                minutes   = calcOngoingMinutesFromMarket(last);

        sb.append("\n").append("─".repeat(SEPARATOR_WIDTH)).append("\n")
                .append(String.format(
                        "  Regime atual: %s  (desde %s — %dmin atrás)\n",
                        regime, entryTime, minutes))
                .append("━".repeat(SEPARATOR_WIDTH)).append("\n\n");
    }

    // ═══════════════════════════════════════════════════════════════
    // Conversão para Map JSON
    // ═══════════════════════════════════════════════════════════════

    /**
     * Converte RegimeChangeEvent para Map para serialização JSON.
     *
     * Campos de tempo (v5.4.2):
     * - marketTimestamp: tempo real do candle que gerou a confirmação
     * - marketTimestampBrasilia: idem em horário de Brasília
     * - processingTimestamp: quando o sistema processou (diagnóstico)
     *
     * @param event evento de mudança de regime
     * @return Map pronto para serialização JSON
     */
    private Map<String, Object> toJsonMap(RegimeChangeEvent event) {
        Map<String, Object> map = new LinkedHashMap<>();

        // Tempo real de mercado — usado para relatórios e durações
        map.put("marketTimestamp",
                event.timestamp().toString());
        map.put("marketTimestampBrasilia",
                event.timestamp().atZone(ZONE)
                        .toOffsetDateTime().toString());
        map.put("dateBrasilia",
                event.timestamp().atZone(ZONE)
                        .toLocalDate().toString());

        // Tempo de processamento — metadado técnico de diagnóstico
        map.put("processingTimestamp",
                event.processingTimestamp().toString());

        map.put("symbol",         event.symbol());
        map.put("previousRegime", event.previousRegime().name());
        map.put("currentRegime",  event.currentRegime().name());

        // Duração em minutos — preenchido na próxima transição
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadExistingEvents(Path jsonFile)
            throws IOException {
        List<Map<String, Object>> events = new ArrayList<>();

        if (Files.exists(jsonFile)) {
            Map[] existing = mapper.readValue(
                    jsonFile.toFile(), Map[].class);
            for (Map e : existing) {
                events.add((Map<String, Object>) e);
            }
        }

        return events;
    }

    // ═══════════════════════════════════════════════════════════════
    // Utilitários
    // ═══════════════════════════════════════════════════════════════

    /**
     * Calcula minutos decorridos desde o timestamp de mercado
     * de início do regime atual até agora.
     *
     * Usa o marketTimestamp real do candle para garantir coerência
     * com os demais cálculos de duração do relatório.
     *
     * @param event mapa de dados do evento
     * @return minutos decorridos
     */
    private long calcOngoingMinutesFromMarket(
            Map<String, Object> event
    ) {
        String ts = (String) event.get("marketTimestamp");
        if (ts == null) return 0L;

        try {
            Instant start = Instant.parse(ts);
            return Duration.between(start, Instant.now()).toMinutes();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Formata o timestamp de Brasília como HH:mm para o relatório.
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

    private Path resolveJsonPath(String symbol, LocalDate date) {
        return REPORT_DIR.resolve(
                "regime_history_" + symbol + "_" + date + ".json");
    }

    private Path resolveSummaryPath(String symbol, LocalDate date) {
        return REPORT_DIR.resolve(
                "regime_summary_" + symbol + "_" + date + ".txt");
    }
}