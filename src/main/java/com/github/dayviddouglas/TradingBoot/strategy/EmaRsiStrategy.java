package com.github.dayviddouglas.TradingBoot.strategy;

import com.github.dayviddouglas.TradingBoot.model.Bar;
import com.github.dayviddouglas.TradingBoot.model.Signal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Estratégia de confirmação de tendência via cruzamento de EMAs com filtro de RSI.
 *
 * Avalia a tendência pelo posicionamento relativo entre EMA rápida e EMA lenta
 * e usa o RSI como filtro de confirmação de momentum:
 * <ul>
 *   <li>{@code BUY}: EMA rápida acima da lenta (tendência de alta) E RSI {@code >= rsiBuyThreshold}</li>
 *   <li>{@code SELL}: EMA rápida abaixo da lenta (tendência de baixa) E RSI {@code <= rsiSellThreshold}</li>
 * </ul>
 *
 * Filtro anti-chop: a distância entre as EMAs deve ser maior que
 * {@code emaDistanceFactor × avgRange} dos últimos 14 candles. Quando as EMAs estão próximas,
 * cruzamentos tendem a ser ruído de mercado lateral e não tendência real.
 *
 * A EMA é calculada sobre toda a série histórica disponível (não apenas uma janela móvel),
 * produzindo valores mais estáveis. O RSI utiliza o smoothing de Wilder (média exponencial).
 */
public class EmaRsiStrategy implements TradingStrategy {

    /** Período da EMA rápida; deve ser menor que {@code emaSlow}. */
    private final int emaFast;

    /** Período da EMA lenta; referência de direção de médio prazo. */
    private final int emaSlow;

    /** Período do RSI de Wilder para confirmação de momentum. */
    private final int rsiPeriod;

    /**
     * Threshold de RSI para confirmar compra.
     * RSI {@code >=} este valor indica momentum de alta suficiente.
     */
    private final double rsiBuyThreshold;

    /**
     * Threshold de RSI para confirmar venda.
     * RSI {@code <=} este valor indica momentum de baixa suficiente.
     */
    private final double rsiSellThreshold;

    /** Período para calcular o range médio dos candles no filtro anti-chop. */
    private final int rangeLookback = 14;

    /**
     * Fator multiplicador da distância mínima entre as EMAs.
     * A distância {@code |emaFast - emaSlow|} deve superar {@code emaDistanceFactor × avgRange}
     * para que o sinal seja gerado.
     */
    private final double emaDistanceFactor = 0.5;

    /**
     * @param emaFast          período da EMA rápida; mínimo 2 e deve ser menor que {@code emaSlow}
     * @param emaSlow          período da EMA lenta; mínimo 2
     * @param rsiPeriod        período do RSI; mínimo 2
     * @param rsiBuyThreshold  nível mínimo de RSI para confirmar BUY
     * @param rsiSellThreshold nível máximo de RSI para confirmar SELL
     * @throws IllegalArgumentException se os períodos forem inválidos ou {@code emaFast >= emaSlow}
     */
    public EmaRsiStrategy(int emaFast, int emaSlow, int rsiPeriod,
                          double rsiBuyThreshold, double rsiSellThreshold) {
        if (emaFast < 2 || emaSlow < 2 || rsiPeriod < 2)
            throw new IllegalArgumentException("Periods must be >= 2");
        if (emaFast >= emaSlow)
            throw new IllegalArgumentException("emaFast must be < emaSlow");

        this.emaFast          = emaFast;
        this.emaSlow          = emaSlow;
        this.rsiPeriod        = rsiPeriod;
        this.rsiBuyThreshold  = rsiBuyThreshold;
        this.rsiSellThreshold = rsiSellThreshold;
    }

    /**
     * Identificador da estratégia utilizado em logs, metadata do sinal
     * e no {@link com.github.dayviddouglas.TradingBoot.engine.confluence.StrategyWeightProfile}.
     */
    @Override
    public String name() {
        return "EmaRsiStrategy";
    }

    /**
     * Avalia a tendência e retorna sinal de compra, venda ou ausência.
     *
     * Fluxo:
     * <ol>
     *   <li>Calcula EMA rápida e lenta sobre toda a série histórica</li>
     *   <li>Calcula RSI com smoothing de Wilder</li>
     *   <li>Aplica filtro anti-chop: distância entre EMAs vs range médio</li>
     *   <li>EMAs muito próximas → NONE</li>
     *   <li>EMA rápida acima da lenta e RSI confirma → BUY</li>
     *   <li>EMA rápida abaixo da lenta e RSI confirma → SELL</li>
     * </ol>
     *
     * @param bars lista de candles para análise
     * @return {@link Signal} com tipo, timestamp, preço e metadata dos indicadores
     */
    @Override
    public Signal checkSignal(List<Bar> bars) {
        if (bars == null) return Signal.none(name());

        int minBars = Math.max(emaSlow + 10, Math.max(rsiPeriod + 1, rangeLookback));
        if (bars.size() < minBars) return Signal.none(name());

        Bar last = bars.get(bars.size() - 1);

        double emaFastVal = emaCloseCorrect(bars, emaFast);
        double emaSlowVal = emaCloseCorrect(bars, emaSlow);
        double rsiVal     = rsiWilder(bars, rsiPeriod);

        if (!Double.isFinite(emaFastVal) || !Double.isFinite(emaSlowVal)
                || !Double.isFinite(rsiVal)) {
            return Signal.none(name());
        }

        // Filtro anti-chop: EMAs coladas indicam mercado lateral, não tendência
        double  avgRange      = averageRange(bars, rangeLookback);
        double  emaDistance   = Math.abs(emaFastVal - emaSlowVal);
        boolean trendStrengthOk = Double.isFinite(avgRange)
                && emaDistance > (avgRange * emaDistanceFactor);

        if (!trendStrengthOk) {
            return Signal.none(name());
        }

        boolean upTrend   = emaFastVal > emaSlowVal;
        boolean downTrend = emaFastVal < emaSlowVal;

        Map<String, Object> meta = new HashMap<>();
        meta.put("emaFast",           emaFast);
        meta.put("emaSlow",           emaSlow);
        meta.put("rsiPeriod",         rsiPeriod);
        meta.put("emaFastVal",        emaFastVal);
        meta.put("emaSlowVal",        emaSlowVal);
        meta.put("rsiVal",            rsiVal);
        meta.put("rsiBuyThreshold",   rsiBuyThreshold);
        meta.put("rsiSellThreshold",  rsiSellThreshold);
        meta.put("avgRange",          avgRange);
        meta.put("emaDistance",       emaDistance);
        meta.put("emaDistanceFactor", emaDistanceFactor);

        // BUY: tendência de alta confirmada por momentum de RSI
        if (upTrend && rsiVal >= rsiBuyThreshold) {
            return Signal.buy(name(), last.timestamp(), last.close(), meta);
        }

        // SELL: tendência de baixa confirmada por momentum de RSI
        if (downTrend && rsiVal <= rsiSellThreshold) {
            return Signal.sell(name(), last.timestamp(), last.close(), meta);
        }

        return Signal.none(name());
    }

    // ═══════════════════════════════════════════════════════════════
    // Indicadores técnicos
    // ═══════════════════════════════════════════════════════════════

    /**
     * Calcula a EMA sobre toda a série histórica disponível.
     * Inicializa com a SMA dos primeiros {@code period} candles (seed) e aplica
     * suavização exponencial com fator {@code k = 2 / (period + 1)} até o último candle.
     *
     * @param bars   lista completa de candles
     * @param period período da EMA
     * @return valor atual da EMA ou {@code NaN} se dados insuficientes
     */
    private static double emaCloseCorrect(List<Bar> bars, int period) {
        if (bars.size() < period) return Double.NaN;

        double k   = 2.0 / (period + 1.0);
        double ema = 0.0;

        // Seed com SMA dos primeiros N candles
        for (int i = 0; i < period; i++) {
            ema += bars.get(i).close();
        }
        ema /= period;

        // Suavização exponencial sobre o restante da série
        for (int i = period; i < bars.size(); i++) {
            ema = bars.get(i).close() * k + ema * (1.0 - k);
        }

        return ema;
    }

    /**
     * Calcula o RSI utilizando o smoothing de Wilder (média exponencial).
     * Fase 1 (seed): média simples dos primeiros {@code period} ganhos e perdas.
     * Fase 2 (smoothing): suavização com fator {@code (period-1)/period}.
     * Retorna {@code 100.0} quando não há perdas no período.
     *
     * @param bars   lista de candles
     * @param period período do RSI
     * @return RSI entre 0 e 100, ou {@code NaN} se dados insuficientes
     */
    private static double rsiWilder(List<Bar> bars, int period) {
        if (bars.size() < period + 1) return Double.NaN;

        double[] changes = new double[bars.size() - 1];
        for (int i = 1; i < bars.size(); i++) {
            changes[i - 1] = bars.get(i).close() - bars.get(i - 1).close();
        }

        double avgGain = 0.0;
        double avgLoss = 0.0;

        for (int i = 0; i < period; i++) {
            if (changes[i] > 0) avgGain += changes[i];
            else                 avgLoss += -changes[i];
        }

        avgGain /= period;
        avgLoss /= period;

        for (int i = period; i < changes.length; i++) {
            double gain = changes[i] > 0 ? changes[i] : 0.0;
            double loss = changes[i] < 0 ? -changes[i] : 0.0;

            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;
        }

        if (avgLoss == 0.0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    /**
     * Calcula o range médio ({@code high - low}) dos últimos {@code lookback} candles.
     * Utilizado como referência de escala pelo filtro anti-chop.
     *
     * @param bars     lista de candles
     * @param lookback número de candles para a média
     * @return range médio ou {@code NaN} se dados insuficientes
     */
    private static double averageRange(List<Bar> bars, int lookback) {
        if (bars.size() < lookback) return Double.NaN;

        double sum = 0.0;
        for (int i = bars.size() - lookback; i < bars.size(); i++) {
            Bar b = bars.get(i);
            sum += (b.high() - b.low());
        }
        return sum / lookback;
    }
}