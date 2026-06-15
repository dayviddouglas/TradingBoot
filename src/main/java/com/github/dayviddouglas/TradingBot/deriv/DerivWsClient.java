package com.github.dayviddouglas.TradingBot.deriv;

import com.github.dayviddouglas.TradingBot.bot.MultiSymbolDerivBotRunner;
import com.github.dayviddouglas.TradingBot.deriv.ws.TickHeartbeat;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Cliente WebSocket de baixo nível para comunicação com a API da Deriv.
 *
 * Gerencia o ciclo de vida completo da conexão WebSocket:
 * <ul>
 *   <li>Criação do cliente com URI dinâmica obtida via {@link Supplier}, chamado apenas
 *       no momento de cada conexão para obter um OTP fresco</li>
 *   <li>Roteamento de eventos do WebSocket ({@code onOpen}, {@code onMessage},
 *       {@code onClose}, {@code onError}) para os handlers registrados externamente</li>
 *   <li>Keep-alive via ping periódico com intervalo configurável</li>
 *   <li>Reconexão automática com backoff exponencial ao detectar desconexão</li>
 *   <li>Integração com {@link TickHeartbeat} para monitoramento de saúde da conexão</li>
 *   <li>Retomada coordenada da operação via {@link MultiSymbolDerivBotRunner#reconnectionBot()}
 *       após reconexão bem-sucedida</li>
 * </ul>
 *
 * O {@code internalClient} é substituído a cada reconexão por uma nova instância,
 * garantindo que o OTP utilizado seja sempre fresco. O campo é {@code volatile} para
 * garantir visibilidade entre as threads de conexão e de envio.
 *
 * Disponibiliza dois construtores adicionais retrocompatíveis: um que aceita
 * {@link MultiSymbolDerivBotRunner} para injeção secundária no bean do Spring,
 * e outro que aceita {@link URI} fixa para uso em ferramentas standalone.
 */
public class DerivWsClient implements AutoCloseable {

    private static final Logger log =
            LoggerFactory.getLogger(DerivWsClient.class);

    /**
     * Fornece a URI do WebSocket antes de cada conexão.
     * No runtime: invoca {@link DerivOtpService#fetchWsUri()} para obter um OTP fresco.
     * Em ferramentas standalone: retorna URI fixa sem chamada REST.
     */
    private Supplier<URI> uriSupplier;
    private TickHeartbeat tickHeartbeat;

    /**
     * Instância atual do cliente WebSocket da biblioteca java-websocket.
     * Substituída a cada reconexão. {@code null} antes da primeira chamada a {@link #connect()}.
     */
    private volatile WebSocketClient internalClient;

    /** Handler invocado para cada mensagem JSON recebida do WebSocket. */
    private volatile Consumer<String>      messageHandler = msg -> {};

    /** Callback invocado após abertura bem-sucedida da conexão. */
    private volatile Runnable              onConnected;

    /** Callback invocado após fechamento da conexão, com código e motivo. */
    private volatile Consumer<CloseEvent>  onDisconnected;

    /**
     * Injetado pelo construtor secundário para permitir que a reconexão coordene
     * a retomada do pipeline do bot via {@link MultiSymbolDerivBotRunner#reconnectionBot()}.
     */
    private MultiSymbolDerivBotRunner multiSymbolDerivBotRunner;

    /** Encapsula os dados de um evento de fechamento do WebSocket. */
    public record CloseEvent(int code, String reason, boolean remote) {}

    /**
     * Scheduler compartilhado para o ping loop e o mecanismo de reconexão.
     * Thread daemon para não impedir o shutdown da JVM.
     */
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "deriv-ws-supervisor");
                t.setDaemon(true);
                return t;
            });

    /** Controla se o cliente deve tentar se reconectar ao detectar desconexão. */
    private final AtomicBoolean shouldRun          = new AtomicBoolean(true);

    /** Evita agendamentos duplicados de reconexão quando múltiplos eventos são disparados. */
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);

    /** Contador de tentativas de reconexão, utilizado para calcular o backoff exponencial. */
    private final AtomicInteger reconnectAttempts  = new AtomicInteger(0);

    /** Garante que o ping loop seja iniciado apenas uma vez por ciclo de conexão. */
    private final AtomicBoolean pingLoopStarted    = new AtomicBoolean(false);

    private Duration pingInterval      = Duration.ofSeconds(25);
    private Duration reconnectMinDelay = Duration.ofSeconds(1);
    private Duration reconnectMaxDelay = Duration.ofSeconds(20);

    // ═══════════════════════════════════════════════════════════════
    // Construtores
    // ═══════════════════════════════════════════════════════════════

    /**
     * Construtor principal para uso no runtime do bot.
     * O {@code uriSupplier} é invocado apenas no momento de cada conexão,
     * nunca no construtor, evitando chamadas REST durante o startup do Spring.
     *
     * @param uriSupplier   fornece a URI WebSocket autenticada com OTP fresco antes de cada conexão
     * @param tickHeartbeat monitor de chegada de ticks para detecção de conexões silenciosas
     */
    public DerivWsClient(Supplier<URI> uriSupplier, TickHeartbeat tickHeartbeat) {
        this.uriSupplier   = uriSupplier;
        this.tickHeartbeat = tickHeartbeat;
        // internalClient não é criado aqui — instanciado apenas em connect()
    }

    /**
     * Construtor secundário utilizado pelo bean do Spring para injetar o
     * {@link MultiSymbolDerivBotRunner}, habilitando a chamada a
     * {@link MultiSymbolDerivBotRunner#reconnectionBot()} após reconexão bem-sucedida.
     *
     * @param multiSymbolDerivBotRunner runner do bot, invocado para retomar o pipeline após reconexão
     */
    public DerivWsClient(MultiSymbolDerivBotRunner multiSymbolDerivBotRunner) {
        this.multiSymbolDerivBotRunner = multiSymbolDerivBotRunner;
    }

    /**
     * Construtor retrocompatível para ferramentas standalone que utilizam URI fixa
     * sem necessidade de OTP. Utilizado por {@code ListOfFinancialAssets}
     * e {@code DerivHistoryDownloadTool}.
     *
     * @param serverUri URI fixa do endpoint WebSocket público da Deriv
     */
    public DerivWsClient(URI serverUri) {
        this(() -> serverUri, new TickHeartbeat());
    }

    // ═══════════════════════════════════════════════════════════════
    // Criação do cliente interno
    // ═══════════════════════════════════════════════════════════════

    /**
     * Cria uma nova instância de {@link WebSocketClient} invocando o {@code uriSupplier}.
     * No runtime, essa invocação dispara a chamada REST ao {@link DerivOtpService}
     * para obter um OTP fresco. Os handlers de eventos delegam para os métodos
     * {@code handle*} desta classe.
     * Chamado apenas em {@link #connect()}, {@link #connectBlocking()} e na reconexão automática.
     *
     * @return nova instância de {@link WebSocketClient} pronta para conexão
     */
    private WebSocketClient createNewClient() {
        URI uri = uriSupplier.get();
        log.info("WS CLIENT CREATING | uri={}",
                uri.toString().replaceAll("otp=[^&]+", "otp=***"));

        return new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake handshakeData) {
                DerivWsClient.this.handleOnOpen(handshakeData);
            }

            @Override
            public void onMessage(String message) {
                DerivWsClient.this.handleOnMessage(message);
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                DerivWsClient.this.handleOnClose(code, reason, remote);
            }

            @Override
            public void onError(Exception ex) {
                DerivWsClient.this.handleOnError(ex);
            }
        };
    }

    // ═══════════════════════════════════════════════════════════════
    // Configuração de callbacks e parâmetros
    // ═══════════════════════════════════════════════════════════════

    /**
     * Registra o handler de mensagens JSON recebidas via WebSocket.
     * Substitui por handler vazio quando {@code null} for informado.
     *
     * @param handler consumer invocado para cada mensagem recebida
     */
    public void setMessageHandler(Consumer<String> handler) {
        this.messageHandler = (handler == null) ? (msg -> {}) : handler;
    }

    /**
     * Registra o callback invocado após abertura bem-sucedida da conexão.
     *
     * @param cb runnable executado em virtual thread após o evento {@code onOpen}
     */
    public void setOnConnected(Runnable cb) {
        this.onConnected = cb;
    }

    /**
     * Registra o callback invocado após fechamento da conexão.
     *
     * @param cb consumer que recebe o {@link CloseEvent} com código, motivo e origem do fechamento
     */
    public void setOnDisconnected(Consumer<CloseEvent> cb) {
        this.onDisconnected = cb;
    }

    /**
     * Configura o intervalo entre pings de keep-alive.
     * Ignorado se {@code null} for informado.
     *
     * @param pingInterval intervalo entre pings
     */
    public void setPingInterval(Duration pingInterval) {
        if (pingInterval != null) this.pingInterval = pingInterval;
    }

    /**
     * Configura os limites de delay para o backoff exponencial de reconexão.
     * Parâmetros {@code null} mantêm os valores atuais.
     *
     * @param minDelay delay mínimo da primeira tentativa
     * @param maxDelay delay máximo aplicado após o crescimento exponencial
     */
    public void setReconnectBackoff(Duration minDelay, Duration maxDelay) {
        if (minDelay != null) this.reconnectMinDelay = minDelay;
        if (maxDelay != null) this.reconnectMaxDelay = maxDelay;
    }

    // ═══════════════════════════════════════════════════════════════
    // API pública de conexão
    // ═══════════════════════════════════════════════════════════════

    /**
     * Cria o cliente interno e inicia a conexão de forma assíncrona.
     * O OTP é obtido aqui via {@code uriSupplier.get()}, nunca no construtor.
     */
    public void connect() {
        internalClient = createNewClient();
        internalClient.connect();
    }

    /**
     * Cria o cliente interno e conecta de forma bloqueante até a conexão ser estabelecida.
     * O OTP é obtido aqui via {@code uriSupplier.get()}, nunca no construtor.
     *
     * @throws InterruptedException se a thread for interrompida durante a espera
     */
    public void connectBlocking() throws InterruptedException {
        internalClient = createNewClient();
        internalClient.connectBlocking();
    }

    /**
     * Cria o cliente interno e conecta de forma bloqueante com timeout máximo.
     * O OTP é obtido aqui via {@code uriSupplier.get()}, nunca no construtor.
     *
     * @param timeout tempo máximo de espera pela conexão
     * @param unit    unidade do timeout
     * @throws InterruptedException se a thread for interrompida durante a espera
     */
    public void connectBlocking(long timeout, TimeUnit unit)
            throws InterruptedException {
        internalClient = createNewClient();
        internalClient.connectBlocking(timeout, unit);
    }

    /**
     * Envia uma mensagem de texto pelo WebSocket.
     *
     * @param text mensagem JSON serializada a ser enviada
     * @throws IllegalStateException se {@link #connect()} ainda não tiver sido chamado
     */
    public void send(String text) {
        if (internalClient == null) {
            throw new IllegalStateException(
                    "WebSocket not connected. Call connect() first.");
        }
        internalClient.send(text);
    }

    /**
     * Verifica se a conexão WebSocket está aberta e pronta para envio.
     *
     * @return {@code true} se o cliente interno existir e estiver conectado
     */
    public boolean isOpen() {
        return internalClient != null && internalClient.isOpen();
    }

    // ═══════════════════════════════════════════════════════════════
    // Handlers internos
    // ═══════════════════════════════════════════════════════════════

    /**
     * Processa o evento de abertura da conexão WebSocket.
     * Reseta os contadores de reconexão, inicia o ping loop, ativa o {@link TickHeartbeat}
     * e executa o callback {@code onConnected} em virtual thread.
     */
    private void handleOnOpen(ServerHandshake handshakeData) {
        reconnectAttempts.set(0);
        reconnectScheduled.set(false);

        log.info("Deriv WS opened | httpStatus={}",
                handshakeData.getHttpStatus());

        startPingLoop();
        tickHeartbeat.startMonitoring();

        Runnable cb = onConnected;
        if (cb != null) {
            Thread.startVirtualThread(() -> {
                try {
                    cb.run();
                } catch (Exception e) {
                    log.warn("onConnected callback error", e);
                }
            });
        }
    }

    /**
     * Repassa a mensagem recebida ao handler registrado.
     * Erros no handler são capturados para não interromper o loop do WebSocket.
     *
     * @param message mensagem JSON bruta recebida da API
     */
    private void handleOnMessage(String message) {
        try {
            messageHandler.accept(message);
        } catch (Exception e) {
            log.error("Message handler error | raw={}", message, e);
        }
    }

    /**
     * Processa o evento de fechamento da conexão.
     * Para o {@link TickHeartbeat}, invoca o callback {@code onDisconnected}
     * e agenda reconexão automática quando {@link #shouldRun} estiver ativo.
     *
     * @param code   código de fechamento do WebSocket
     * @param reason motivo do fechamento
     * @param remote {@code true} se o fechamento foi iniciado pelo servidor
     */
    private void handleOnClose(int code, String reason, boolean remote) {
        log.warn("Deriv WS closed | code={} | remote={} | reason={}",
                code, remote, reason);

        tickHeartbeat.stopMonitoring();

        Consumer<CloseEvent> cb = onDisconnected;
        if (cb != null) {
            try {
                cb.accept(new CloseEvent(code, reason, remote));
            } catch (Exception e) {
                log.warn("onDisconnected callback error", e);
            }
        }

        // Aciona reconexão apenas se o cliente não foi encerrado intencionalmente
        if (shouldRun.get()) {
            scheduleReconnect(code, reason, remote);
        }
    }

    /**
     * Registra erros de baixo nível do WebSocket em nível {@code error}.
     *
     * @param ex exceção reportada pela biblioteca java-websocket
     */
    private void handleOnError(Exception ex) {
        log.error("Deriv WS error", ex);
    }

    // ═══════════════════════════════════════════════════════════════
    // Ping Loop (keep-alive)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Inicia o loop de ping periódico para manter a conexão ativa.
     * Utiliza {@link AtomicBoolean} para garantir que apenas um loop seja iniciado
     * por ciclo de conexão, mesmo em cenários de múltiplos eventos {@code onOpen}.
     */
    private void startPingLoop() {
        if (!pingLoopStarted.compareAndSet(false, true)) return;

        scheduler.scheduleWithFixedDelay(() -> {
                    try {
                        if (!shouldRun.get()) return;
                        if (!isOpen()) return;
                        internalClient.sendPing();
                    } catch (Exception e) {
                        log.debug("Ping failed: {}", e.getMessage());
                    }
                }, pingInterval.toSeconds(), pingInterval.toSeconds(),
                TimeUnit.SECONDS);
    }

    // ═══════════════════════════════════════════════════════════════
    // Reconexão automática com backoff exponencial
    // ═══════════════════════════════════════════════════════════════

    /**
     * Agenda uma tentativa de reconexão com backoff exponencial.
     * O {@link AtomicBoolean#compareAndSet} em {@code reconnectScheduled} garante
     * que apenas uma reconexão seja agendada por vez, mesmo quando múltiplos eventos
     * de fechamento chegam simultaneamente.
     *
     * A cada tentativa, {@code createNewClient()} invoca o {@code uriSupplier}
     * para obter um OTP fresco, garantindo que a nova sessão seja autenticada.
     * Após reconexão bem-sucedida, invoca {@link MultiSymbolDerivBotRunner#reconnectionBot()}
     * para retomar o pipeline do bot de forma coordenada.
     *
     * @param code   código de fechamento, repassado em caso de nova tentativa
     * @param reason motivo do fechamento, repassado em caso de nova tentativa
     * @param remote origem do fechamento, repassada em caso de nova tentativa
     */
    private void scheduleReconnect(int code, String reason, boolean remote) {
        if (!reconnectScheduled.compareAndSet(false, true)) return;

        int  attempt = reconnectAttempts.incrementAndGet();
        long delayMs = computeBackoffMs(attempt);

        if (attempt == 1) {
            log.warn("WS disconnected | code={} | reconnecting...", code);
        } else {
            log.warn("WS reconnect | attempt={} | waiting={}ms",
                    attempt, delayMs);
        }

        scheduler.schedule(() -> {
            if (!shouldRun.get()) return;

            try {
                log.info("WS reconnecting... | attempt={}", attempt);

                tickHeartbeat.stopMonitoring();

                // Fecha o cliente anterior se ainda estiver aberto
                try {
                    if (internalClient != null && internalClient.isOpen()) {
                        internalClient.close();
                    }
                } catch (Exception ignored) {}

                // Reseta o heartbeat para evitar que o timestamp anterior seja reaproveitado
                tickHeartbeat.reset();

                // createNewClient() invoca uriSupplier.get() para obter OTP fresco
                internalClient = createNewClient();
                boolean connected = internalClient
                        .connectBlocking(10, TimeUnit.SECONDS);

                if (!connected || !internalClient.isOpen()) {
                    throw new IllegalStateException(
                            "Socket not open after connectBlocking");
                } else {
                    // Retomada coordenada do pipeline do bot após reconexão bem-sucedida
                    multiSymbolDerivBotRunner.reconnectionBot();
                }

                log.info("WS reconnection successful | attempt={}", attempt);

            } catch (Exception e) {
                reconnectScheduled.set(false);
                long nextDelay = computeBackoffMs(attempt + 1);
                log.warn("WS reconnect failed | attempt={} | reason={} "
                                + "| retrying in {}ms...",
                        attempt, e.getMessage(), nextDelay);
                scheduleReconnect(code, reason, remote);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Calcula o delay de backoff exponencial para a tentativa informada.
     * O delay cresce como {@code minDelay * 2^(attempt-1)}, limitado por {@code maxDelay}.
     *
     * @param attempt número da tentativa de reconexão
     * @return delay em milissegundos para a tentativa
     */
    private long computeBackoffMs(int attempt) {
        long min  = reconnectMinDelay.toMillis();
        long max  = reconnectMaxDelay.toMillis();
        long base = min * (1L << Math.min(attempt - 1, 5));
        return Math.min(base, max);
    }

    // ═══════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════

    /**
     * Encerra o cliente WebSocket de forma ordenada.
     * Sinaliza que reconexões não devem ser tentadas, para o {@link TickHeartbeat},
     * interrompe o scheduler e fecha o cliente interno.
     */
    @Override
    public void close() {
        try {
            shouldRun.set(false);
            tickHeartbeat.stopMonitoring();
            scheduler.shutdownNow();
        } catch (Exception ignored) {}

        try {
            if (internalClient != null) {
                internalClient.close();
            }
        } catch (Exception e) {
            log.warn("Error while closing Deriv WS", e);
        }
    }
}