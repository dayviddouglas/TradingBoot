package com.github.dayviddouglas.TradingBot.deriv.trade.monitor;

import com.github.dayviddouglas.TradingBot.deriv.trade.context.TradeContext;
import com.github.dayviddouglas.TradingBot.model.Signal;

import java.time.Instant;
import java.util.List;

/**
 * Estado operacional de um ativo durante o ciclo de vida de um trade.
 *
 * Encapsula:
 * - Máquina de estados (IDLE → PENDING → OPEN → IDLE)
 * - Contexto completo da operação para uso no relatório de fechamento
 *
 * Todos os acessos ao state são synchronized para garantir
 * consistência entre a thread de envio (virtual thread) e
 * a thread de recebimento do WebSocket.
 */
public class TradeState {

    private final String symbol;

    private volatile TradeOperationState state = TradeOperationState.IDLE;
    private volatile Long contractId;
    private volatile String subscriptionId;

    // Contexto da operação (preenchido antes da execução)
    private volatile double stake;
    private volatile String currency;
    private volatile int duration;
    private volatile String durationUnit;
    private volatile String decisionMode;
    private volatile String strategy;
    private volatile List<String> decisionStrategies = List.of();
    private volatile String regime;
    private volatile Signal.Type signalType = Signal.Type.NONE;
    private volatile Instant entryTimestamp;

    public TradeState(String symbol) {
        this.symbol = symbol;
    }

    // ═══════════════════════════════════════════════════════════════
    // Máquina de estados
    // ═══════════════════════════════════════════════════════════════

    public synchronized boolean isIdle() {
        return state == TradeOperationState.IDLE;
    }

    public synchronized void markPending() {
        this.state = TradeOperationState.PENDING;
        this.contractId = null;
        this.subscriptionId = null;
    }

    public synchronized void markOpen(long contractId) {
        this.contractId = contractId;
        this.state = TradeOperationState.OPEN;
    }

    public synchronized void resetToIdle() {
        this.state = TradeOperationState.IDLE;
        this.contractId = null;
        this.subscriptionId = null;
    }

    public synchronized TradeOperationState currentState() {
        return state;
    }

    // ═══════════════════════════════════════════════════════════════
    // Contexto da operação
    // ═══════════════════════════════════════════════════════════════

    public synchronized void applyContext(TradeContext context) {
        this.stake = context.amount();
        this.currency = context.currency();
        this.duration = context.duration();
        this.durationUnit = context.durationUnit();
        this.decisionMode = context.decisionMode();
        this.strategy = context.strategy();
        this.decisionStrategies = context.decisionStrategies();
        this.regime = context.regime();
        this.signalType = context.signalType();
        this.entryTimestamp = Instant.now();
    }

    // ═══════════════════════════════════════════════════════════════
    // Getters
    // ═══════════════════════════════════════════════════════════════

    public String getSymbol() { return symbol; }
    public Long getContractId() { return contractId; }
    public String getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(String id) { this.subscriptionId = id; }
    public double getStake() { return stake; }
    public String getCurrency() { return currency; }
    public int getDuration() { return duration; }
    public String getDurationUnit() { return durationUnit; }
    public String getDecisionMode() { return decisionMode; }
    public String getStrategy() { return strategy; }
    public List<String> getDecisionStrategies() { return decisionStrategies; }
    public String getRegime() { return regime; }
    public Signal.Type getSignalType() { return signalType; }
    public Instant getEntryTimestamp() { return entryTimestamp; }
}
