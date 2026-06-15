package com.github.dayviddouglas.TradingBot.deriv.trade.monitor;

import com.github.dayviddouglas.TradingBot.deriv.trade.context.TradeContext;
import com.github.dayviddouglas.TradingBot.model.Signal;

import java.time.Instant;
import java.util.List;

/**
 * Representa o estado operacional de um ativo durante o ciclo de vida de um trade.
 *
 * Encapsula duas responsabilidades complementares:
 * <ul>
 *   <li>Máquina de estados: controla a transição {@code IDLE → PENDING → OPEN → IDLE}
 *       via métodos synchronized, garantindo consistência entre a thread de envio
 *       e a thread de recebimento do WebSocket</li>
 *   <li>Contexto da operação: armazena todos os dados necessários para o
 *       {@link TradeMonitor} registrar o resultado no relatório ao fechar o contrato</li>
 * </ul>
 *
 * Os campos de estado e contexto são declarados como {@code volatile} para garantir
 * visibilidade entre threads, complementando a sincronização dos métodos de transição.
 */
public class TradeState {

    /** Símbolo do ativo associado a este estado; imutável após construção. */
    private final String symbol;

    private volatile TradeOperationState state = TradeOperationState.IDLE;

    /** ID do contrato aberto; nulo quando o estado for IDLE ou PENDING. */
    private volatile Long contractId;

    /** ID da subscrição do stream {@code proposal_open_contract}; usado no cancelamento. */
    private volatile String subscriptionId;

    // Campos do contexto da operação, preenchidos por applyContext() antes da execução
    private volatile double stake;
    private volatile String currency;
    private volatile int duration;
    private volatile String durationUnit;
    private volatile String decisionMode;
    private volatile String strategy;
    private volatile List<String> decisionStrategies = List.of();
    private volatile String regime;
    private volatile Signal.Type signalType = Signal.Type.NONE;

    /** Timestamp de abertura do contrato; utilizado para calcular a duração real da operação. */
    private volatile Instant entryTimestamp;

    /**
     * @param symbol símbolo do ativo associado a este estado
     */
    public TradeState(String symbol) {
        this.symbol = symbol;
    }

    // ═══════════════════════════════════════════════════════════════
    // Máquina de estados
    // ═══════════════════════════════════════════════════════════════

    /**
     * Verifica se o ativo está disponível para receber uma nova operação.
     *
     * @return {@code true} se o estado atual for {@link TradeOperationState#IDLE}
     */
    public synchronized boolean isIdle() {
        return state == TradeOperationState.IDLE;
    }

    /**
     * Transiciona o estado para {@link TradeOperationState#PENDING},
     * indicando que um proposal ou buy está em andamento.
     * Limpa {@code contractId} e {@code subscriptionId} de operações anteriores.
     */
    public synchronized void markPending() {
        this.state = TradeOperationState.PENDING;
        this.contractId = null;
        this.subscriptionId = null;
    }

    /**
     * Transiciona o estado para {@link TradeOperationState#OPEN} após compra confirmada,
     * registrando o ID do contrato aberto.
     *
     * @param contractId ID do contrato retornado pela API após o buy
     */
    public synchronized void markOpen(long contractId) {
        this.contractId = contractId;
        this.state = TradeOperationState.OPEN;
    }

    /**
     * Reseta o estado para {@link TradeOperationState#IDLE} após o fechamento do contrato.
     * Limpa {@code contractId} e {@code subscriptionId}, liberando o ativo para nova operação.
     */
    public synchronized void resetToIdle() {
        this.state = TradeOperationState.IDLE;
        this.contractId = null;
        this.subscriptionId = null;
    }

    /**
     * Retorna o estado operacional atual do ativo.
     *
     * @return estado atual
     */
    public synchronized TradeOperationState currentState() {
        return state;
    }

    // ═══════════════════════════════════════════════════════════════
    // Contexto da operação
    // ═══════════════════════════════════════════════════════════════

    /**
     * Popula todos os campos de contexto da operação a partir do {@link TradeContext}.
     * Registra o {@code entryTimestamp} com o instante atual de aplicação.
     * Chamado pelo {@link com.github.dayviddouglas.TradingBot.deriv.DerivTradeService}
     * imediatamente antes de iniciar a execução.
     *
     * @param context contexto completo da operação construído pelo {@code TradeContextFactory}
     */
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

    /**
     * Registra o ID da subscrição do stream {@code proposal_open_contract},
     * capturado pelo {@link TradeMonitor} a partir das mensagens recebidas via WebSocket.
     *
     * @param id ID da subscrição retornado pela API no campo {@code subscription.id}
     */
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