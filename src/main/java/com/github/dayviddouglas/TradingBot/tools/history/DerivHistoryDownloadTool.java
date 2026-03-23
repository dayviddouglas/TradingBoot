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

public class DerivHistoryDownloadTool {
    private static final Logger log = LoggerFactory.getLogger(DerivHistoryDownloadTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    // ====== CONFIG FIXA (edite aqui) ======
    private static final int APP_ID = 1089;

    // NEW: lista de ativos (um por vez, sequencial)
    private static final List<String> SYMBOLS = List.of(
            "frxXAUUSD",
            "frxXPDUSD",
            "frxXPTUSD",
            "frxXAGUSD",
            "cryBTCUSD",
            "cryETHUSD",
            "frxAUDCAD",
            "frxAUDCHF",
            "frxAUDJPY",
            "frxAUDNZD",
            "frxAUDUSD",
            "frxEURAUD",
            "frxEURCAD",
            "frxEURCHF",
            "frxEURGBP",
            "frxEURJPY",
            "frxEURNZD",
            "frxEURUSD",
            "frxGBPAUD",
            "frxGBPCAD",
            "frxGBPCHF",
            "frxGBPJPY",
            "frxGBPNOK",
            "frxGBPNZD",
            "frxGBPUSD",
            "frxNZDJPY",
            "frxNZDUSD",
            "frxUSDCAD",
            "frxUSDCHF",
            "frxUSDJPY",
            "frxUSDMXN",
            "frxUSDNOK",
            "frxUSDPLN",
            "frxUSDSEK",
            "OTC_AS51",
            "OTC_SX5E",
            "OTC_FCHI",
            "OTC_GDAXI",
            "OTC_HSI",
            "OTC_N225",
            "OTC_AEX",
            "OTC_SSMI",
            "OTC_FTSE",
            "OTC_SPC",
            "OTC_NDX",
            "OTC_DJI",
            "WLDAUD",
            "RDBEAR",
            "BOOM1000",
            "BOOM300N",
            "BOOM500",
            "BOOM600",
            "BOOM900",
            "RDBULL",
            "CRASH1000",
            "CRASH300N",
            "CRASH500",
            "CRASH600",
            "CRASH900",
            "WLDEUR",
            "WLDGBP",
            "WLDXAU",
            "JD10",
            "JD100",
            "JD25",
            "JD50",
            "JD75",
            "RB100",
            "RB200",
            "stpRNG",
            "stpRNG2",
            "stpRNG3",
            "stpRNG4",
            "stpRNG5",
            "WLDUSD",
            "1HZ10V",
            "R_10",
            "1HZ100V",
            "R_100",
            "1HZ15V",
            "1HZ25V",
            "R_25",
            "1HZ30V",
            "1HZ50V",
            "R_50",
            "1HZ75V",
            "R_75",
            "1HZ90V"
    );

    private static final int GRANULARITY_SECONDS = 60; // 60=1m, 300=5m
    private static final int DAYS_BACK = 90;

    private static final int COUNT_PER_REQUEST = 1000;
    private static final int TIMEOUT_SECONDS = 60;

    // Segurança extra: limite de páginas por símbolo
    private static final int MAX_PAGES_PER_SYMBOL = 5000;
    // ======================================

    private static final Set<String> ACCEPTED_MSG_TYPES = Set.of("history", "ticks_history", "candles");

    public static void main(String[] args) throws Exception {
        String endpoint = "wss://ws.derivws.com/websockets/v3?app_id=" + APP_ID;

        HistoryFileStore store = new HistoryFileStore();

        log.info("Starting history download. symbols={} granularity={} daysBack={}",
                SYMBOLS.size(), GRANULARITY_SECONDS, DAYS_BACK);

        for (String symbol : SYMBOLS) {
            try {
                downloadOneSymbol(endpoint, store, symbol, GRANULARITY_SECONDS, DAYS_BACK);
            } catch (Exception e) {
                log.error("FAILED to download history | symbol={} granularity={} daysBack={}",
                        symbol, GRANULARITY_SECONDS, DAYS_BACK, e);
            }
        }

        log.info("History download finished for all symbols.");
    }

    private static void downloadOneSymbol(String endpoint,
                                          HistoryFileStore store,
                                          String symbol,
                                          int granularitySeconds,
                                          int daysBack) throws Exception {

        long endEpoch = Instant.now().getEpochSecond();
        long startEpochTarget = Instant.now().minus(daysBack, ChronoUnit.DAYS).getEpochSecond();

        // Buffer de saída por símbolo
        ArrayNode allCandles = mapper.createArrayNode();
        Set<Long> seenEpochs = new HashSet<>();

        log.info("Downloading history | symbol={} granularity={} daysBack={} ...",
                symbol, granularitySeconds, daysBack);

        // Opção mais robusta: 1 conexão por símbolo (evita “respostas cruzadas”/estado sujo)
        try (DerivWsClient ws = new DerivWsClient(new URI(endpoint))) {
            ws.connectBlocking(10, TimeUnit.SECONDS);

            long currentEnd = endEpoch;
            int page = 0;

            while (true) {
                page++;
                final int pageNo = page;

                CountDownLatch latch = new CountDownLatch(1);
                final ObjectNode[] pageMsgHolder = new ObjectNode[1];

                // Handler “one-shot” para esta página
                ws.setMessageHandler(raw -> {
                    try {
                        JsonNode msg = mapper.readTree(raw);

                        if (msg.has("error")) {
                            log.error("Deriv error (symbol={} page={}): {}", symbol, pageNo, msg.toString());
                            latch.countDown();
                            return;
                        }

                        String msgType = msg.path("msg_type").asText("");
                        if (!ACCEPTED_MSG_TYPES.contains(msgType)) return;

                        // Validação para garantir que a resposta é do símbolo/granularity esperado
                        JsonNode echo = msg.get("echo_req");
                        if (echo != null && echo.isObject()) {
                            String echoSymbol = echo.path("ticks_history").asText("");
                            int echoGran = echo.path("granularity").asInt(-1);

                            if (!echoSymbol.isBlank() && (!symbol.equals(echoSymbol) || granularitySeconds != echoGran)) {
                                return;
                            }

                            // também valida req_id se presente
                            int echoReqId = echo.path("req_id").asInt(-1);
                            if (echoReqId != -1 && echoReqId != pageNo) {
                                return;
                            }
                        }

                        if (msg.isObject()) pageMsgHolder[0] = (ObjectNode) msg;
                        latch.countDown();

                    } catch (Exception e) {
                        log.error("Falha ao processar mensagem (symbol={} page={}).", symbol, pageNo, e);
                        latch.countDown();
                    }
                });

                // Request de candles paginado
                ObjectNode req = mapper.createObjectNode();
                req.put("ticks_history", symbol);
                req.put("style", "candles");
                req.put("granularity", granularitySeconds);
                req.put("count", COUNT_PER_REQUEST);
                req.put("end", currentEnd);
                req.put("adjust_start_time", 1);
                req.put("req_id", pageNo);

                ws.send(req.toString());

                if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timeout aguardando página " + pageNo + " da Deriv para symbol=" + symbol);
                }

                ObjectNode pageMsg = pageMsgHolder[0];
                if (pageMsg == null) {
                    throw new IllegalStateException("Resposta vazia na página " + pageNo + " para symbol=" + symbol);
                }

                JsonNode candlesNode = pageMsg.get("candles");
                if (candlesNode == null || !candlesNode.isArray() || candlesNode.isEmpty()) {
                    // sem mais dados
                    break;
                }

                long oldestEpochInPage = Long.MAX_VALUE;

                for (JsonNode c : candlesNode) {
                    long epoch = c.path("epoch").asLong(-1);
                    if (epoch <= 0) continue;

                    if (seenEpochs.add(epoch)) {
                        allCandles.add(c);
                    }

                    if (epoch < oldestEpochInPage) oldestEpochInPage = epoch;
                }

                if (oldestEpochInPage == Long.MAX_VALUE) break;

                // Já atingimos a janela desejada?
                if (oldestEpochInPage <= startEpochTarget) {
                    break;
                }

                // Próxima página: end = candle mais antigo - 1
                long nextEnd = oldestEpochInPage - 1;
                if (nextEnd >= currentEnd) break;
                currentEnd = nextEnd;

                if (pageNo >= MAX_PAGES_PER_SYMBOL) {
                    throw new IllegalStateException("Paginação excedeu limite de páginas (" + MAX_PAGES_PER_SYMBOL + ") para symbol=" + symbol);
                }

                if (pageNo % 10 == 0) {
                    log.info("Progress | symbol={} pages={} candlesSoFar={}", symbol, pageNo, allCandles.size());
                }
            }

            // Ordena candles por epoch para garantir dataset consistente
            sortCandlesByEpoch(allCandles);

            ObjectNode out = mapper.createObjectNode();
            out.put("symbol", symbol);
            out.put("granularitySeconds", granularitySeconds);
            out.put("daysBackRequested", daysBack);
            out.put("generatedAtEpoch", Instant.now().getEpochSecond());
            out.put("candlesCount", allCandles.size());
            out.set("candles", allCandles);

            store.write(symbol, granularitySeconds, out);

            log.info("History saved | symbol={} candles={} path={}",
                    symbol, allCandles.size(), store.pathFor(symbol, granularitySeconds).toAbsolutePath());
        }
    }

    private static void sortCandlesByEpoch(ArrayNode candles) {
        if (candles == null || candles.size() <= 1) return;

        List<JsonNode> list = new ArrayList<>(candles.size());
        candles.forEach(list::add);

        list.sort(Comparator.comparingLong(n -> n.path("epoch").asLong(0)));

        candles.removeAll();
        for (JsonNode n : list) candles.add(n);
    }
}