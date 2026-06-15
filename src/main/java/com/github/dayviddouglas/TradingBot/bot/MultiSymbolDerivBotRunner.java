package com.github.dayviddouglas.TradingBot.bot;

import com.github.dayviddouglas.TradingBot.config.strategy.StrategiesConfigLoader;
import com.github.dayviddouglas.TradingBot.config.strategy.StrategiesProfile;
import com.github.dayviddouglas.TradingBot.deriv.DerivMarketDataService;
import com.github.dayviddouglas.TradingBot.deriv.DerivTradeService;
import com.github.dayviddouglas.TradingBot.deriv.DerivWsClient;
import com.github.dayviddouglas.TradingBot.engine.core.StrategyEngine;
import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.model.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orquestrador principal do bot em tempo real.
 *
 * Ponto de entrada da aplicação via {@link CommandLineRunner}.
 * Coordena o ciclo de vida completo do bot: carregamento de configurações,
 * construção de pipelines por ativo, registro de callbacks de mercado e
 * de conexão, e estabelecimento da conexão WebSocket com a API da Deriv.
 *
 * Também atua como ponto de retomada após reconexões WebSocket, expondo
 * {@link #reconnectionBot()} para ser invocado pelo {@link DerivWsClient}
 * quando uma reconexão bem-sucedida é detectada.
 *
 * Colabora com:
 * <ul>
 *   <li>{@link StrategiesConfigLoader} — carrega os profiles de estratégia.</li>
 *   <li>{@link PipelineRegistry} — constrói e mantém os pipelines por ativo.</li>
 *   <li>{@link BotInitializer} — executa a inicialização pós-conexão.</li>
 *   <li>{@link DerivWsClient} — gerencia a conexão WebSocket.</li>
 *   <li>{@link DerivMarketDataService} — fornece ticks e histórico de candles.</li>
 *   <li>{@link DerivTradeService} — recebe sinais finais para execução de contratos.</li>
 * </ul>
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

    /**
     * Controla se a inicialização pós-conexão já foi executada.
     * Impede dupla execução de {@link #initializeAfterConnection()}
     * em cenários de reconexão rápida.
     */
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
        connectBot();
    }

    // ═══════════════════════════════════════════════════════════════
    // Callbacks de conexão
    // ═══════════════════════════════════════════════════════════════

    /**
     * Executa o fluxo completo de inicialização e conexão do bot.
     *
     * Carrega os profiles do strategies.json, constrói os pipelines,
     * registra os callbacks de mercado e de conexão, estabelece a
     * conexão WebSocket e bloqueia a thread corrente indefinidamente
     * para manter o processo ativo.
     *
     * Interrompido apenas por sinal externo (ex: Ctrl+C).
     */
    public void connectBot() {
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
        try {
            // Bloqueia a thread principal para manter o processo ativo
            Thread.currentThread().join();

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Retomada do bot após uma reconexão WebSocket bem-sucedida.
     *
     * Reinicializa os pipelines existentes, recarrega os profiles,
     * reconstrói os pipelines e reregistra os callbacks de mercado.
     * Bloqueia a thread corrente ao final, mantendo o bot operacional
     * até a próxima desconexão ou interrupção.
     *
     * Invocado pelo {@link DerivWsClient} quando a reconexão é confirmada.
     */
    public void reconnectionBot() {
        log.info("WS CONNECTED | initializing pipelines...");

        // Reinicializa os pipelines com os dados já disponíveis no registry
        botInitializer.initialize(pipelineRegistry.getAll());

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

        log.info("BOT CONNECTING | symbols={}", pipelineRegistry.size());
        log.info("BOT RUNNING | Press Ctrl+C to stop.");
        try {
            // Bloqueia a thread corrente para manter o bot operacional
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Registra os callbacks de conexão e desconexão no {@link DerivWsClient}.
     *
     * O callback de conexão delega a inicialização para
     * {@link #initializeAfterConnection()}, protegida contra dupla execução.
     * O callback de desconexão registra o evento via log de alerta.
     */
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
     * O {@code initialized.compareAndSet} garante que a inicialização
     * dos pipelines ocorra apenas uma vez, mesmo que o callback de
     * conexão seja disparado múltiplas vezes em reconexões rápidas.
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

    /**
     * Registra os callbacks de ticks e de histórico de candles
     * no {@link DerivMarketDataService}.
     */
    private void registerMarketCallbacks() {
        marketDataService.onTick(this::handleTick);
        marketDataService.onCandleHistory(this::handleCandleHistory);
    }

    /**
     * Processa um tick recebido em tempo real.
     *
     * Descarta o tick se os dados forem inválidos e, caso contrário,
     * repassa ao agregador do pipeline correspondente ao símbolo.
     *
     * @param symbol símbolo do ativo
     * @param epoch  timestamp do tick em segundos
     * @param quote  preço do tick
     */
    private void handleTick(String symbol, long epoch, double quote) {
        if (isInvalidTick(symbol, epoch, quote)) return;

        pipelineRegistry.get(symbol).ifPresent(pipeline ->
                pipeline.aggregator().onTick(epoch, quote));
    }

    /**
     * Processa o histórico de candles recebido em resposta a uma requisição.
     *
     * Resolve o símbolo a partir do ID da requisição via {@link BotInitializer}.
     * Caso o símbolo seja identificado, alimenta o histórico no engine do pipeline
     * e dispara a avaliação de regime a partir dos dados históricos carregados.
     *
     * @param reqId ID da requisição que originou este histórico
     * @param bars  lista de candles recebidos
     */
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

            // Avalia o regime de mercado a partir do histórico recém-carregado,
            // permitindo que o bot inicie com o regime já confirmado
            pipeline.engine().evaluateRegimeFromHistory();
        });
    }

    /**
     * Valida se os dados de um tick são utilizáveis para processamento.
     *
     * Considera inválido qualquer tick com símbolo nulo ou em branco,
     * epoch não positivo ou preço não finito.
     *
     * @param symbol símbolo do ativo
     * @param epoch  timestamp do tick em segundos
     * @param quote  preço do tick
     * @return {@code true} se o tick for inválido, {@code false} caso contrário
     */
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

    /**
     * Processa um sinal final gerado pelo {@link StrategyEngine} de um ativo.
     *
     * Registra o sinal via log e encaminha para o {@link DerivTradeService},
     * junto com o snapshot recente de candles do engine, para avaliação
     * e eventual execução de contrato.
     *
     * @param profile configuração do ativo que gerou o sinal
     * @param engine  engine do ativo que gerou o sinal
     * @param signal  sinal operacional produzido
     */
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