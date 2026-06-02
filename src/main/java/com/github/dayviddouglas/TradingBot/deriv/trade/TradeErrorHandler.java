package com.github.dayviddouglas.TradingBot.deriv.trade;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Responsável por classificar e tratar erros de execução de trade.
 *
 * Classifica erros da API Deriv em categorias e decide a ação:
 * - PERMANENT: bloqueia o ativo permanentemente
 * - SESSION: requer reautenticação (reseta para IDLE)
 * - MARKET: mercado fechado (reseta para IDLE)
 * - CRITICAL: saldo insuficiente (log.error + reseta para IDLE)
 * - TRANSIENT: limite de contratos (reseta para IDLE)
 * - UNKNOWN: erro genérico (log.warn + reseta para IDLE)
 *
 * Extraído do DerivTradeService para respeitar SRP:
 * - DerivTradeService orquestra o fluxo
 * - TradeErrorHandler classifica e trata os erros
 */
@Component
public class TradeErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(TradeErrorHandler.class);

    private final TradeValidator validator;

    public TradeErrorHandler(TradeValidator validator) {
        this.validator = validator;
    }

    /**
     * Trata a exceção ocorrida durante a execução do trade.
     *
     * Classifica o erro e aplica a ação correspondente:
     * - Bloqueia o símbolo se for erro permanente
     * - Loga com nível adequado (warn ou error)
     * - Reseta o estado do ativo para IDLE em todos os casos
     *
     * @param state estado do ativo que falhou
     * @param error mensagem de erro normalizada (lowercase)
     * @param cause exceção original para log detalhado
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

    private boolean isPermanentError(String error) {
        return error.contains("not offered for this duration")
                || error.contains("symbol not found")
                || error.contains("invalid symbol");
    }

    private boolean isSessionError(String error) {
        return error.contains("please log in");
    }

    private boolean isMarketClosedError(String error) {
        return error.contains("market is closed")
                || error.contains("trading is not available");
    }

    private boolean isCriticalError(String error) {
        return error.contains("insufficient balance")
                || error.contains("not enough balance");
    }

    private boolean isTransientError(String error) {
        return error.contains("maximum number of contracts")
                || error.contains("too many open");
    }

    // ═══════════════════════════════════════════════════════════════
    // Tratamento por categoria
    // ═══════════════════════════════════════════════════════════════

    private void handlePermanentError(String symbol, String error) {
        log.warn("TRADE SKIPPED | symbol={} | reason=Permanent error, symbol will be blocked | detail={}",
                symbol, error);
        validator.blockSymbol(symbol);
    }

    private void handleSessionError(String symbol) {
        log.warn("TRADE FAILED | symbol={} | reason=Session lost / not authorized", symbol);
    }

    private void handleMarketClosedError(String symbol) {
        log.warn("TRADE SKIPPED | symbol={} | reason=Market is closed or trading not available",
                symbol);
    }

    private void handleCriticalError(String symbol) {
        log.error("TRADE FAILED | symbol={} | reason=Insufficient balance. CHECK YOUR ACCOUNT!",
                symbol);
    }

    private void handleTransientError(String symbol) {
        log.warn("TRADE SKIPPED | symbol={} | reason=Maximum open contracts reached. Waiting...",
                symbol);
    }

    private void handleUnknownError(String symbol, String error, Exception cause) {
        String message = cause.getMessage();
        log.warn("TRADE FAILED | symbol={} | reason={}", symbol, message);
    }
}