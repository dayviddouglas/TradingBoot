package com.github.dayviddouglas.TradingBot.bot;

import com.github.dayviddouglas.TradingBot.config.StrategiesConfigLoader;
import com.github.dayviddouglas.TradingBot.config.StrategiesProfile;
import com.github.dayviddouglas.TradingBot.deriv.DerivMarketDataService;
import com.github.dayviddouglas.TradingBot.deriv.DerivTradeService;
import com.github.dayviddouglas.TradingBot.deriv.DerivWsClient;
import com.github.dayviddouglas.TradingBot.engine.StrategyEngine;
import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.model.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orquestrador principal do bot em tempo real.
 *
 * Correção v5.3:
 * O fluxo de inicialização foi simplificado. Com o novo modelo OTP
 * da Deriv, a autenticação acontece antes da conexão WebSocket ser
 * estabelecida. Quando onConnected é disparado, o bot já está
 * autenticado e pode operar diretamente — sem etapa de authorize.
 *
 * Fluxo atualizado:
 * 1. Carrega profiles do strategies.json
 * 2. Constrói pipelines via PipelineRegistry
 * 3. Registra callbacks de mercado
 * 4. Registra callbacks de conexão
 * 5. Conecta WebSocket (OTP obtido automaticamente pelo DerivWsClient)
 * 6. Aguarda indefinidamente
 */
@Component
public class MultiSymbolDerivBotRunner implements CommandLineRunner {

    private static final Logger log =
            LoggerFactory.getLogger(MultiSymbolDerivBotRunner.class);

    private final StrategiesConfigLoader strategiesLoader;
    private final DerivWsClient          derivWsClient;
    private final DerivMarketDataService marketDataService;
    private final DerivTradeService      tradeService;
    private final PipelineRegistry       pipelineRegistry;
    private final BotInitializer         botInitializer;

    private final AtomicBoolean initialized = new AtomicBoolean(false);

    public MultiSymbolDerivBotRunner(
            StrategiesConfigLoader strategiesLoader,
            DerivWsClient derivWsClient,
            DerivMarketDataService marketDataService,
            DerivTradeService tradeService,
            PipelineRegistry pipelineRegistry,
            BotInitializer botInitializer
    ) {
        this.strategiesLoader = strategiesLoader;
        this.derivWsClient    = derivWsClient;
        this.marketDataService = marketDataService;
        this.tradeService     = tradeService;
        this.pipelineRegistry = pipelineRegistry;
        this.botInitializer   = botInitializer;
    }

    // ═══════════════════════════════════════════════════════════════
    // CommandLineRunner: ponto de entrada
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void run(String... args) throws Exception {
        List<StrategiesProfile> profiles =
                strategiesLoader.getProfiles();

        if (profiles.isEmpty()) {
            log.error("No profiles loaded from strategies.json. Aborting.");
            return;
        }

        log.info("BOT STARTING | profiles={}", profiles.size());

        pipelineRegistry.buildAll(profiles, this::handleFinalSignal);

        if (pipelineRegistry.size() == 0) {
            log.error("No valid pipelines built. Aborting.");
            return;
        }

        registerMarketCallbacks();
        registerConnectionCallbacks();

        log.info("BOT CONNECTING | symbols={}", pipelineRegistry.size());

        // OTP é obtido automaticamente pelo DerivWsClient
        // via uriSupplier antes de conectar
        derivWsClient.connect();

        log.info("BOT RUNNING | Press Ctrl+C to stop.");

        Thread.currentThread().join();
    }

    // ═══════════════════════════════════════════════════════════════
    // Callbacks de conexão
    // ═══════════════════════════════════════════════════════════════

    private void registerConnectionCallbacks() {
        derivWsClient.setOnConnected(() -> {
            try {
                initializeAfterConnection();
            } catch (Exception e) {
                log.error("INITIALIZATION FAILED | error={}",
                        e.getMessage(), e);
            }
        });

        derivWsClient.setOnDisconnected(event ->
                log.warn("WS DISCONNECTED | code={} | remote={} | reason={}",
                        event.code(), event.remote(), event.reason()));
    }

    /**
     * Executado após conexão WebSocket bem-sucedida.
     *
     * Correção v5.3: não há mais etapa de authorize — o bot já está
     * autenticado via OTP embutido na URL de conexão.
     * O initialized.compareAndSet evita dupla inicialização em
     * reconexões rápidas.
     */
    private void initializeAfterConnection() {
        if (!initialized.compareAndSet(false, true)) {
            log.debug("Already initialized, skipping...");
            return;
        }

        log.info("WS CONNECTED | initializing pipelines...");
        botInitializer.initialize(pipelineRegistry.getAll());
    }

    // ═══════════════════════════════════════════════════════════════
    // Callbacks de mercado
    // ═══════════════════════════════════════════════════════════════

    private void registerMarketCallbacks() {
        marketDataService.onTick(this::handleTick);
        marketDataService.onCandleHistory(this::handleCandleHistory);
    }

    private void handleTick(String symbol, long epoch, double quote) {
        if (isInvalidTick(symbol, epoch, quote)) return;

        pipelineRegistry.get(symbol).ifPresent(pipeline ->
                pipeline.aggregator().onTick(epoch, quote));
    }

    private void handleCandleHistory(Long reqId, List<Bar> bars) {
        String symbol = botInitializer.resolveHistorySymbol(reqId);

        if (symbol == null) {
            log.debug("HISTORY RECEIVED | unknown req_id={}", reqId);
            return;
        }

        pipelineRegistry.get(symbol).ifPresent(pipeline -> {
            pipeline.engine().seedHistory(bars);
            log.info("HISTORY SEEDED | symbol={} | bars={}",
                    symbol, bars.size());
        });
    }

    private boolean isInvalidTick(
            String symbol, long epoch, double quote
    ) {
        return symbol == null
                || symbol.isBlank()
                || epoch <= 0
                || !Double.isFinite(quote);
    }

    // ═══════════════════════════════════════════════════════════════
    // Tratamento de sinal final
    // ═══════════════════════════════════════════════════════════════

    private void handleFinalSignal(
            StrategiesProfile profile,
            StrategyEngine engine,
            Signal signal
    ) {
        log.info("FINAL SIGNAL | symbol={} | type={} | price={} "
                        + "| strategy={} | decisionMode={} | time={}",
                profile.getSymbol(),
                signal.getType(),
                signal.getPrice(),
                signal.getStrategy(),
                profile.getDecisionMode(),
                signal.getTimestamp());

        List<Bar> recentBars = engine.getBarsSnapshot();
        tradeService.onFinalSignal(profile, signal, recentBars);
    }
}