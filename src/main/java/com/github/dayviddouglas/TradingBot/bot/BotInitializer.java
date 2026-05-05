package com.github.dayviddouglas.TradingBot.bot;

import com.github.dayviddouglas.TradingBot.config.StrategiesProfile;
import com.github.dayviddouglas.TradingBot.config.TradeConfig;
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
 * Correção v5.3:
 * O passo de autorização foi removido. Com o novo modelo OTP da Deriv,
 * a autenticação acontece na URL do WebSocket antes da conexão ser
 * estabelecida. Quando o callback onConnected é disparado, o bot
 * já está autenticado e pode operar diretamente.
 *
 * Sequência de inicialização atualizada:
 * 1. ~~Autoriza sessão~~ — removido (autenticação via OTP na URL)
 * 2. Valida disponibilidade de trade por ativo
 * 3. Solicita histórico de candles por ativo
 * 4. Subscreve ticks em tempo real por ativo
 */
@Component
public class BotInitializer {

    private static final Logger log =
            LoggerFactory.getLogger(BotInitializer.class);

    private final DerivMarketDataService marketDataService;
    private final DerivTradeService tradeService;

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
     * Correção v5.3: authorize removido — bot já autenticado via OTP.
     *
     * @param pipelines mapa de pipelines registrados por símbolo
     */
    public void initialize(Map<String, BotPipeline> pipelines) {
        validateAllTradeAvailabilities(pipelines);
        requestAllHistories(pipelines);
        subscribeAllTicks(pipelines);

        log.info("BOOTSTRAP OK | symbols={}", pipelines.size());
    }

    /**
     * Correlaciona um reqId de histórico com o símbolo correspondente.
     *
     * @param reqId ID da requisição de histórico
     * @return símbolo correspondente ou null se não encontrado
     */
    public String resolveHistorySymbol(Long reqId) {
        return historyReqIdToSymbol.remove(reqId);
    }

    // ═══════════════════════════════════════════════════════════════
    // Validação de disponibilidade de trade
    // ═══════════════════════════════════════════════════════════════

    private void validateAllTradeAvailabilities(
            Map<String, BotPipeline> pipelines
    ) {
        pipelines.values().stream()
                .map(BotPipeline::profile)
                .filter(profile -> profile.getTrade().isEnabled())
                .forEach(this::validateTradeAvailability);
    }

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

    private void requestAllHistories(
            Map<String, BotPipeline> pipelines
    ) {
        pipelines.values()
                .forEach(this::requestHistoryForPipeline);
    }

    private void requestHistoryForPipeline(BotPipeline pipeline) {
        StrategiesProfile profile     = pipeline.profile();
        String            symbol      = profile.getSymbol();
        int               granularity = profile.getGranularitySeconds();
        int               count       = profile.getMaxBars();

        long reqId = marketDataService.fetchCandleHistory(
                symbol, granularity, count);

        historyReqIdToSymbol.put(reqId, symbol);

        log.info("HISTORY REQUESTED | symbol={} | granularity={}s "
                        + "| count={} | req_id={}",
                symbol, granularity, count, reqId);
    }

    // ═══════════════════════════════════════════════════════════════
    // Subscrição de ticks
    // ═══════════════════════════════════════════════════════════════

    private void subscribeAllTicks(
            Map<String, BotPipeline> pipelines
    ) {
        pipelines.keySet()
                .forEach(this::subscribeTicksForSymbol);
    }

    private void subscribeTicksForSymbol(String symbol) {
        marketDataService.subscribeTicks(symbol);
        log.info("TICKS SUBSCRIBED | symbol={}", symbol);
    }
}