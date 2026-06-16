package com.github.dayviddouglas.TradingBoot.strategy;

import com.github.dayviddouglas.TradingBoot.model.Bar;
import com.github.dayviddouglas.TradingBoot.model.Signal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Estratégia de breakout baseada no Canal de Keltner.
 *
 * Constrói o canal usando EMA como linha central e ATR como medida de largura:
 * <ul>
 *   <li>Canal superior = EMA + (ATR × {@code atrMultiplier})</li>
 *   <li>Canal inferior = EMA - (ATR × {@code atrMultiplier})</li>
 * </ul>
 *
 * Gera sinal no momento exato do rompimento, verificando que o candle anterior
 * estava dentro do canal e o atual está fora:
 * <ul>
 *   <li>{@code BUY}: {@code previous.close <= upperChannel} E {@code current.close > upperChannel}</li>
 *   <li>{@code SELL}: {@code previous.close >= lowerChannel} E {@code current.close < lowerChannel}</li>
 * </ul>
 *
 * Filtro opcional de força do candle ({@code requireStrongCandle}): o corpo do candle de
 * rompimento deve ser maior que {@code 1.5 × corpo médio} dos últimos 10 candles,
 * filtrando dojis e candles de indecisão que apenas tocam o canal sem convicção.
 *
 * A EMA é calculada sobre toda a série histórica disponível para produzir valores
 * mais estáveis do que uma janela móvel fixa.
 */
public class KeltnerChannelStrategy implements TradingStrategy {

    /** Período da EMA central do canal. */
    private final int emaPeriod;

    /** Período do ATR para definir a largura do canal. */
    private final int atrPeriod;

    /**
     * Multiplicador do ATR aplicado sobre a EMA para construir as bandas.
     * Valores maiores produzem canal mais largo e menos sinais.
     */
    private final double atrMultiplier;

    /** Quando {@code true}, exige candle de corpo forte para confirmar o rompimento. */
    private final boolean requireStrongCandle;

    /** Período para calcular o corpo médio dos candles no filtro de força. */
    private final int bodyLookback = 10;

    /**
     * Multiplicador do corpo médio no filtro de força.
     * O corpo do candle de rompimento deve superar {@code bodyMultiplier × avgBody}.
     */
    private final double bodyMultiplier = 1.5;

    /**
     * @param emaPeriod          período da EMA central; mínimo 2
     * @param atrPeriod          período do ATR; mínimo 2
     * @param atrMultiplier      multiplicador do ATR; deve ser positivo
     * @param requireStrongCandle quando {@code true}, exige corpo de candle significativo
     * @throws IllegalArgumentException se algum parâmetro for inválido
     */
    public KeltnerChannelStrategy(
            int emaPeriod,
            int atrPeriod,
            double atrMultiplier,
            boolean requireStrongCandle
    ) {
        if (emaPeriod < 2)      throw new IllegalArgumentException("emaPeriod must be >= 2");
        if (atrPeriod < 2)      throw new IllegalArgumentException("atrPeriod must be >= 2");
        if (atrMultiplier <= 0) throw new IllegalArgumentException("atrMultiplier must be > 0");

        this.emaPeriod         = emaPeriod;
        this.atrPeriod         = atrPeriod;
        this.atrMultiplier     = atrMultiplier;
        this.requireStrongCandle = requireStrongCandle;
    }

    /**
     * Identificador da estratégia utilizado em logs e metadata do sinal.
     */
    @Override
    public String name() {
        return "KeltnerChannelStrategy";
    }

    /**
     * Avalia se houve rompimento do Canal de Keltner com força de candle suficiente.
     *
     * Fluxo:
     * <ol>
     *   <li>Calcula EMA sobre toda a série e ATR sobre os últimos {@code atrPeriod} candles</li>
     *   <li>Constrói o canal: {@code upper = EMA + ATR × mult}, {@code lower = EMA - ATR × mult}</li>
     *   <li>Quando {@code requireStrongCandle}, verifica se o corpo é {@code > bodyMultiplier × avgBody}</li>
     *   <li>Detecta rompimento comparando o fechamento do candle anterior e do atual com o canal</li>
     * </ol>
     *
     * @param bars lista de candles para análise
     * @return {@link Signal} com tipo, timestamp, preço e metadata dos indicadores
     */
    @Override
    public Signal checkSignal(List<Bar> bars) {
        if (bars == null) return Signal.none(name());

        int minBars = Math.max(emaPeriod + 10, atrPeriod) + 1;
        if (bars.size() < minBars) return Signal.none(name());

        Bar current  = bars.get(bars.size() - 1);
        Bar previous = bars.get(bars.size() - 2);

        double ema = emaCloseCorrect(bars, emaPeriod);
        double atr = atrValue(bars, atrPeriod);

        if (!Double.isFinite(ema) || !Double.isFinite(atr)) {
            return Signal.none(name());
        }

        double upperChannel = ema + (atr * atrMultiplier);
        double lowerChannel = ema - (atr * atrMultiplier);

        Map<String, Object> meta = new HashMap<>();
        meta.put("emaPeriod",     emaPeriod);
        meta.put("atrPeriod",     atrPeriod);
        meta.put("atrMultiplier", atrMultiplier);
        meta.put("ema",           ema);
        meta.put("atr",           atr);
        meta.put("upperChannel",  upperChannel);
        meta.put("lowerChannel",  lowerChannel);
        meta.put("currentClose",  current.close());
        meta.put("previousClose", previous.close());

        // Filtro de força: candle de rompimento deve ter corpo significativo
        if (requireStrongCandle) {
            double  currentBody  = Math.abs(current.close() - current.open());
            double  avgBody      = averageBody(bars, bodyLookback);
            boolean strongCandle = Double.isFinite(avgBody)
                    && currentBody > (avgBody * bodyMultiplier);

            meta.put("currentBody",   currentBody);
            meta.put("avgBody",       avgBody);
            meta.put("strongCandle",  strongCandle);
            meta.put("bodyMultiplier", bodyMultiplier);

            // Rompimento sem convicção — candle fraco
            if (!strongCandle) {
                return Signal.none(name());
            }
        }

        // BUY: candle anterior dentro do canal, atual acima — momento do rompimento
        if (previous.close() <= upperChannel && current.close() > upperChannel) {
            meta.put("pattern", "breakout_up");
            return Signal.buy(name(), current.timestamp(), current.close(), meta);
        }

        // SELL: candle anterior dentro do canal, atual abaixo — momento do rompimento
        if (previous.close() >= lowerChannel && current.close() < lowerChannel) {
            meta.put("pattern", "breakout_down");
            return Signal.sell(name(), current.timestamp(), current.close(), meta);
        }

        return Signal.none(name());
    }

    // ═══════════════════════════════════════════════════════════════
    // Indicadores técnicos
    // ═══════════════════════════════════════════════════════════════

    /**
     * Calcula a EMA sobre toda a série histórica disponível.
     * Inicializa com a SMA dos primeiros {@code period} candles e aplica
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

        for (int i = 0; i < period; i++) {
            ema += bars.get(i).close();
        }
        ema /= period;

        for (int i = period; i < bars.size(); i++) {
            ema = bars.get(i).close() * k + ema * (1.0 - k);
        }

        return ema;
    }

    /**
     * Calcula o ATR para definir a largura do canal.
     * O primeiro candle da janela usa apenas {@code high - low} por não ter candle anterior.
     *
     * @param bars   lista de candles
     * @param period período do ATR
     * @return ATR calculado ou {@code NaN} se dados insuficientes
     */
    private static double atrValue(List<Bar> bars, int period) {
        if (bars.size() < period) return Double.NaN;

        double sum   = 0.0;
        int    start = bars.size() - period;

        for (int i = start; i < bars.size(); i++) {
            Bar    bar = bars.get(i);
            double tr;

            if (i == start) {
                tr = bar.high() - bar.low();
            } else {
                Bar prev = bars.get(i - 1);
                tr = Math.max(
                        bar.high() - bar.low(),
                        Math.max(
                                Math.abs(bar.high() - prev.close()),
                                Math.abs(bar.low()  - prev.close())
                        )
                );
            }

            sum += tr;
        }

        return sum / period;
    }

    /**
     * Calcula o corpo médio ({@code |close - open|}) dos últimos {@code lookback} candles.
     * Utilizado como referência para o filtro de força do candle de rompimento.
     *
     * @param bars     lista de candles
     * @param lookback número de candles para a média
     * @return corpo médio ou {@code NaN} se dados insuficientes
     */
    private static double averageBody(List<Bar> bars, int lookback) {
        if (bars.size() < lookback) return Double.NaN;

        double sum = 0.0;
        for (int i = bars.size() - lookback; i < bars.size(); i++) {
            Bar b = bars.get(i);
            sum += Math.abs(b.close() - b.open());
        }
        return sum / lookback;
    }
}