package com.github.dayviddouglas.TradingBot.tools.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dayviddouglas.TradingBot.deriv.DerivWsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Ferramenta standalone para download em massa de histórico de candles da API Deriv.
 *
 * Conecta ao endpoint público da Deriv sem necessidade de OTP ou autenticação,
 * pois acessa apenas dados históricos públicos via {@code ticks_history}.
 * Para cada símbolo configurado em {@link #SYMBOLS}, executa paginação automática
 * retroativa até cobrir o período definido em {@link #DAYS_BACK} ou atingir o
 * limite de {@link #MAX_PAGES_PER_SYMBOL} páginas.
 *
 * Após o download, os candles são deduplicados por epoch, ordenados cronologicamente
 * e persistidos via {@link HistoryFileStore} com escrita atômica.
 *
 * A ferramenta é executada diretamente via {@link #main(String[])}, independente
 * do contexto Spring, sem necessidade de OTP ou beans injetados.
 */
public class DerivHistoryDownloadTool {

    private static final Logger log =
            LoggerFactory.getLogger(DerivHistoryDownloadTool.class);

    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Endpoint público da Deriv para dados de mercado históricos.
     * Não requer OTP — utilizado exclusivamente para dados históricos públicos.
     */
    private static final String ENDPOINT =
            "wss://api.derivws.com/trading/v1/options/ws/public";

    /** Lista de símbolos que serão baixados em sequência. */
    private static final List<String> SYMBOLS = List.of(
            "frxXAUUSD", "frxXPDUSD", "frxXPTUSD", "frxXAGUSD",
            "cryBTCUSD", "cryETHUSD",
            "frxAUDCAD", "frxAUDCHF", "frxAUDJPY", "frxAUDNZD", "frxAUDUSD",
            "frxEURAUD", "frxEURCAD", "frxEURCHF", "frxEURGBP", "frxEURJPY",
            "frxEURNZD", "frxEURUSD",
            "frxGBPAUD", "frxGBPCAD", "frxGBPCHF", "frxGBPJPY", "frxGBPNOK",
            "frxGBPNZD", "frxGBPUSD",
            "frxNZDJPY", "frxNZDUSD",
            "frxUSDCAD", "frxUSDCHF", "frxUSDJPY", "frxUSDMXN",
            "frxUSDNOK", "frxUSDPLN", "frxUSDSEK"
    );

    /** Granularidade dos candles em segundos. */
    private static final int GRANULARITY_SECONDS  = 300;

    /** Quantidade de dias retroativos a cobrir no download. */
    private static final int DAYS_BACK            = 90;

    /** Quantidade máxima de candles por requisição à API. */
    private static final int COUNT_PER_REQUEST    = 1000;

    /** Timeout máximo em segundos aguardado por resposta de cada página. */
    private static final int TIMEOUT_SECONDS      = 60;

    /** Limite de páginas por símbolo para evitar loop infinito. */
    private static final int MAX_PAGES_PER_SYMBOL = 5000;

    /** Tipos de mensagem aceitos como resposta válida de histórico de candles. */
    private static final Set<String> ACCEPTED_MSG_TYPES =
            Set.of("history", "ticks_history", "candles");

    /**
     * Ponto de entrada da ferramenta. Itera sobre todos os símbolos configurados
     * e realiza o download sequencial. Erros em um símbolo não interrompem os demais.
     */
    public static void main(String[] args) throws Exception {
        HistoryFileStore store = new HistoryFileStore();

        log.info("Starting history download | symbols={} | granularity={} | daysBack={}",
                SYMBOLS.size(), GRANULARITY_SECONDS, DAYS_BACK);

        for (String symbol : SYMBOLS) {
            try {
                downloadOneSymbol(store, symbol, GRANULARITY_SECONDS, DAYS_BACK);
            } catch (Exception e) {
                log.error("FAILED | symbol={} | granularity={} | daysBack={}",
                        symbol, GRANULARITY_SECONDS, DAYS_BACK, e);
            }
        }

        log.info("History download finished for all symbols.");
    }

    /**
     * Realiza o download paginado do histórico de candles para um único símbolo.
     * A paginação avança retroativamente: cada página solicita candles com
     * {@code end = oldestEpochDaPáginaAnterior - 1} até cobrir o período alvo
     * ou receber resposta vazia da API.
     *
     * Cada mensagem recebida é filtrada pelo {@code echo_req} para garantir que
     * pertence ao símbolo e granularidade corretos. A deduplicação por epoch
     * evita candles duplicados nas páginas de borda.
     *
     * @param store             serviço de persistência do histórico
     * @param symbol            símbolo do ativo a ser baixado
     * @param granularitySeconds granularidade dos candles em segundos
     * @param daysBack          quantidade de dias retroativos a cobrir
     * @throws Exception em caso de timeout, resposta vazia ou limite de páginas atingido
     */
    private static void downloadOneSymbol(
            HistoryFileStore store,
            String symbol,
            int granularitySeconds,
            int daysBack
    ) throws Exception {

        long endEpoch         = Instant.now().getEpochSecond();
        long startEpochTarget = Instant.now()
                .minus(daysBack, ChronoUnit.DAYS)
                .getEpochSecond();

        ArrayNode  allCandles = mapper.createArrayNode();
        Set<Long>  seenEpochs = new HashSet<>();

        log.info("Downloading | symbol={} | granularity={} | daysBack={}",
                symbol, granularitySeconds, daysBack);

        try (DerivWsClient ws = new DerivWsClient(new URI(ENDPOINT))) {
            ws.connectBlocking(10, TimeUnit.SECONDS);

            long currentEnd = endEpoch;
            int  page       = 0;

            while (true) {
                page++;
                final int pageNo = page;

                CountDownLatch     latch         = new CountDownLatch(1);
                final ObjectNode[] pageMsgHolder = new ObjectNode[1];

                ws.setMessageHandler(raw -> {
                    try {
                        JsonNode msg = mapper.readTree(raw);

                        if (msg.has("error")) {
                            log.error("Deriv error | symbol={} | page={} | {}",
                                    symbol, pageNo, msg);
                            latch.countDown();
                            return;
                        }

                        String msgType = msg.path("msg_type").asText("");
                        if (!ACCEPTED_MSG_TYPES.contains(msgType)) return;

                        // Filtra a resposta pelo echo_req para garantir correspondência
                        // com o símbolo, granularidade e req_id desta página
                        JsonNode echo = msg.get("echo_req");
                        if (echo != null && echo.isObject()) {
                            String echoSymbol = echo.path("ticks_history").asText("");
                            int    echoGran   = echo.path("granularity").asInt(-1);

                            if (!echoSymbol.isBlank()
                                    && (!symbol.equals(echoSymbol)
                                    || granularitySeconds != echoGran)) {
                                return;
                            }

                            int echoReqId = echo.path("req_id").asInt(-1);
                            if (echoReqId != -1 && echoReqId != pageNo) {
                                return;
                            }
                        }

                        if (msg.isObject()) {
                            pageMsgHolder[0] = (ObjectNode) msg;
                        }
                        latch.countDown();

                    } catch (Exception e) {
                        log.error("Error processing message | symbol={} | page={}",
                                symbol, pageNo, e);
                        latch.countDown();
                    }
                });

                ObjectNode req = mapper.createObjectNode();
                req.put("ticks_history",     symbol);
                req.put("style",             "candles");
                req.put("granularity",       granularitySeconds);
                req.put("count",             COUNT_PER_REQUEST);
                req.put("end",               currentEnd);
                req.put("adjust_start_time", 1);
                req.put("req_id",            pageNo);

                ws.send(req.toString());

                if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "Timeout page=" + pageNo + " symbol=" + symbol);
                }

                ObjectNode pageMsg = pageMsgHolder[0];
                if (pageMsg == null) {
                    throw new IllegalStateException(
                            "Empty response page=" + pageNo + " symbol=" + symbol);
                }

                JsonNode candlesNode = pageMsg.get("candles");
                if (candlesNode == null
                        || !candlesNode.isArray()
                        || candlesNode.isEmpty()) {
                    break;
                }

                long oldestEpochInPage = Long.MAX_VALUE;

                for (JsonNode c : candlesNode) {
                    long epoch = c.path("epoch").asLong(-1);
                    if (epoch <= 0) continue;

                    // Deduplicação: evita candles repetidos nas páginas de borda
                    if (seenEpochs.add(epoch)) {
                        allCandles.add(c);
                    }

                    if (epoch < oldestEpochInPage) {
                        oldestEpochInPage = epoch;
                    }
                }

                if (oldestEpochInPage == Long.MAX_VALUE) break;

                // Período alvo coberto — encerra a paginação
                if (oldestEpochInPage <= startEpochTarget) break;

                long nextEnd = oldestEpochInPage - 1;
                if (nextEnd >= currentEnd) break;
                currentEnd = nextEnd;

                if (pageNo >= MAX_PAGES_PER_SYMBOL) {
                    throw new IllegalStateException(
                            "Max pages reached symbol=" + symbol);
                }

                if (pageNo % 10 == 0) {
                    log.info("Progress | symbol={} | pages={} | candles={}",
                            symbol, pageNo, allCandles.size());
                }
            }

            sortCandlesByEpoch(allCandles);

            ObjectNode out = mapper.createObjectNode();
            out.put("symbol",             symbol);
            out.put("granularitySeconds", granularitySeconds);
            out.put("daysBackRequested",  daysBack);
            out.put("generatedAtEpoch",   Instant.now().getEpochSecond());
            out.put("candlesCount",       allCandles.size());
            out.set("candles",            allCandles);

            store.write(symbol, granularitySeconds, out);

            log.info("History saved | symbol={} | candles={} | path={}",
                    symbol, allCandles.size(),
                    store.pathFor(symbol, granularitySeconds).toAbsolutePath());
        }
    }

    /**
     * Ordena os candles do array JSON pelo campo {@code epoch} em ordem crescente.
     * Necessário porque a paginação retroativa recebe as páginas do presente para o passado,
     * e os candles precisam estar em ordem cronológica para uso nas estratégias.
     *
     * @param candles array de candles JSON a ser ordenado in-place
     */
    private static void sortCandlesByEpoch(ArrayNode candles) {
        if (candles == null || candles.size() <= 1) return;

        List<JsonNode> list = new ArrayList<>(candles.size());
        candles.forEach(list::add);

        list.sort(Comparator.comparingLong(n -> n.path("epoch").asLong(0)));

        candles.removeAll();
        for (JsonNode n : list) candles.add(n);
    }
}