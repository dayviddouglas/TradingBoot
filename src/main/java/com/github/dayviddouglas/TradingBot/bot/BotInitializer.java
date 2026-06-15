package com.github.dayviddouglas.TradingBot.bot;

import com.github.dayviddouglas.TradingBot.config.strategy.StrategiesProfile;
import com.github.dayviddouglas.TradingBot.config.strategy.TradeConfig;
import com.github.dayviddouglas.TradingBot.deriv.DerivMarketDataService;
import com.github.dayviddouglas.TradingBot.deriv.DerivTradeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Responsável pela inicialização pós-conexão do bot.
 *
 * Executa, em sequência, as três etapas obrigatórias após o
 * estabelecimento da conexão WebSocket:
 * <ol>
 *   <li>Valida a disponibilidade de contratos CALL e PUT por ativo.</li>
 *   <li>Solicita o histórico de candles para cada ativo registrado.</li>
 *   <li>Subscreve o feed de ticks em tempo real por ativo.</li>
 * </ol>
 *
 * Mantém internamente o mapeamento entre IDs de requisição de histórico
 * e seus respectivos símbolos, permitindo que respostas assíncronas
 * sejam correlacionadas ao ativo correto via {@link #resolveHistorySymbol(Long)}.
 *
 * Colabora com {@link DerivMarketDataService} para operações de mercado
 * e com {@link DerivTradeService} para validação de disponibilidade de contratos.
 */
@Component
public class BotInitializer {

    private static final Logger log =
            LoggerFactory.getLogger(BotInitializer.class);

    private final DerivMarketDataService marketDataService;
    private final DerivTradeService tradeService;

    /**
     * Mapa de correlação entre IDs de requisição de histórico e símbolos.
     * Entradas são inseridas em {@link #requestHistoryForPipeline(BotPipeline)}
     * e removidas em {@link #resolveHistorySymbol(Long)}, garantindo
     * que cada requisição seja resolvida exatamente uma vez.
     */
    private final Map<Long, String> historyReqIdToSymbol =
            new ConcurrentHashMap<>();

    public BotInitializer(
            DerivMarketDataService marketDataService,
            DerivTradeService tradeService
    ) {
        this.marketDataService = marketDataService;
        this.tradeService      = tradeService;
    }

    /**
     * Executa a sequência completa de inicialização pós-conexão.
     *
     * Itera sobre todos os pipelines registrados e, para cada um,
     * executa as etapas de validação de trade, requisição de histórico
     * e subscrição de ticks, nessa ordem.
     *
     * @param pipelines mapa de pipelines ativos, indexados por símbolo
     */
    public void initialize(Map<String, BotPipeline> pipelines) {
        validateAllTradeAvailabilities(pipelines);
        requestAllHistories(pipelines);
        subscribeAllTicks(pipelines);

        log.info("BOOTSTRAP OK | symbols={}", pipelines.size());
    }

    /**
     * Resolve o símbolo associado a um ID de requisição de histórico.
     *
     * Remove a entrada do mapa após a resolução, garantindo que cada
     * ID seja consumido uma única vez. Retorna {@code null} quando o
     * ID não corresponde a nenhuma requisição pendente.
     *
     * @param reqId ID da requisição de histórico retornado pela API
     * @return símbolo correspondente, ou {@code null} se não encontrado
     */
    public String resolveHistorySymbol(Long reqId) {
        return historyReqIdToSymbol.remove(reqId);
    }

    // ═══════════════════════════════════════════════════════════════
    // Validação de disponibilidade de trade
    // ═══════════════════════════════════════════════════════════════

    /**
     * Valida a disponibilidade de contratos para todos os ativos
     * com trade habilitado nos pipelines fornecidos.
     *
     * @param pipelines mapa de pipelines ativos, indexados por símbolo
     */
    private void validateAllTradeAvailabilities(
            Map<String, BotPipeline> pipelines
    ) {
        pipelines.values().stream()
                .map(BotPipeline::profile)
                .filter(profile -> profile.getTrade().isEnabled())
                .forEach(this::validateTradeAvailability);
    }

    /**
     * Verifica se os contratos CALL e PUT estão disponíveis para o ativo,
     * registrando o resultado via log informativo ou de alerta.
     *
     * @param profile configuração do ativo a ser validado
     */
    private void validateTradeAvailability(StrategiesProfile profile) {
        String      symbol = profile.getSymbol();
        TradeConfig trade  = profile.getTrade();

        boolean callAvailable = checkContract(symbol, "CALL", trade);
        boolean putAvailable  = checkContract(symbol, "PUT",  trade);

        if (callAvailable && putAvailable) {
            log.info("TRADE VALIDATION OK | symbol={} | duration={}{} "
                            + "| contracts=CALL/PUT",
                    symbol,
                    trade.getDuration(),
                    trade.getDurationUnit());
        } else {
            log.warn("TRADE VALIDATION WARNING | symbol={} | duration={}{} "
                            + "| CALL={} | PUT={}",
                    symbol,
                    trade.getDuration(),
                    trade.getDurationUnit(),
                    callAvailable,
                    putAvailable);
        }
    }

    /**
     * Verifica a disponibilidade de um tipo específico de contrato
     * para o ativo informado, delegando a consulta ao {@link DerivTradeService}.
     *
     * @param symbol       símbolo do ativo
     * @param contractType tipo do contrato a verificar (ex: "CALL" ou "PUT")
     * @param trade        configuração de trade com parâmetros financeiros
     * @return {@code true} se o contrato está disponível, {@code false} caso contrário
     */
    private boolean checkContract(
            String symbol,
            String contractType,
            TradeConfig trade
    ) {
        return tradeService.checkTradeAvailability(
                symbol,
                contractType,
                trade.getAmount(),
                trade.getCurrency(),
                trade.getDuration(),
                trade.getDurationUnit()
        );
    }

    // ═══════════════════════════════════════════════════════════════
    // Histórico de candles
    // ═══════════════════════════════════════════════════════════════

    /**
     * Solicita o histórico de candles para todos os pipelines registrados.
     *
     * @param pipelines mapa de pipelines ativos, indexados por símbolo
     */
    private void requestAllHistories(
            Map<String, BotPipeline> pipelines
    ) {
        pipelines.values()
                .forEach(this::requestHistoryForPipeline);
    }

    /**
     * Solicita o histórico de candles para um pipeline específico,
     * registrando o ID da requisição no mapa de correlação para
     * resolução posterior da resposta assíncrona.
     *
     * @param pipeline pipeline do ativo para o qual o histórico será requisitado
     */
    private void requestHistoryForPipeline(BotPipeline pipeline) {
        StrategiesProfile profile     = pipeline.profile();
        String            symbol      = profile.getSymbol();
        int               granularity = profile.getGranularitySeconds();
        int               count       = profile.getMaxBars();

        long reqId = marketDataService.fetchCandleHistory(
                symbol, granularity, count);

        // Registra a correlação entre o ID da requisição e o símbolo
        // para que a resposta assíncrona possa ser roteada corretamente
        historyReqIdToSymbol.put(reqId, symbol);

        log.info("HISTORY REQUESTED | symbol={} | granularity={}s "
                        + "| count={} | req_id={}",
                symbol, granularity, count, reqId);
    }

    // ═══════════════════════════════════════════════════════════════
    // Subscrição de ticks
    // ═══════════════════════════════════════════════════════════════

    /**
     * Subscreve o feed de ticks em tempo real para todos os símbolos
     * dos pipelines registrados.
     *
     * @param pipelines mapa de pipelines ativos, indexados por símbolo
     */
    private void subscribeAllTicks(
            Map<String, BotPipeline> pipelines
    ) {
        pipelines.keySet()
                .forEach(this::subscribeTicksForSymbol);
    }

    /**
     * Subscreve o feed de ticks em tempo real para um símbolo específico,
     * delegando a operação ao {@link DerivMarketDataService}.
     *
     * @param symbol símbolo do ativo a ser subscrito
     */
    private void subscribeTicksForSymbol(String symbol) {
        marketDataService.subscribeTicks(symbol);
        log.info("TICKS SUBSCRIBED | symbol={}", symbol);
    }
}