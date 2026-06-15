package com.github.dayviddouglas.TradingBot.engine.regime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Armazena e fornece acesso em O(1) ao regime de mercado confirmado por ativo.
 *
 * Apenas regimes que passaram pelo filtro de persistência do {@link RegimeStateTracker}
 * (3 avaliações consecutivas = 45 minutos de confirmação) são registrados aqui.
 * Regimes candidatos ou provisórios nunca são armazenados neste registry.
 *
 * Desacopla o pipeline de classificação ({@link MarketRegimeMonitor} →
 * {@link RegimeStateTracker}) do pipeline de execução
 * ({@link com.github.dayviddouglas.TradingBot.deriv.DerivTradeService}):
 * o {@code DerivTradeService} não precisa conhecer o histórico de classificações,
 * consultando apenas o estado atual confirmado via {@link #getRegime}.
 *
 * O padrão de acesso é escritas pelo {@link RegimeStateTracker} em virtual thread do
 * {@link com.github.dayviddouglas.TradingBot.engine.core.StrategyEngine} e leituras
 * pelo {@code DerivTradeService} em virtual thread de execução de trade.
 * {@link ConcurrentHashMap} é suficiente para esse padrão, pois não há operações
 * compostas que necessitem de lock explícito.
 */
@Component
public class RegimeRegistry {

    private static final Logger log = LoggerFactory.getLogger(RegimeRegistry.class);

    /**
     * Regime padrão retornado quando nenhuma classificação foi confirmada para o ativo.
     * {@code CHOPPY} é o valor conservador: na ausência de informação sobre o regime,
     * o sistema assume o pior caso para evitar decisões baseadas em regime desconhecido.
     */
    private static final MarketRegime DEFAULT_REGIME = MarketRegime.CHOPPY;

    /**
     * Mapa de regime confirmado por símbolo.
     * Chave: símbolo do ativo (ex: {@code "frxEURUSD"}).
     * Valor: último regime confirmado pelo {@link RegimeStateTracker}.
     */
    private final Map<String, MarketRegime> confirmedRegimeBySymbol =
            new ConcurrentHashMap<>();

    /**
     * Atualiza o regime confirmado de um ativo e registra a transição em log operacional.
     * Chamado pelo {@link RegimeStateTracker} após confirmação pelo filtro de persistência.
     * Não deve ser chamado com regimes candidatos não confirmados.
     *
     * @param symbol símbolo do ativo
     * @param regime novo regime confirmado a ser registrado
     */
    public void updateRegime(String symbol, MarketRegime regime) {
        MarketRegime previous = confirmedRegimeBySymbol.put(symbol, regime);

        if (previous == null) {
            log.info("REGIME REGISTRY INITIAL | symbol={} | regime={}",
                    symbol, regime);
        } else if (previous != regime) {
            log.info("REGIME REGISTRY UPDATED | symbol={} | {} → {}",
                    symbol, previous, regime);
        }
    }

    /**
     * Retorna o regime confirmado atual para o ativo informado.
     * Retorna {@link MarketRegime#CHOPPY} quando nenhuma classificação foi confirmada ainda.
     *
     * @param symbol símbolo do ativo
     * @return regime confirmado atual ou {@link #DEFAULT_REGIME} se não houver classificação
     */
    public MarketRegime getRegime(String symbol) {
        return confirmedRegimeBySymbol.getOrDefault(symbol, DEFAULT_REGIME);
    }

    /**
     * Verifica se já existe regime confirmado para o ativo.
     * Permite distinguir entre "regime {@code CHOPPY} confirmado" e
     * "nenhuma classificação confirmada ainda" durante o período de warm-up.
     *
     * @param symbol símbolo do ativo
     * @return {@code true} se pelo menos uma classificação foi confirmada para o ativo
     */
    public boolean hasConfirmedRegime(String symbol) {
        return confirmedRegimeBySymbol.containsKey(symbol);
    }

    /**
     * Remove o regime confirmado de um ativo do registry.
     * Utilizado em testes ou cenários de reset de estado.
     *
     * @param symbol símbolo do ativo a ser removido
     */
    public void clearRegime(String symbol) {
        confirmedRegimeBySymbol.remove(symbol);
    }

    /**
     * Atualiza o regime confirmado de um ativo sem gerar log de transição.
     * Utilizado exclusivamente pelo {@link MarketRegimeMonitor} durante o warm-up histórico
     * para suprimir logs de transições intermediárias que não representam eventos reais de mercado.
     * O regime final confirmado ao término do warm-up é logado pelo
     * {@link com.github.dayviddouglas.TradingBot.engine.core.StrategyEngine}.
     *
     * No fluxo de runtime, utilizar {@link #updateRegime} para manter rastreabilidade
     * operacional completa nos logs.
     *
     * @param symbol símbolo do ativo
     * @param regime novo regime confirmado a ser registrado silenciosamente
     */
    public void updateRegimeSilently(String symbol, MarketRegime regime) {
        confirmedRegimeBySymbol.put(symbol, regime);
    }
}