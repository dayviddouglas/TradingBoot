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
 * Monitora a chegada de ticks para confirmar que a conexão está funcional.
 *
 * Um WebSocket tecnicamente conectado pode estar sem receber dados
 * por falha na autorização, subscrição ou problema de rede silencioso.
 * O TickHeartbeat detecta essa situação rastreando o último tick recebido.
 *
 * Não bloqueia nenhuma thread: a verificação ocorre em background
 * via ScheduledExecutorService com intervalo configurado.
 */
@Component
public class TickHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(TickHeartbeat.class);

    /**
     * 3 minutos porque mercados fechados (fins de semana, feriados)
     * ficam longos períodos sem emitir ticks, e não devem ser
     * interpretados como falha de conexão.
     */
    private static final Duration TICK_TIMEOUT = Duration.ofMinutes(3);

    /**
     * 30 segundos porque mercados ativos emitem ticks com frequência
     * muito maior, tornando qualquer silêncio rapidamente detectável.
     */
    private static final Duration CHECK_INTERVAL = Duration.ofSeconds(30);

    /**
     * AtomicReference garante visibilidade do timestamp entre a thread
     * do WebSocket (que grava) e a thread do heartbeat (que lê).
     */
    private final AtomicReference<Instant> lastTickAt = new AtomicReference<>();

    /**
     * Thread daemon para não impedir o shutdown da JVM
     * quando a aplicação for encerrada normalmente.
     */
    private final ScheduledExecutorService scheduler = Executors
            .newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "tick-heartbeat");
                t.setDaemon(true);
                return t;
            });

    private ScheduledFuture<?> monitorTask;
    private Runnable onTickTimeout;

    /**
     * Registra o recebimento de um tick.
     * Chamado pelo DerivMarketDataService a cada tick recebido.
     */
    public void recordTick() {
        lastTickAt.set(Instant.now());
    }

    /**
     * Registra callback chamado quando o timeout de ticks expira.
     *
     * @param callback ação a executar quando ticks pararem de chegar
     */
    public void onTickTimeout(Runnable callback) {
        this.onTickTimeout = callback;
    }

    /**
     * Inicia o monitoramento periódico em background.
     * Chamado após cada conexão bem-sucedida.
     *
     * stopMonitoring() é chamado antes para cancelar qualquer
     * tarefa anterior e evitar monitoramentos duplicados.
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
     * Para o monitoramento de ticks.
     * Chamado antes de cada reconexão para evitar verificações
     * durante o período em que a conexão está sendo restabelecida.
     */
    public void stopMonitoring() {
        if (monitorTask != null && !monitorTask.isCancelled()) {
            monitorTask.cancel(false);
        }
    }

    /**
     * Reseta o timestamp do último tick para null.
     * Necessário antes de cada reconexão para evitar que o timestamp
     * da conexão anterior seja usado como referência na nova conexão.
     */
    public void reset() {
        lastTickAt.set(null);
        log.debug("TICK HEARTBEAT | reset");
    }

    /**
     * Verifica se ticks estão chegando dentro do timeout configurado.
     *
     * @return true se a conexão está recebendo ticks ativamente
     */
    public boolean isAlive() {
        Instant last = lastTickAt.get();
        if (last == null) return false;
        return Duration.between(last, Instant.now()).compareTo(TICK_TIMEOUT) < 0;
    }

    /**
     * Retorna o timestamp do último tick recebido.
     *
     * @return Instant do último tick ou null se nenhum tick foi recebido
     */
    public Instant getLastTickAt() {
        return lastTickAt.get();
    }

    // ═══════════════════════════════════════════════════════════════
    // Verificação periódica
    // ═══════════════════════════════════════════════════════════════

    /**
     * Verifica se ticks estão chegando e aciona o callback se necessário.
     *
     * Retorna silenciosamente se nenhum tick foi recebido ainda,
     * pois pode ser o período inicial logo após a subscrição.
     *
     * O callback é executado em virtual thread para não bloquear
     * o scheduler e permitir que as próximas verificações ocorram
     * normalmente enquanto a reconexão é processada.
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

        Runnable callback = onTickTimeout;
        if (callback != null) {
            Thread.startVirtualThread(callback);
        }
    }
}