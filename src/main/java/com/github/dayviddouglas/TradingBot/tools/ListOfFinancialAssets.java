package com.github.dayviddouglas.TradingBot.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dayviddouglas.TradingBot.deriv.DerivWsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Ferramenta standalone para mapear todos os ativos e contratos disponíveis na plataforma Deriv.
 *
 * Conecta ao endpoint público da Deriv sem necessidade de OTP, solicita a lista de símbolos ativos
 * via {@code active_symbols} e em seguida solicita {@code contracts_for} para cada símbolo,
 * coletando os tipos de contrato disponíveis e suas características.
 *
 * O resultado é persistido em {@code data/active_symbols.full.json} com:
 * <ul>
 *   <li>Dados de cada ativo: símbolo, mercado, submercado, contratos disponíveis e suas famílias</li>
 *   <li>Frequência de cada tipo de contrato e família de trade entre todos os ativos</li>
 *   <li>Lista de símbolos sem contratos disponíveis ou com timeout</li>
 * </ul>
 *
 * A ferramenta é executada diretamente via {@link #main(String[])}, independente do contexto Spring.
 * Não é parte do fluxo operacional do bot — destina-se ao uso manual para exploração da API.
 */
public class ListOfFinancialAssets {

    private static final Logger log =
            LoggerFactory.getLogger(ListOfFinancialAssets.class);

    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Endpoint público da Deriv para dados de mercado.
     * Não requer OTP — utilizado apenas para dados públicos.
     */
    private static final String ENDPOINT =
            "wss://api.derivws.com/trading/v1/options/ws/public";

    /**
     * Ponto de entrada da ferramenta.
     * Solicita os símbolos ativos, coleta contratos de cada um com controle de concorrência
     * via {@link CountDownLatch} e {@link ConcurrentHashMap}, e persiste o relatório final.
     *
     * @throws Exception em caso de falha na conexão WebSocket ou na escrita do relatório
     */
    public static void main(String[] args) throws Exception {

        Path outFile = Path.of("data", "active_symbols.full.json");

        CountDownLatch          activeSymbolsLatch    = new CountDownLatch(1);
        Map<String, ObjectNode> assetsBySymbol        = new ConcurrentHashMap<>();
        Map<Integer, String>    reqIdToSymbol         = new ConcurrentHashMap<>();
        Set<String>             pendingContracts      = ConcurrentHashMap.newKeySet();
        Set<String>             unsupportedSymbols    = ConcurrentHashMap.newKeySet();
        Map<String, Integer>    contractTypeFrequency = new ConcurrentHashMap<>();
        Map<String, Integer>    tradeFamilyFrequency  = new ConcurrentHashMap<>();

        try (DerivWsClient ws = new DerivWsClient(new URI(ENDPOINT))) {

            ws.setMessageHandler(raw -> {
                log.info("RAW MESSAGE RECEIVED: {}", raw);
                try {
                    JsonNode msg     = mapper.readTree(raw);
                    String   msgType = msg.path("msg_type").asText("");

                    if (msg.has("error")) {
                        int    reqId  = msg.path("echo_req").path("req_id").asInt(-1);
                        String symbol = reqId != -1 ? reqIdToSymbol.remove(reqId) : null;

                        if ("contracts_for".equals(msgType) && symbol != null) {
                            // Contrato não disponível para este símbolo — registra como sem suporte
                            unsupportedSymbols.add(symbol);
                            pendingContracts.remove(symbol);

                            ObjectNode assetNode = assetsBySymbol.get(symbol);
                            if (assetNode != null) {
                                assetNode.put("contractsAvailable",   false);
                                assetNode.set("contractTypes",        mapper.createArrayNode());
                                assetNode.set("tradeFamilies",        mapper.createObjectNode());
                                assetNode.set("contractsReport",      mapper.createArrayNode());
                                assetNode.put("contractsErrorCode",
                                        msg.path("error").path("code").asText(""));
                                assetNode.put("contractsErrorMessage",
                                        msg.path("error").path("message").asText(""));
                            }

                            log.warn("No contracts | symbol={} | code={}",
                                    symbol, msg.path("error").path("code").asText(""));
                            return;
                        }

                        log.error("Deriv error: {}", msg.toPrettyString());

                        if (activeSymbolsLatch.getCount() > 0) {
                            activeSymbolsLatch.countDown();
                        }
                        return;
                    }

                    if ("active_symbols".equals(msgType)) {
                        JsonNode arr = msg.get("active_symbols");
                        if (arr != null && arr.isArray()) {
                            int reqId = 1;

                            for (JsonNode o : arr) {
                                String symbol = o.path("underlying_symbol").asText("");
                                if (symbol.isBlank()) continue;

                                // Constrói o nó do ativo com os campos retornados pela API
                                ObjectNode item = mapper.createObjectNode();
                                item.put("symbol",           symbol);
                                item.put("name",             o.path("underlying_symbol_name").asText(""));
                                item.put("market",           o.path("market").asText(""));
                                item.put("submarket",        o.path("submarket").asText(""));
                                item.put("marketDisplay",    o.path("market").asText(""));
                                item.put("submarketDisplay", o.path("submarket").asText(""));
                                item.put("open",             o.path("exchange_is_open").asInt(0) == 1);
                                item.put("suspended",        o.path("is_trading_suspended").asInt(0) == 1);
                                item.put("pip",              o.path("pip_size").asDouble(0.0));
                                item.put("symbolType",       o.path("underlying_symbol_type").asText(""));
                                item.put("isTradingSuspended",
                                        o.path("is_trading_suspended").asInt(0) == 1);
                                item.put("contractsAvailable", false);
                                item.set("contractTypes",   mapper.createArrayNode());
                                item.set("tradeFamilies",   mapper.createObjectNode());
                                item.set("contractsReport", mapper.createArrayNode());

                                assetsBySymbol.put(symbol, item);

                                // Enfileira a requisição de contratos para este símbolo
                                ObjectNode contractsPayload = mapper.createObjectNode();
                                contractsPayload.put("contracts_for", symbol);
                                contractsPayload.put("req_id",        reqId);

                                reqIdToSymbol.put(reqId, symbol);
                                pendingContracts.add(symbol);

                                ws.send(contractsPayload.toString());
                                reqId++;
                            }
                        }

                        activeSymbolsLatch.countDown();
                        return;
                    }

                    if ("contracts_for".equals(msgType)) {
                        int    reqId  = msg.path("echo_req").path("req_id").asInt(-1);
                        String symbol = reqIdToSymbol.remove(reqId);

                        if (symbol == null) return;

                        ObjectNode assetNode = assetsBySymbol.get(symbol);
                        if (assetNode == null) {
                            pendingContracts.remove(symbol);
                            return;
                        }

                        JsonNode contractsFor = msg.path("contracts_for");
                        JsonNode available    = contractsFor.path("available");

                        Set<String>              uniqueContractTypes   = new TreeSet<>();
                        Map<String, Set<String>> familyToContractTypes = new TreeMap<>();
                        ArrayNode                contractsReport       = mapper.createArrayNode();

                        if (available.isArray()) {
                            for (JsonNode contract : available) {
                                String contractType =
                                        contract.path("contract_type").asText("");
                                if (contractType.isBlank()) continue;

                                uniqueContractTypes.add(contractType);
                                contractTypeFrequency.merge(contractType, 1, Integer::sum);

                                String family = classifyTradeFamily(contractType);
                                tradeFamilyFrequency.merge(family, 1, Integer::sum);
                                familyToContractTypes
                                        .computeIfAbsent(family, k -> new TreeSet<>())
                                        .add(contractType);

                                // Constrói o nó de detalhes do contrato
                                ObjectNode contractNode = mapper.createObjectNode();
                                contractNode.put("contractType",     contractType);
                                contractNode.put("tradeFamily",      family);
                                contractNode.put("contractCategory",
                                        contract.path("contract_category").asText(""));
                                contractNode.put("contractDisplay",
                                        contract.path("contract_display").asText(""));
                                contractNode.put("exchangeName",
                                        contract.path("exchange_name").asText(""));
                                contractNode.put("market",
                                        contract.path("market").asText(""));
                                contractNode.put("submarket",
                                        contract.path("submarket").asText(""));
                                contractNode.put("sentiment",
                                        contract.path("sentiment").asText(""));
                                contractNode.put("barrierCategory",
                                        contract.path("barrier_category").asText(""));
                                contractNode.put("startType",
                                        contract.path("start_type").asText(""));
                                contractNode.put("expiryType",
                                        contract.path("expiry_type").asText(""));

                                JsonNode minDur = contract.get("min_contract_duration");
                                JsonNode maxDur = contract.get("max_contract_duration");
                                String   minRaw = minDur != null ? minDur.asText("") : "";
                                String   maxRaw = maxDur != null ? maxDur.asText("") : "";

                                contractNode.put("minContractDuration", minRaw);
                                contractNode.put("maxContractDuration", maxRaw);

                                // Bloco de duração enriquecido com conversão e formatação
                                ObjectNode durationInfo = mapper.createObjectNode();
                                durationInfo.put("minRaw",    minRaw);
                                durationInfo.put("maxRaw",    maxRaw);
                                durationInfo.put("minSeconds", parseDurationToSeconds(minRaw));
                                durationInfo.put("maxSeconds", parseDurationToSeconds(maxRaw));
                                durationInfo.put("minPretty",  prettyDuration(minRaw));
                                durationInfo.put("maxPretty",  prettyDuration(maxRaw));
                                contractNode.set("duration", durationInfo);

                                contractsReport.add(contractNode);
                            }
                        }

                        ArrayNode contractTypes = mapper.createArrayNode();
                        for (String type : uniqueContractTypes) {
                            contractTypes.add(type);
                        }

                        ObjectNode tradeFamiliesNode = mapper.createObjectNode();
                        for (Map.Entry<String, Set<String>> entry :
                                familyToContractTypes.entrySet()) {
                            ArrayNode familyTypes = mapper.createArrayNode();
                            for (String type : entry.getValue()) {
                                familyTypes.add(type);
                            }
                            tradeFamiliesNode.set(entry.getKey(), familyTypes);
                        }

                        assetNode.put("contractsAvailable",
                                !uniqueContractTypes.isEmpty());
                        assetNode.set("contractTypes",   contractTypes);
                        assetNode.set("tradeFamilies",   tradeFamiliesNode);
                        assetNode.set("contractsReport", contractsReport);

                        pendingContracts.remove(symbol);
                    }

                } catch (Exception e) {
                    log.error("Failed to process message", e);
                }
            });

            ws.connectBlocking(10, TimeUnit.SECONDS);

            ObjectNode payload = mapper.createObjectNode();
            payload.put("active_symbols", "brief");

            log.info("Sending active_symbols request...");
            ws.send(payload.toString());

            if (!activeSymbolsLatch.await(15, TimeUnit.SECONDS)) {
                log.warn("Timeout waiting for active_symbols response");
            }

            // Aguarda até todas as respostas de contracts_for chegarem ou o timeout expirar
            long waitStart = System.currentTimeMillis();
            long maxWaitMs = 120_000;

            while (!pendingContracts.isEmpty()
                    && (System.currentTimeMillis() - waitStart) < maxWaitMs) {
                Thread.sleep(200);
            }

            // Marca os símbolos que não responderam dentro do timeout
            if (!pendingContracts.isEmpty()) {
                log.warn("Timeout waiting contracts_for for {} symbols",
                        pendingContracts.size());
                for (String symbol : pendingContracts) {
                    unsupportedSymbols.add(symbol);
                    ObjectNode assetNode = assetsBySymbol.get(symbol);
                    if (assetNode != null) {
                        assetNode.put("contractsAvailable",   false);
                        assetNode.set("contractTypes",        mapper.createArrayNode());
                        assetNode.set("tradeFamilies",        mapper.createObjectNode());
                        assetNode.set("contractsReport",      mapper.createArrayNode());
                        assetNode.put("contractsErrorCode",   "TIMEOUT");
                        assetNode.put("contractsErrorMessage",
                                "Timeout waiting for contracts_for response");
                    }
                }
            }

            JsonNode report = buildFinalJson(
                    assetsBySymbol, unsupportedSymbols,
                    contractTypeFrequency, tradeFamilyFrequency);

            String pretty = mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(report);

            writeTextAtomic(outFile, pretty);
            log.info("Saved report to {}", outFile.toAbsolutePath());

            printSummary(assetsBySymbol, unsupportedSymbols,
                    contractTypeFrequency, tradeFamilyFrequency);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Relatório final
    // ═══════════════════════════════════════════════════════════════

    /**
     * Constrói o JSON final do relatório consolidando todos os ativos,
     * símbolos sem suporte e frequências de contratos e famílias de trade.
     *
     * @param assetsBySymbol        mapa de dados de cada ativo indexado pelo símbolo
     * @param unsupportedSymbols    símbolos sem contratos disponíveis ou com timeout
     * @param contractTypeFrequency frequência de cada tipo de contrato entre todos os ativos
     * @param tradeFamilyFrequency  frequência de cada família de trade entre todos os ativos
     * @return nó JSON raiz do relatório consolidado
     */
    private static JsonNode buildFinalJson(
            Map<String, ObjectNode> assetsBySymbol,
            Set<String> unsupportedSymbols,
            Map<String, Integer> contractTypeFrequency,
            Map<String, Integer> tradeFamilyFrequency
    ) {
        ObjectNode root            = mapper.createObjectNode();
        ArrayNode  assetsArr       = mapper.createArrayNode();
        ArrayNode  unsupportedArr  = mapper.createArrayNode();
        ObjectNode contractSummary = mapper.createObjectNode();
        ObjectNode familySummary   = mapper.createObjectNode();

        int withContracts    = 0;
        int withoutContracts = 0;

        List<ObjectNode> sortedAssets = new ArrayList<>(assetsBySymbol.values());
        sortedAssets.sort(Comparator.comparing(a -> a.path("symbol").asText("")));

        for (ObjectNode asset : sortedAssets) {
            assetsArr.add(asset);
            if (asset.path("contractsAvailable").asBoolean(false)) {
                withContracts++;
            } else {
                withoutContracts++;
            }
        }

        unsupportedSymbols.stream().sorted().forEach(unsupportedArr::add);

        contractTypeFrequency.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> contractSummary.put(e.getKey(), e.getValue()));

        tradeFamilyFrequency.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> familySummary.put(e.getKey(), e.getValue()));

        root.put("count",                 assetsArr.size());
        root.put("withContracts",         withContracts);
        root.put("withoutContracts",      withoutContracts);
        root.set("unsupportedSymbols",    unsupportedArr);
        root.set("contractTypeFrequency", contractSummary);
        root.set("tradeFamilyFrequency",  familySummary);
        root.set("assets",                assetsArr);

        return root;
    }

    // ═══════════════════════════════════════════════════════════════
    // Classificação de família de trade
    // ═══════════════════════════════════════════════════════════════

    /**
     * Classifica um tipo de contrato em sua família de trade correspondente.
     *
     * @param contractType tipo de contrato retornado pela API Deriv
     * @return nome da família de trade ({@code rise_fall}, {@code digits}, etc.)
     */
    private static String classifyTradeFamily(String contractType) {
        return switch (contractType) {
            case "CALL", "PUT"                            -> "rise_fall";
            case "CALLE", "PUTE"                         -> "higher_lower";
            case "ONETOUCH", "NOTOUCH"                   -> "touch_no_touch";
            case "DIGITDIFF", "DIGITEVEN", "DIGITODD",
                 "DIGITMATCH", "DIGITOVER", "DIGITUNDER" -> "digits";
            case "MULTUP", "MULTDOWN"                    -> "multipliers";
            case "ASIANU", "ASIAND"                      -> "asian";
            case "EXPIRYRANGE", "UPORDOWN"               -> "range";
            default                                      -> "other";
        };
    }

    // ═══════════════════════════════════════════════════════════════
    // Utilitários de duração
    // ═══════════════════════════════════════════════════════════════

    /**
     * Converte a string de duração da API Deriv para o equivalente em segundos.
     * Sufixos suportados: {@code s} (segundos), {@code m} (minutos),
     * {@code h} (horas), {@code d} (dias). Retorna {@code -1} para valores inválidos.
     *
     * @param raw string de duração no formato da API (ex: {@code "15m"}, {@code "1d"})
     * @return duração em segundos ou {@code -1} se não conversível
     */
    private static long parseDurationToSeconds(String raw) {
        if (raw == null || raw.isBlank()) return -1;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        try {
            if (value.endsWith("t")) return -1;
            if (value.endsWith("s")) return Long.parseLong(value.substring(0, value.length() - 1));
            if (value.endsWith("m")) return Long.parseLong(value.substring(0, value.length() - 1)) * 60;
            if (value.endsWith("h")) return Long.parseLong(value.substring(0, value.length() - 1)) * 3600;
            if (value.endsWith("d")) return Long.parseLong(value.substring(0, value.length() - 1)) * 86400;
        } catch (NumberFormatException ignored) {}
        return -1;
    }

    /**
     * Formata a string de duração em texto legível (ex: {@code "15 minute(s)"}).
     * Retorna a string original quando não for conversível.
     *
     * @param raw string de duração no formato da API
     * @return string formatada em unidade legível
     */
    private static String prettyDuration(String raw) {
        long seconds = parseDurationToSeconds(raw);
        if (seconds < 0)             return raw == null ? "" : raw;
        if (seconds % 86400 == 0)    return (seconds / 86400) + " day(s)";
        if (seconds % 3600  == 0)    return (seconds / 3600)  + " hour(s)";
        if (seconds % 60    == 0)    return (seconds / 60)    + " minute(s)";
        return seconds + " second(s)";
    }

    // ═══════════════════════════════════════════════════════════════
    // Resumo no console
    // ═══════════════════════════════════════════════════════════════

    /**
     * Imprime no console um resumo estruturado com contagens, símbolos sem suporte,
     * frequências de tipos de contrato, famílias de trade e visão geral por ativo.
     */
    private static void printSummary(
            Map<String, ObjectNode> assetsBySymbol,
            Set<String> unsupportedSymbols,
            Map<String, Integer> contractTypeFrequency,
            Map<String, Integer> tradeFamilyFrequency
    ) {
        long withContracts = assetsBySymbol.values().stream()
                .filter(a -> a.path("contractsAvailable").asBoolean(false))
                .count();

        System.out.println();
        System.out.println("==================================================");
        System.out.println("ACTIVE SYMBOLS FULL REPORT SUMMARY");
        System.out.println("==================================================");
        System.out.println("Total assets      : " + assetsBySymbol.size());
        System.out.println("With contracts    : " + withContracts);
        System.out.println("Without contracts : " + (assetsBySymbol.size() - withContracts));
        System.out.println();

        System.out.println("UNSUPPORTED SYMBOLS");
        System.out.println("--------------------------------------------------");
        if (unsupportedSymbols.isEmpty()) {
            System.out.println("(none)");
        } else {
            unsupportedSymbols.stream().sorted()
                    .forEach(s -> System.out.println("- " + s));
        }
        System.out.println();

        System.out.println("CONTRACT TYPE FREQUENCY");
        System.out.println("--------------------------------------------------");
        contractTypeFrequency.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> System.out.println("- " + e.getKey() + ": " + e.getValue()));
        System.out.println();

        System.out.println("TRADE FAMILY FREQUENCY");
        System.out.println("--------------------------------------------------");
        tradeFamilyFrequency.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> System.out.println("- " + e.getKey() + ": " + e.getValue()));
        System.out.println();

        System.out.println("ASSETS OVERVIEW");
        System.out.println("--------------------------------------------------");
        assetsBySymbol.values().stream()
                .sorted(Comparator.comparing(a -> a.path("symbol").asText("")))
                .forEach(asset -> {
                    String      symbol = asset.path("symbol").asText("");
                    boolean     ok     = asset.path("contractsAvailable").asBoolean(false);
                    List<String> types = new ArrayList<>();
                    JsonNode     arr   = asset.path("contractTypes");
                    if (arr.isArray()) arr.forEach(t -> types.add(t.asText("")));
                    System.out.println(symbol
                            + " | contractsAvailable=" + ok
                            + " | contractTypes=" + types);
                });

        System.out.println("==================================================");
    }

    // ═══════════════════════════════════════════════════════════════
    // Escrita atômica
    // ═══════════════════════════════════════════════════════════════

    /**
     * Persiste o texto no arquivo de destino usando escrita atômica.
     * Escreve em arquivo temporário e renomeia para o destino,
     * evitando estados parcialmente escritos em caso de falha.
     *
     * @param outFile caminho do arquivo de destino
     * @param text    conteúdo textual a ser persistido em UTF-8
     * @throws IOException se ocorrer falha na escrita ou renomeação
     */
    private static void writeTextAtomic(Path outFile, String text)
            throws IOException {
        Files.createDirectories(outFile.getParent());
        Path tmp = outFile.resolveSibling(outFile.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(tmp, outFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, outFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}