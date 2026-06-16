package com.github.dayviddouglas.TradingBoot.engine.filter;

import com.github.dayviddouglas.TradingBoot.model.Bar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Filtro de volatilidade que impede operações quando o mercado está com movimento insuficiente.
 *
 * Compara o range ({@code high - low}) do candle mais recente com o range médio dos últimos
 * {@code rangeLookback} candles. Quando o range atual for inferior a
 * {@code avgRange × rangeMultiplier}, o candle é considerado sem volatilidade suficiente
 * para operar, protegendo contra sinais gerados em períodos de baixa liquidez.
 *
 * Os parâmetros são configuráveis por ativo via strategies.json no bloco {@code engine}:
 * <pre>
 * {
 *   "engine": {
 *     "rangeLookback": 14,
 *     "rangeMultiplier": 1.10
 *   }
 * }
 * </pre>
 *
 * Quando os campos não estão configurados, os valores padrão
 * ({@code rangeLookback=14}, {@code rangeMultiplier=1.10}) são aplicados,
 * mantendo compatibilidade retroativa com profiles existentes.
 */
public class VolatilityFilter {

    private static final Logger log = LoggerFactory.getLogger(VolatilityFilter.class);

    /** Valor padrão do lookback para cálculo do range médio. */
    private static final int    DEFAULT_RANGE_LOOKBACK   = 14;

    /** Valor padrão do multiplicador mínimo de volatilidade. */
    private static final double DEFAULT_RANGE_MULTIPLIER = 1.10;

    /**
     * Quantidade de candles utilizados para calcular o range médio de referência.
     * Configurável via strategies.json; substituído pelo padrão quando inválido.
     */
    private final int rangeLookback;

    /**
     * Multiplicador aplicado sobre o range médio para definir o limiar mínimo.
     * O range atual deve ser {@code >= avgRange × rangeMultiplier} para operar.
     * Configurável via strategies.json; substituído pelo padrão quando inválido.
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
     * Valores zero ou negativos são substituídos pelos padrões,
     * garantindo que o filtro nunca opere com configuração inválida.
     *
     * @param rangeLookback   quantidade de candles para o cálculo do range médio; deve ser positivo
     * @param rangeMultiplier multiplicador mínimo de volatilidade; deve ser positivo
     */
    public VolatilityFilter(int rangeLookback, double rangeMultiplier) {
        this.rangeLookback   = rangeLookback   > 0 ? rangeLookback   : DEFAULT_RANGE_LOOKBACK;
        this.rangeMultiplier = rangeMultiplier > 0 ? rangeMultiplier : DEFAULT_RANGE_MULTIPLIER;
    }

    /**
     * Verifica se a volatilidade do candle mais recente é suficiente para operar.
     * Calcula o range médio dos últimos {@code rangeLookback} candles e compara
     * com o range do último candle. Registra log de diagnóstico quando bloqueado.
     *
     * @param snapshot cópia imutável dos candles atuais; não pode ser nulo ou vazio
     * @param symbol   símbolo do ativo, utilizado apenas para o log de diagnóstico
     * @return {@code true} se o range atual atingir o limiar mínimo configurado
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
     * Retorna o range ({@code high - low}) do último candle do snapshot.
     * Utilizado pelo {@link com.github.dayviddouglas.TradingBoot.engine.core.SignalEmitter}
     * para incluir na metadata do sinal final.
     *
     * @param snapshot lista de candles; retorna {@code 0.0} se nula ou vazia
     * @return range do último candle
     */
    public double getCurrentRange(List<Bar> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) return 0.0;
        return computeRange(snapshot.get(snapshot.size() - 1));
    }

    /**
     * Retorna o range médio dos últimos {@code rangeLookback} candles.
     * Utilizado pelo {@link com.github.dayviddouglas.TradingBoot.engine.core.SignalEmitter}
     * para incluir na metadata do sinal final.
     *
     * @param snapshot lista de candles
     * @return range médio ou {@code NaN} se há menos candles que {@code rangeLookback}
     */
    public double getAverageRange(List<Bar> snapshot) {
        return computeAverageRange(snapshot);
    }

    /**
     * Retorna o {@code rangeLookback} configurado.
     *
     * @return número de candles utilizados no cálculo do range médio
     */
    public int getRangeLookback() { return rangeLookback; }

    /**
     * Retorna o {@code rangeMultiplier} configurado.
     *
     * @return multiplicador mínimo de volatilidade aplicado sobre o range médio
     */
    public double getRangeMultiplier() { return rangeMultiplier; }

    /**
     * Calcula o range ({@code high - low}) de um candle individual.
     *
     * @param bar candle a ser calculado
     * @return diferença entre {@code high} e {@code low}
     */
    private double computeRange(Bar bar) {
        return bar.high() - bar.low();
    }

    /**
     * Calcula o range médio dos últimos {@code rangeLookback} candles da lista.
     * Retorna {@code NaN} quando há menos candles disponíveis que o lookback configurado.
     *
     * @param bars lista de candles; pode ser nula
     * @return média dos ranges ou {@code NaN} se dados insuficientes
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