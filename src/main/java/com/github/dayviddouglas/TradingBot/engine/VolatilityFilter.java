package com.github.dayviddouglas.TradingBot.engine;

import com.github.dayviddouglas.TradingBot.model.Bar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Filtro de volatilidade que impede operações quando o mercado está muito parado.
 *
 * Compara o range (high - low) do candle atual com o range médio dos últimos
 * N candles. Se o range atual for menor que avgRange × rangeMultiplier,
 * o candle é considerado sem movimento suficiente para operar.
 *
 * Isso protege contra sinais gerados em períodos de baixa liquidez
 * (madrugada, feriados, etc.), onde os spreads podem ser ruins e os
 * movimentos erráticos.
 *
 * Os parâmetros são configuráveis via strategies.json no bloco "engine":
 * {
 *   "engine": {
 *     "maxBars": 1500,
 *     "decisionMode": "VOTING",
 *     "rangeLookback": 14,
 *     "rangeMultiplier": 1.10
 *   }
 * }
 *
 * Classe extraída do StrategyEngine para:
 * - Respeitar SRP (Single Responsibility Principle)
 * - Permitir reutilização em outros contextos (ex: backtest)
 * - Facilitar testes unitários isolados
 * - Permitir evolução independente da lógica de filtro
 *
 * ⚠️ Ponto de atenção: Os valores padrão (rangeLookback=14, rangeMultiplier=1.10)
 * são os mesmos que estavam hardcoded no StrategyEngine, garantindo compatibilidade
 * retroativa com profiles que não configuram esses campos.
 */
public class VolatilityFilter {

    private static final Logger log = LoggerFactory.getLogger(VolatilityFilter.class);

    /**
     * Valor padrão do rangeLookback.
     * Mantém compatibilidade com profiles que não configuram este campo.
     */
    private static final int DEFAULT_RANGE_LOOKBACK = 14;

    /**
     * Valor padrão do rangeMultiplier.
     * Mantém compatibilidade com profiles que não configuram este campo.
     */
    private static final double DEFAULT_RANGE_MULTIPLIER = 1.10;

    /**
     * Quantidade de candles usados para calcular o range médio.
     * Padrão: 14 (valor clássico para ATR e filtros de volatilidade).
     * Configurável via strategies.json campo "rangeLookback".
     */
    private final int rangeLookback;

    /**
     * Multiplicador de volatilidade mínima.
     * O range atual deve ser >= avgRange × rangeMultiplier para operar.
     * Padrão: 1.10 (candle atual precisa de pelo menos 110% do range médio).
     * Configurável via strategies.json campo "rangeMultiplier".
     */
    private final double rangeMultiplier;

    /**
     * Construtor com valores padrão.
     * Mantém compatibilidade retroativa com engines que não configuram o filtro.
     */
    public VolatilityFilter() {
        this(DEFAULT_RANGE_LOOKBACK, DEFAULT_RANGE_MULTIPLIER);
    }

    /**
     * Construtor com parâmetros configuráveis.
     *
     * Valores inválidos (zero ou negativos) são substituídos pelos padrões,
     * garantindo que o filtro nunca opere com configuração absurda.
     *
     * @param rangeLookback quantidade de candles para calcular range médio (> 0)
     * @param rangeMultiplier multiplicador mínimo de volatilidade (> 0)
     */
    public VolatilityFilter(int rangeLookback, double rangeMultiplier) {
        this.rangeLookback = rangeLookback > 0 ? rangeLookback : DEFAULT_RANGE_LOOKBACK;
        this.rangeMultiplier = rangeMultiplier > 0 ? rangeMultiplier : DEFAULT_RANGE_MULTIPLIER;
    }

    /**
     * Verifica se a volatilidade atual é suficiente para operar.
     *
     * Calcula o range médio dos últimos rangeLookback candles e compara
     * com o range do candle atual. Se o candle atual não atingir o
     * threshold mínimo, o mercado é considerado parado.
     *
     * @param snapshot cópia imutável dos candles atuais
     * @param symbol símbolo do ativo para log de diagnóstico
     * @return true se a volatilidade é aceitável para operar
     */
    public boolean isAcceptable(List<Bar> snapshot, String symbol) {
        if (snapshot == null || snapshot.isEmpty()) return false;

        Bar last = snapshot.get(snapshot.size() - 1);
        double currentRange = computeRange(last);
        double avgRange = computeAverageRange(snapshot);

        boolean ok = Double.isFinite(avgRange) && currentRange >= avgRange * rangeMultiplier;

        if (!ok) {
            log.debug("Volatility filter blocked | symbol={} | time={} | range={} avgRange={} mult={}",
                    symbol, last.timestamp(), currentRange, avgRange, rangeMultiplier);
        }

        return ok;
    }

    /**
     * Retorna o range (high - low) do último candle do snapshot.
     *
     * @param snapshot lista de candles
     * @return range do último candle ou 0.0 se snapshot vazio
     */
    public double getCurrentRange(List<Bar> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) return 0.0;
        return computeRange(snapshot.get(snapshot.size() - 1));
    }

    /**
     * Retorna o range médio dos últimos rangeLookback candles.
     *
     * @param snapshot lista de candles
     * @return range médio ou NaN se dados insuficientes
     */
    public double getAverageRange(List<Bar> snapshot) {
        return computeAverageRange(snapshot);
    }

    /** Retorna o rangeLookback configurado */
    public int getRangeLookback() { return rangeLookback; }

    /** Retorna o rangeMultiplier configurado */
    public double getRangeMultiplier() { return rangeMultiplier; }

    /**
     * Calcula o range (high - low) de um candle.
     *
     * @param bar candle a calcular
     * @return range do candle
     */
    private double computeRange(Bar bar) {
        return bar.high() - bar.low();
    }

    /**
     * Calcula o range médio (high - low) dos últimos rangeLookback candles.
     *
     * @param bars lista de candles
     * @return range médio ou NaN se dados insuficientes
     */
    private double computeAverageRange(List<Bar> bars) {
        if (bars == null || bars.size() < rangeLookback) return Double.NaN;

        double sum = 0.0;
        for (int i = bars.size() - rangeLookback; i < bars.size(); i++) {
            sum += computeRange(bars.get(i));
        }
        return sum / rangeLookback;
    }
}