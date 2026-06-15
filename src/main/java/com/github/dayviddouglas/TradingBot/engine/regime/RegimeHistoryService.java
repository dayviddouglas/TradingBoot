package com.github.dayviddouglas.TradingBot.engine.regime;

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
 * Responsável pela persistência de eventos de mudança de regime e pela geração
 * do relatório diário unificado de comportamento de mercado por ativo.
 *
 * Gera um único arquivo JSON por ativo por dia em um subdiretório separado por
 * data dentro de {@code data/reports/{date}/},
 * nomeado {@code regime_report_{symbol}_{date}.json}, contendo:
 * <ul>
 *   <li>{@code events}: lista de transições de regime com métricas técnicas e timestamps</li>
 *   <li>{@code summary}: bloco calculado após cada transição com distribuição de regimes
 *       em minutos, percentuais, regime atual e horário de início (fuso de Brasília)</li>
 * </ul>
 *
 * Os timestamps dos eventos utilizam o tempo real de mercado extraído do
 * {@link RegimeChangeEvent#timestamp()}, que corresponde ao timestamp do último candle
 * da janela de classificação. O {@link RegimeChangeEvent#processingTimestamp()} é mantido
 * no JSON apenas como metadado de diagnóstico.
 *
 * As durações de cada regime são calculadas com base nos timestamps reais de mercado.
 * Durante o warm-up histórico, a janela deslizante de 200 candles pode gerar timestamps
 * com sobreposição, resultando em diferenças negativas entre eventos consecutivos.
 * Nesses casos, {@code Math.max(0, minutes)} garante duração não-negativa sem mascarar
 * o dado bruto.
 *
 * O método {@link #record} é {@code synchronized} para proteger contra escritas
 * concorrentes de múltiplos símbolos confirmando transição quase simultaneamente.
 */
@Service
public class RegimeHistoryService {

    private static final Logger log =
            LoggerFactory.getLogger(RegimeHistoryService.class);

    /** Fuso horário utilizado para exibição de timestamps no relatório. */
    private static final ZoneId ZONE =
            ZoneId.of("America/Sao_Paulo");

    /** Formatador para o campo {@code currentRegimeSince} no resumo. */
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm");

    /** Diretório raiz de saída dos arquivos de relatório de regime. */
    private static final Path REPORT_DIR =
            Path.of("data", "reports");

    private final ObjectMapper mapper;

    /**
     * Inicializa o serviço com um {@link ObjectMapper} configurado para saída indentada.
     */
    public RegimeHistoryService() {
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    // ═══════════════════════════════════════════════════════════════
    // API pública
    // ═══════════════════════════════════════════════════════════════

    /**
     * Persiste um evento de mudança de regime confirmada e atualiza o relatório JSON do dia.
     * Carrega os eventos existentes do arquivo, calcula a duração do regime anterior,
     * adiciona o novo evento e regrava o arquivo completo com o bloco {@code summary} atualizado.
     *
     * @param event evento de mudança de regime confirmado pelo {@link RegimeStateTracker}
     */
    public synchronized void record(RegimeChangeEvent event) {
        try {
            LocalDate date    = event.timestamp().atZone(ZONE).toLocalDate();
            Path      dateDir = resolveDateDir(date);

            Files.createDirectories(dateDir);

            Path reportFile = resolveReportPath(event.symbol(), date, dateDir);

            List<Map<String, Object>> events = loadExistingEvents(reportFile);

            // Calcula a duração do regime anterior usando o timestamp de mercado do novo evento.
            // Math.max(0, ...) trata durações negativas — possíveis durante o warm-up
            // por sobreposição de janelas deslizantes — como zero.
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
    // Resolução de diretório por data
    // ═══════════════════════════════════════════════════════════════

    /**
     * Resolve o subdiretório de relatórios para a data informada.
     * Os arquivos de cada dia são organizados em {@code data/reports/{date}/}.
     *
     * @param date data do relatório no fuso de Brasília
     * @return caminho do diretório correspondente à data
     */
    private Path resolveDateDir(LocalDate date) {
        return REPORT_DIR.resolve(date.toString());
    }

    // ═══════════════════════════════════════════════════════════════
    // Construção do relatório unificado
    // ═══════════════════════════════════════════════════════════════

    /**
     * Constrói o mapa raiz do relatório JSON unificado com os campos
     * {@code symbol}, {@code date}, {@code summary} e {@code events}.
     *
     * @param symbol símbolo do ativo
     * @param date   data do relatório
     * @param events lista de eventos de transição já serializada em mapas
     * @return mapa pronto para serialização JSON
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
     * Calcula o bloco {@code summary} com distribuição de regimes em minutos
     * e percentuais, regime atual e horário de início no fuso de Brasília.
     *
     * Para o último evento da lista, a duração é calculada como o tempo decorrido
     * desde o {@code marketTimestamp} do evento até o momento da geração do relatório,
     * representando o tempo em que o regime atual permanece ativo.
     * Os demais eventos utilizam o campo {@code durationMinutes} já calculado.
     * A distribuição é ordenada do regime mais frequente para o menos frequente.
     *
     * @param events lista de eventos de transição serializada
     * @return mapa com {@code totalMinutes}, {@code distribution},
     *         {@code currentRegime} e {@code currentRegimeSince}
     */
    private Map<String, Object> buildSummary(
            List<Map<String, Object>> events
    ) {
        Map<String, Object> summary = new LinkedHashMap<>();

        if (events.isEmpty()) {
            summary.put("totalMinutes",       0L);
            summary.put("distribution",       Map.of());
            summary.put("currentRegime",      null);
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

            // Para o último evento, calcula o tempo decorrido desde o início do regime atual
            long minutes = isLast
                    ? calcOngoingMinutesFromMarket(event)
                    : toLong(event.get("durationMinutes"));

            minutesByRegime.merge(regime, minutes, Long::sum);
            totalMinutes += minutes;
        }

        // Distribuição ordenada do regime mais frequente para o menos frequente
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

        Map<String, Object> lastEvent = events.get(events.size() - 1);
        String currentRegime      = (String) lastEvent.get("currentRegime");
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
     * Atualiza o campo {@code durationMinutes} do último evento da lista
     * com base no timestamp de mercado do início do próximo regime.
     *
     * Quando a lista está vazia ou o timestamp anterior for inválido, retorna sem modificar.
     * {@code Math.max(0, minutes)} garante duração não-negativa para casos de sobreposição
     * de janelas durante o warm-up histórico.
     *
     * @param events    lista de eventos existentes; o último será atualizado
     * @param nextStart timestamp de mercado do início do novo regime
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
        long    minutes   = Duration.between(prevStart, nextStart).toMinutes();

        last.put("durationMinutes", Math.max(0L, minutes));
    }

    // ═══════════════════════════════════════════════════════════════
    // Conversão para Map JSON
    // ═══════════════════════════════════════════════════════════════

    /**
     * Converte um {@link RegimeChangeEvent} para mapa para serialização JSON.
     *
     * Campos de tempo incluídos:
     * <ul>
     *   <li>{@code marketTimestamp}: tempo real de mercado em UTC</li>
     *   <li>{@code marketTimestampBrasilia}: mesmo instante no fuso de Brasília</li>
     *   <li>{@code dateBrasilia}: data local no fuso de Brasília</li>
     *   <li>{@code processingTimestamp}: momento em que o sistema processou (diagnóstico)</li>
     * </ul>
     * O campo {@code durationMinutes} é inicializado como {@code null} e preenchido
     * pelo próximo evento via {@link #updatePreviousRegimeDuration}.
     *
     * @param event evento de mudança de regime a ser serializado
     * @return mapa com todos os campos do evento pronto para serialização JSON
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

        map.put("symbol",          event.symbol());
        map.put("previousRegime",  event.previousRegime().name());
        map.put("currentRegime",   event.currentRegime().name());
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
     * Carrega a lista de eventos do campo {@code events} do arquivo JSON existente.
     * Retorna lista vazia quando o arquivo não existir ou o campo não estiver presente.
     *
     * @param reportFile caminho do arquivo de relatório
     * @return lista mutável com os eventos existentes, pronta para receber novos itens
     * @throws IOException se houver falha na leitura do arquivo
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
     * Calcula os minutos decorridos desde o {@code marketTimestamp} do evento
     * até o momento atual, representando o tempo em que o regime corrente está ativo.
     * Retorna {@code 0} quando o timestamp for nulo ou inválido.
     *
     * @param event mapa de dados do último evento de regime
     * @return minutos decorridos ou {@code 0} em caso de dado inválido
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
     * Formata o timestamp de Brasília como {@code HH:mm} para o campo
     * {@code currentRegimeSince} do resumo.
     * Retorna {@code "--:--"} quando o valor for nulo ou inválido.
     *
     * @param timestampBrasilia string ISO com offset de Brasília
     * @return horário formatado como {@code HH:mm} ou {@code "--:--"}
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

    /**
     * Converte um valor de {@code Object} para {@code long}.
     * Retorna {@code 0} quando o valor for nulo ou não for {@link Number}.
     *
     * @param value valor a converter
     * @return valor como {@code long} ou {@code 0}
     */
    private long toLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number n) return n.longValue();
        return 0L;
    }

    /**
     * Resolve o caminho do arquivo de relatório de regime para o símbolo e data informados,
     * dentro do subdiretório correspondente à data.
     *
     * @param symbol  símbolo do ativo
     * @param date    data do relatório
     * @param dateDir diretório de saída correspondente à data
     * @return caminho completo do arquivo {@code regime_report_{symbol}_{date}.json}
     */
    private Path resolveReportPath(String symbol, LocalDate date, Path dateDir) {
        return dateDir.resolve(
                "regime_report_" + symbol + "_" + date + ".json");
    }
}