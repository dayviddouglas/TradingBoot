package com.github.dayviddouglas.TradingBot.deriv.trade;

import com.github.dayviddouglas.TradingBot.config.StrategiesProfile;
import com.github.dayviddouglas.TradingBot.config.TradeConfig;
import com.github.dayviddouglas.TradingBot.model.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Responsável por validar se um sinal pode ser executado como trade.
 *
 * Aplica validações em camadas:
 * 1. Dados de entrada (profile, signal não nulos)
 * 2. Configuração de trade (habilitado no JSON)
 * 3. Bloqueio permanente de símbolo (duração não suportada, etc.)
 * 4. Estado operacional do ativo (deve estar IDLE)
 *
 * Extraída do DerivTradeService para respeitar SRP:
 * - DerivTradeService orquestra o fluxo
 * - TradeValidator decide se pode prosseguir
 */
@Component
public class TradeValidator {

    private static final Logger log = LoggerFactory.getLogger(TradeValidator.class);

    /**
     * Símbolos permanentemente bloqueados.
     * Uma vez adicionado, o ativo não tentará mais operar.
     * Exemplos: duração não suportada, símbolo inválido.
     */
    private final Set<String> blockedSymbols = ConcurrentHashMap.newKeySet();

    /**
     * Valida se um sinal pode prosseguir para execução.
     *
     * @param profile configuração do ativo
     * @param signal sinal final do engine
     * @param state estado operacional do ativo
     * @return resultado da validação com status e motivo
     */
    public ValidationResult validate(StrategiesProfile profile, Signal signal, TradeState state) {
        if (profile == null) {
            return skipWithDebug(null, "profile is null");
        }

        String symbol = profile.getSymbol();

        if (signal == null) {
            return skipWithDebug(symbol, "signal is null");
        }

        if (signal.getType() == Signal.Type.NONE) {
            return skipWithDebug(symbol, "signal is NONE");
        }

        TradeConfig trade = profile.getTrade();

        if (trade == null) {
            return skipWithDebug(symbol, "trade config absent");
        }

        if (!trade.isEnabled()) {
            return skipWithDebug(symbol, "trade disabled in profile");
        }

        if (blockedSymbols.contains(symbol)) {
            return skipWithDebug(symbol, "symbol permanently blocked");
        }

        if (!state.isIdle()) {
            log.info("TRADE IGNORE (already {}) | symbol={} | signal={}",
                    state.currentState(), symbol, signal.getType());
            return ValidationResult.ignore();
        }

        return ValidationResult.proceed();
    }

    /**
     * Bloqueia um símbolo permanentemente.
     * Chamado quando um erro estrutural é detectado (ex: duração não suportada).
     */
    public void blockSymbol(String symbol) {
        blockedSymbols.add(symbol);
        log.warn("SYMBOL BLOCKED PERMANENTLY | symbol={}", symbol);
    }

    public boolean isBlocked(String symbol) {
        return blockedSymbols.contains(symbol);
    }

    private ValidationResult skipWithDebug(String symbol, String reason) {
        log.debug("TRADE SKIP | symbol={} | reason={}", symbol != null ? symbol : "N/A", reason);
        return ValidationResult.skip(reason);
    }
}