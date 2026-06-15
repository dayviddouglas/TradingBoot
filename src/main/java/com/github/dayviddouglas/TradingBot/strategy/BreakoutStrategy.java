package com.github.dayviddouglas.TradingBot.strategy;

import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.model.Signal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Estratégia de rompimento (breakout) de máximas e mínimas históricas.
 *
 * Identifica a máxima e a mínima dos últimos {@code lookback} candles (excluindo o atual)
 * e gera sinal quando o preço fecha além desses níveis com buffer e força de candle suficientes:
 * <ul>
 *   <li>{@code BUY}: fechamento acima de {@code prevHigh * (1 + bufferPct)} com candle forte</li>
 *   <li>{@code SELL}: fechamento abaixo de {@code prevLow * (1 - bufferPct)} com candle forte</li>
 * </ul>
 *
 * Filtro de força do candle: o corpo ({@code |close - open|}) do candle de rompimento
 * deve ser maior que {@code 1.5 × corpo médio} dos últimos 14 candles.
 * Filtra dojis e candles de indecisão que tocam o nível sem demonstrar convicção.
 *
 * O candle atual é excluído do cálculo de máxima/mínima para garantir que o rompimento
 * seja avaliado em relação a um nível histórico, não ao próprio candle corrente.
 */
public class BreakoutStrategy implements TradingStrategy {

    /**
     * Período de lookback para identificar máximas e mínimas históricas.
     * Quanto maior, mais significativo é o nível rompido.
     */
    private final int lookback;

    /**
     * Buffer percentual aplicado sobre o nível para confirmar o rompimento.
     * Evita sinais em rompimentos marginais que podem ser ruído de mercado.
     * Exemplo: {@code 0.0002} exige fechamento 0.02% acima da máxima para BUY.
     */
    private final double bufferPct;

    /** Período para calcular o corpo médio dos candles no filtro de força. */
    private final int bodyLookback = 14;

    /**
     * Multiplicador aplicado sobre o corpo médio no filtro de força.
     * O corpo do candle de rompimento deve superar {@code bodyMultiplier × avgBody}.
     */
    private final double bodyMultiplier = 1.5;

    /**
     * @param lookback   período para identificar máximas e mínimas; mínimo 2
     * @param bufferPct  buffer percentual para confirmar rompimento; deve ser {@code >= 0}
     * @throws IllegalArgumentException se os parâmetros forem inválidos
     */
    public BreakoutStrategy(int lookback, double bufferPct) {
        if (lookback < 2)  throw new IllegalArgumentException("lookback must be >= 2");
        if (bufferPct < 0) throw new IllegalArgumentException("bufferPct must be >= 0");
        this.lookback  = lookback;
        this.bufferPct = bufferPct;
    }

    /**
     * Identificador da estratégia utilizado em logs e metadata do sinal.
     */
    @Override
    public String name() {
        return "BreakoutStrategy";
    }

    /**
     * Avalia se houve rompimento de nível histórico com força de candle suficiente.
     *
     * Fluxo:
     * <ol>
     *   <li>Identifica máxima e mínima dos últimos {@code lookback} candles, excluindo o atual</li>
     *   <li>Calcula triggers com buffer: {@code buyTrigger = prevHigh * (1 + bufferPct)}</li>
     *   <li>Verifica força do candle atual: corpo deve ser {@code > bodyMultiplier × avgBody}</li>
     *   <li>Candle fraco → NONE</li>
     *   <li>Fechamento acima do buyTrigger com candle forte → BUY</li>
     *   <li>Fechamento abaixo do sellTrigger com candle forte → SELL</li>
     * </ol>
     *
     * @param bars lista de candles para análise
     * @return {@link Signal} com tipo, timestamp, preço e metadata dos indicadores
     */
    @Override
    public Signal checkSignal(List<Bar> bars) {
        if (bars == null) return Signal.none(name());

        int minBars = Math.max(lookback + 1, bodyLookback + 1);
        if (bars.size() < minBars) return Signal.none(name());

        Bar    last      = bars.get(bars.size() - 1);
        double lastClose = last.close();

        // Identifica máxima e mínima histórica excluindo o candle atual
        double prevHigh = Double.NEGATIVE_INFINITY;
        double prevLow  = Double.POSITIVE_INFINITY;

        int start = bars.size() - 1 - lookback;
        int end   = bars.size() - 2; // Exclui o candle atual

        for (int i = start; i <= end; i++) {
            Bar b = bars.get(i);
            prevHigh = Math.max(prevHigh, b.high());
            prevLow  = Math.min(prevLow,  b.low());
        }

        double buyTrigger  = prevHigh * (1.0 + bufferPct);
        double sellTrigger = prevLow  * (1.0 - bufferPct);

        // Filtro de força: candle de rompimento deve ter corpo significativo
        double currentBody = Math.abs(last.close() - last.open());
        double avgBody     = averageBody(bars, bodyLookback);
        boolean strongCandle = Double.isFinite(avgBody) && currentBody > (avgBody * bodyMultiplier);

        Map<String, Object> meta = new HashMap<>();
        meta.put("lookback",      lookback);
        meta.put("bufferPct",     bufferPct);
        meta.put("prevHigh",      prevHigh);
        meta.put("prevLow",       prevLow);
        meta.put("buyTrigger",    buyTrigger);
        meta.put("sellTrigger",   sellTrigger);
        meta.put("close",         lastClose);
        meta.put("currentBody",   currentBody);
        meta.put("avgBody",       avgBody);
        meta.put("bodyMultiplier", bodyMultiplier);
        meta.put("strongCandle",  strongCandle);

        // Rompimento sem convicção — candle fraco
        if (!strongCandle) return Signal.none(name());

        // Rompimento de alta: fechamento acima da máxima histórica com buffer
        if (lastClose > buyTrigger) {
            return Signal.buy(name(), last.timestamp(), lastClose, meta);
        }

        // Rompimento de baixa: fechamento abaixo da mínima histórica com buffer
        if (lastClose < sellTrigger) {
            return Signal.sell(name(), last.timestamp(), lastClose, meta);
        }

        return Signal.none(name());
    }

    /**
     * Calcula o corpo médio ({@code |close - open|}) dos últimos {@code lookback} candles.
     * Utilizado como referência para o filtro de força do candle de rompimento.
     *
     * @param bars    lista de candles
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