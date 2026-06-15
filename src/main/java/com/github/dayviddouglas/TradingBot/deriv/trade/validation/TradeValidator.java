package com.github.dayviddouglas.TradingBot.deriv.trade.validation;

import com.github.dayviddouglas.TradingBot.config.strategy.StrategiesProfile;
import com.github.dayviddouglas.TradingBot.config.strategy.TradeConfig;
import com.github.dayviddouglas.TradingBot.deriv.trade.monitor.TradeState;
import com.github.dayviddouglas.TradingBot.model.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Responsável por validar se um sinal pode ser executado como trade.
 *
 * Aplica validações em camadas sequenciais, retornando imediatamente
 * ao encontrar a primeira violação:
 * <ol>
 *   <li>Dados de entrada: {@code profile} e {@code signal} não nulos</li>
 *   <li>Tipo do sinal: deve ser diferente de {@link Signal.Type#NONE}</li>
 *   <li>Configuração de trade: bloco {@code trade} presente e {@code enabled: true}</li>
 *   <li>Bloqueio permanente: símbolo não deve constar na lista de bloqueados</li>
 *   <li>Estado operacional: o ativo deve estar em {@link com.github.dayviddouglas.TradingBot.deriv.trade.monitor.TradeOperationState#IDLE}</li>
 * </ol>
 *
 * Símbolos são bloqueados permanentemente via {@link #blockSymbol} quando erros
 * estruturais são detectados pelo {@link com.github.dayviddouglas.TradingBot.deriv.trade.context.TradeErrorHandler},
 * como contrato não ofertado para a duração configurada ou símbolo inválido.
 * A lista de bloqueados pode ser limpa via {@link #cleanListBlockedSymbols}
 * para retomada de operações após manutenções da plataforma.
 */
@Component
public class TradeValidator {

    private static final Logger log = LoggerFactory.getLogger(TradeValidator.class);

    /**
     * Conjunto thread-safe de símbolos permanentemente bloqueados.
     * Um símbolo bloqueado não tentará mais operar até que a lista seja limpa.
     */
    private final Set<String> blockedSymbols = ConcurrentHashMap.newKeySet();

    /**
     * Valida se um sinal pode prosseguir para execução, aplicando as cinco camadas
     * de validação em sequência. O resultado indica se o fluxo deve prosseguir,
     * ser ignorado silenciosamente ou ser descartado com log de debug.
     *
     * @param profile configuração do ativo lida do strategies.json
     * @param signal  sinal final emitido pelo engine
     * @param state   estado operacional atual do ativo
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

        // Quando o ativo não está IDLE, o sinal é ignorado sem log de debug
        // para evitar poluição de logs durante operações em andamento
        if (!state.isIdle()) {
            log.info("TRADE IGNORE (already {}) | symbol={} | signal={}",
                    state.currentState(), symbol, signal.getType());
            return ValidationResult.ignore();
        }

        return ValidationResult.proceed();
    }

    /**
     * Bloqueia um símbolo permanentemente, impedindo novas tentativas de operação.
     * Chamado pelo {@link com.github.dayviddouglas.TradingBot.deriv.trade.context.TradeErrorHandler}
     * quando um erro estrutural é detectado, como duração não suportada ou símbolo inválido.
     *
     * @param symbol símbolo do ativo a ser bloqueado
     */
    public void blockSymbol(String symbol) {
        blockedSymbols.add(symbol);
        log.warn("SYMBOL BLOCKED PERMANENTLY | symbol={}", symbol);
    }

    /**
     * Verifica se um símbolo está na lista de bloqueados.
     *
     * @param symbol símbolo do ativo a ser verificado
     * @return {@code true} se o símbolo estiver permanentemente bloqueado
     */
    public boolean isBlocked(String symbol) {
        return blockedSymbols.contains(symbol);
    }

    /**
     * Remove todos os símbolos da lista de bloqueados.
     * Utilizado para retomar operações em ativos bloqueados após manutenções
     * ou indisponibilidades temporárias da plataforma Deriv.
     */
    public void cleanListBlockedSymbols(){
        blockedSymbols.clear();
    }

    /**
     * Descarta o sinal registrando o motivo em nível {@code debug}.
     *
     * @param symbol símbolo do ativo; exibido como {@code "N/A"} quando nulo
     * @param reason motivo do descarte para rastreabilidade nos logs
     * @return resultado com status SKIP e o motivo informado
     */
    private ValidationResult skipWithDebug(String symbol, String reason) {
        log.debug("TRADE SKIP | symbol={} | reason={}", symbol != null ? symbol : "N/A", reason);
        return ValidationResult.skip(reason);
    }
}