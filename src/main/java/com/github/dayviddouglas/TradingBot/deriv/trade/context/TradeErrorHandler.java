package com.github.dayviddouglas.TradingBot.deriv.trade.context;

import com.github.dayviddouglas.TradingBot.deriv.trade.monitor.TradeState;
import com.github.dayviddouglas.TradingBot.deriv.trade.validation.TradeValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Responsável por classificar e tratar erros ocorridos durante a execução de trades.
 *
 * Recebe a exceção capturada pelo {@link com.github.dayviddouglas.TradingBot.deriv.DerivTradeService},
 * classifica a mensagem de erro em uma das categorias conhecidas e aplica a ação correspondente.
 * Em todos os casos, reseta o {@link TradeState} do ativo para {@code IDLE} ao final do tratamento.
 *
 * Categorias de erro e ações associadas:
 * <ul>
 *   <li><b>PERMANENT</b>: bloqueia o símbolo via {@link TradeValidator#blockSymbol} e loga em {@code warn}</li>
 *   <li><b>SESSION</b>: sessão perdida ou não autorizada — loga em {@code warn}</li>
 *   <li><b>MARKET</b>: mercado fechado ou trading indisponível — loga em {@code warn}</li>
 *   <li><b>CRITICAL</b>: saldo insuficiente — loga em {@code error}</li>
 *   <li><b>TRANSIENT</b>: limite de contratos abertos atingido — loga em {@code warn}</li>
 *   <li><b>UNKNOWN</b>: erro não mapeado — loga mensagem da causa em {@code warn}, sem interromper o fluxo</li>
 * </ul>
 */
@Component
public class TradeErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(TradeErrorHandler.class);

    private final TradeValidator validator;

    /**
     * @param validator utilizado para bloquear símbolos em erros permanentes
     */
    public TradeErrorHandler(TradeValidator validator) {
        this.validator = validator;
    }

    /**
     * Classifica o erro pelo conteúdo da mensagem normalizada e aplica a ação correspondente.
     * Independentemente da categoria, o estado do ativo é resetado para {@code IDLE} ao final.
     *
     * @param state estado do ativo no momento da falha, usado para identificar o símbolo e resetar
     * @param error mensagem de erro normalizada em lowercase, usada para classificação
     * @param cause exceção original, utilizada para extração da mensagem em erros desconhecidos
     */
    public void handle(TradeState state, String error, Exception cause) {
        String symbol = state.getSymbol();

        if (isPermanentError(error)) {
            handlePermanentError(symbol, error);
        } else if (isSessionError(error)) {
            handleSessionError(symbol);
        } else if (isMarketClosedError(error)) {
            handleMarketClosedError(symbol);
        } else if (isCriticalError(error)) {
            handleCriticalError(symbol);
        } else if (isTransientError(error)) {
            handleTransientError(symbol);
        } else {
            handleUnknownError(symbol, error, cause);
        }

        state.resetToIdle();
    }

    // ═══════════════════════════════════════════════════════════════
    // Classificação de erros
    // ═══════════════════════════════════════════════════════════════

    /**
     * Identifica erros permanentes: contrato não ofertado para esta duração,
     * símbolo não encontrado ou símbolo inválido.
     * Erros desta categoria resultam no bloqueio permanente do símbolo.
     */
    private boolean isPermanentError(String error) {
        return error.contains("not offered for this duration")
                || error.contains("symbol not found")
                || error.contains("invalid symbol");
    }

    /**
     * Identifica erros de sessão expirada ou ausência de autenticação.
     */
    private boolean isSessionError(String error) {
        return error.contains("please log in");
    }

    /**
     * Identifica erros de mercado fechado ou trading temporariamente indisponível.
     */
    private boolean isMarketClosedError(String error) {
        return error.contains("market is closed")
                || error.contains("trading is not available");
    }

    /**
     * Identifica erros críticos de saldo insuficiente na conta.
     */
    private boolean isCriticalError(String error) {
        return error.contains("insufficient balance")
                || error.contains("not enough balance");
    }

    /**
     * Identifica erros transitórios de limite de contratos abertos atingido.
     */
    private boolean isTransientError(String error) {
        return error.contains("maximum number of contracts")
                || error.contains("too many open");
    }

    // ═══════════════════════════════════════════════════════════════
    // Tratamento por categoria
    // ═══════════════════════════════════════════════════════════════

    /**
     * Trata erro permanente bloqueando o símbolo via {@link TradeValidator#blockSymbol}
     * para evitar novas tentativas de operação neste ativo.
     */
    private void handlePermanentError(String symbol, String error) {
        log.warn("TRADE SKIPPED | symbol={} | reason=Permanent error, symbol will be blocked | detail={}",
                symbol, error);
        validator.blockSymbol(symbol);
    }

    /**
     * Trata erro de sessão perdida ou não autorizada.
     * O ativo permanece desbloqueado e o estado é resetado para IDLE.
     */
    private void handleSessionError(String symbol) {
        log.warn("TRADE FAILED | symbol={} | reason=Session lost / not authorized", symbol);
    }

    /**
     * Trata erro de mercado fechado ou trading temporariamente indisponível.
     * O ativo permanece desbloqueado e o estado é resetado para IDLE.
     */
    private void handleMarketClosedError(String symbol) {
        log.warn("TRADE SKIPPED | symbol={} | reason=Market is closed or trading not available",
                symbol);
    }

    /**
     * Trata erro crítico de saldo insuficiente.
     * Loga em nível {@code error} para destacar a necessidade de verificação da conta.
     */
    private void handleCriticalError(String symbol) {
        log.error("TRADE FAILED | symbol={} | reason=Insufficient balance. CHECK YOUR ACCOUNT!",
                symbol);
    }

    /**
     * Trata erro transitório de limite de contratos abertos atingido.
     * O ativo permanece desbloqueado; a próxima tentativa ocorrerá no próximo sinal.
     */
    private void handleTransientError(String symbol) {
        log.warn("TRADE SKIPPED | symbol={} | reason=Maximum open contracts reached. Waiting...",
                symbol);
    }

    /**
     * Trata erros não classificados pelas categorias anteriores.
     * Registra a mensagem da causa em {@code warn} sem interromper o fluxo operacional,
     * permitindo que o bot continue operando os demais ativos.
     */
    private void handleUnknownError(String symbol, String error, Exception cause) {
        String message = cause.getMessage();
        log.warn("TRADE FAILED | symbol={} | reason={}", symbol, message);
    }
}