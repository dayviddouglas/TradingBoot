package com.github.dayviddouglas.TradingBot.deriv.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Monitora a chegada de ticks em tempo real para confirmar que a conexão WebSocket
 * está funcional e recebendo dados do mercado.
 *
 * Um WebSocket tecnicamente conectado pode estar em estado silencioso por falha
 * na autorização, subscrição ou problema de rede sem sinalização de erro.
 * Este componente detecta essa situação rastreando o timestamp do último tick recebido
 * e acionando um callback configurável quando o silêncio exceder o limiar definido
 * em {@link #TICK_TIMEOUT}.
 *
 * A verificação ocorre em background a cada {@link #CHECK_INTERVAL} via
 * {@link ScheduledExecutorService} em thread daemon, sem bloquear nenhuma thread do sistema.
 * O callback de timeout é executado em virtual thread para não bloquear o scheduler
 * e permitir que as verificações subsequentes ocorram normalmente durante a reconexão.
 *
 * O timeout é dimensionado em 3 minutos para tolerar períodos de mercado fechado
 * (fins de semana, feriados) sem gerar falsos positivos de reconexão.
 */
@Component
public class TickHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(TickHeartbeat.class);

    /**
     * Silêncio máximo tolerado antes de considerar a conexão inativa.
     * Definido em 3 minutos para tolerar períodos de mercado fechado sem
     * interpretar a ausência de ticks como falha de conexão.
     */
    private static final Duration TICK_TIMEOUT = Duration.ofMinutes(3);

    /**
     * Intervalo entre cada verificação periódica de chegada de ticks.
     * Definido em 30 segundos, pois mercados ativos emitem ticks com
     * frequência muito maior, tornando qualquer silêncio rapidamente detectável.
     */
    private static final Duration CHECK_INTERVAL = Duration.ofSeconds(30);

    /**
     * Timestamp do último tick recebido. {@link AtomicReference} garante
     * visibilidade entre a thread do WebSocket (que grava) e a thread do
     * scheduler (que lê) sem necessidade de sincronização explícita.
     */
    private final AtomicReference<Instant> lastTickAt = new AtomicReference<>();

    /**
     * Scheduler em thread daemon para não impedir o shutdown da JVM
     * quando a aplicação for encerrada normalmente.
     */
    private final ScheduledExecutorService scheduler = Executors
            .newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "tick-heartbeat");
                t.setDaemon(true);
                return t;
            });

    private ScheduledFuture<?> monitorTask;

    /** Callback acionado quando o silêncio de ticks exceder {@link #TICK_TIMEOUT}. */
    private Runnable onTickTimeout;

    /**
     * Registra a chegada de um tick, atualizando o timestamp de referência.
     * Invocado pelo {@link com.github.dayviddouglas.TradingBot.deriv.DerivMarketDataService}
     * a cada tick recebido da API Deriv.
     */
    public void recordTick() {
        lastTickAt.set(Instant.now());
    }

    /**
     * Registra o callback a ser executado quando o timeout de ticks for detectado.
     * Normalmente configurado para acionar o fluxo de reconexão do bot.
     *
     * @param callback ação executada em virtual thread ao detectar ausência de ticks
     */
    public void onTickTimeout(Runnable callback) {
        this.onTickTimeout = callback;
    }

    /**
     * Inicia o monitoramento periódico de chegada de ticks em background.
     * Cancela qualquer tarefa anterior antes de agendar uma nova,
     * evitando monitoramentos duplicados após reconexões.
     */
    public void startMonitoring() {
        stopMonitoring();

        log.info("TICK HEARTBEAT | monitoring started | timeout={}min",
                TICK_TIMEOUT.toMinutes());

        monitorTask = scheduler.scheduleWithFixedDelay(
                this::checkTickArrival,
                CHECK_INTERVAL.toSeconds(),
                CHECK_INTERVAL.toSeconds(),
                TimeUnit.SECONDS
        );
    }

    /**
     * Para o monitoramento periódico de ticks.
     * Invocado antes de cada reconexão para evitar verificações
     * durante o período em que a conexão está sendo restabelecida.
     */
    public void stopMonitoring() {
        if (monitorTask != null && !monitorTask.isCancelled()) {
            monitorTask.cancel(false);
        }
    }

    /**
     * Reseta o timestamp do último tick para {@code null}.
     * Invocado antes de cada reconexão para evitar que o timestamp
     * da sessão anterior seja usado como referência na nova conexão,
     * o que poderia suprimir a detecção de silêncio legítimo.
     */
    public void reset() {
        lastTickAt.set(null);
        log.debug("TICK HEARTBEAT | reset");
    }

    /**
     * Verifica se ticks estão chegando dentro do intervalo de timeout configurado.
     *
     * @return {@code true} se o último tick foi recebido há menos de {@link #TICK_TIMEOUT}
     */
    public boolean isAlive() {
        Instant last = lastTickAt.get();
        if (last == null) return false;
        return Duration.between(last, Instant.now()).compareTo(TICK_TIMEOUT) < 0;
    }

    /**
     * Retorna o timestamp do último tick recebido.
     *
     * @return {@link Instant} do último tick ou {@code null} se nenhum tick foi recebido ainda
     */
    public Instant getLastTickAt() {
        return lastTickAt.get();
    }

    // ═══════════════════════════════════════════════════════════════
    // Verificação periódica
    // ═══════════════════════════════════════════════════════════════

    /**
     * Executa uma verificação de chegada de ticks.
     * Quando nenhum tick foi recebido ainda, retorna silenciosamente,
     * pois pode ser o período inicial imediatamente após a subscrição.
     * Quando o silêncio exceder {@link #TICK_TIMEOUT}, aciona o callback
     * configurado em virtual thread para não bloquear o scheduler.
     */
    private void checkTickArrival() {
        Instant last = lastTickAt.get();

        if (last == null) {
            log.debug("TICK HEARTBEAT | no ticks received yet");
            return;
        }

        Duration silence = Duration.between(last, Instant.now());

        if (silence.compareTo(TICK_TIMEOUT) < 0) {
            log.debug("TICK HEARTBEAT | alive | last tick {}s ago",
                    silence.toSeconds());
            return;
        }

        log.warn("TICK HEARTBEAT | no ticks for {}min | possible connection issue",
                silence.toMinutes());

        // Executado em virtual thread para não bloquear o scheduler durante a reconexão
        Runnable callback = onTickTimeout;
        if (callback != null) {
            Thread.startVirtualThread(callback);
        }
    }
}