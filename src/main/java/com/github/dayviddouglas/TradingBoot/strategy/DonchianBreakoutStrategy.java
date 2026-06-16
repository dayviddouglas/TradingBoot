package com.github.dayviddouglas.TradingBoot.strategy;

import com.github.dayviddouglas.TradingBoot.model.Bar;
import com.github.dayviddouglas.TradingBoot.model.Signal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Estratégia de breakout baseada no Canal de Donchian, inspirada no sistema Turtle Traders.
 *
 * Rastreia a máxima mais alta e a mínima mais baixa dos últimos {@code entryPeriod} candles
 * e gera sinal no momento exato em que o preço rompe esses níveis:
 * <ul>
 *   <li>{@code BUY}: fechamento atual acima do canal superior E fechamento anterior dentro do canal</li>
 *   <li>{@code SELL}: fechamento atual abaixo do canal inferior E fechamento anterior dentro do canal</li>
 * </ul>
 *
 * A verificação dupla (candle atual vs candle anterior) garante que o sinal é emitido
 * apenas no momento exato do rompimento, evitando sinais repetidos enquanto o preço
 * permanece fora do canal.
 *
 * Filtro opcional de expansão de ATR: o ATR atual deve ser maior que o ATR anterior
 * em pelo menos {@code minATRExpansion}, confirmando que o rompimento ocorre com
 * momentum crescente, não em mercado comprimido onde falsos breakouts são comuns.
 *
 * O {@code exitPeriod} está disponível como parâmetro para compatibilidade com o
 * sistema Turtle original (onde definia quando fechar posições), mas não é utilizado
 * na lógica de geração de sinal, pois contratos binários de expiração fixa não
 * possuem saída gerenciada.
 */
public class DonchianBreakoutStrategy implements TradingStrategy {

    /** Período para calcular os canais de entrada: máxima e mínima dos últimos N candles. */
    private final int entryPeriod;

    /**
     * Período para calcular os canais de saída.
     * Mantido por compatibilidade com o sistema Turtle original;
     * não utilizado na geração de sinal em contratos binários.
     */
    private final int exitPeriod;

    /** Quando {@code true}, exige expansão do ATR para confirmar o rompimento. */
    private final boolean useATRFilter;

    /** Período para cálculo do ATR no filtro de expansão. */
    private final int atrPeriod;

    /**
     * Expansão mínima do ATR para confirmar rompimento.
     * Exemplo: {@code 0.05} exige que o ATR atual seja pelo menos 5% maior que o anterior.
     */
    private final double minATRExpansion;

    /**
     * @param entryPeriod    período do canal de entrada; mínimo 2
     * @param exitPeriod     período do canal de saída; mínimo 2
     * @param useATRFilter   se deve exigir expansão de ATR antes de gerar sinal
     * @param atrPeriod      período do ATR para o filtro; mínimo 2
     * @param minATRExpansion expansão mínima percentual do ATR; deve ser {@code >= 0}
     * @throws IllegalArgumentException se algum parâmetro for inválido
     */
    public DonchianBreakoutStrategy(
            int entryPeriod,
            int exitPeriod,
            boolean useATRFilter,
            int atrPeriod,
            double minATRExpansion
    ) {
        if (entryPeriod < 2)    throw new IllegalArgumentException("entryPeriod must be >= 2");
        if (exitPeriod < 2)     throw new IllegalArgumentException("exitPeriod must be >= 2");
        if (atrPeriod < 2)      throw new IllegalArgumentException("atrPeriod must be >= 2");
        if (minATRExpansion < 0) throw new IllegalArgumentException("minATRExpansion must be >= 0");

        this.entryPeriod    = entryPeriod;
        this.exitPeriod     = exitPeriod;
        this.useATRFilter   = useATRFilter;
        this.atrPeriod      = atrPeriod;
        this.minATRExpansion = minATRExpansion;
    }

    /**
     * Identificador da estratégia utilizado em logs e metadata do sinal.
     */
    @Override
    public String name() {
        return "DonchianBreakoutStrategy";
    }

    /**
     * Avalia se houve rompimento do Canal de Donchian com confirmação opcional de ATR.
     *
     * Fluxo:
     * <ol>
     *   <li>Calcula os canais de entrada excluindo o candle atual</li>
     *   <li>Quando {@code useATRFilter} está ativo, verifica se o ATR está expandindo</li>
     *   <li>Detecta rompimento comparando o fechamento atual com o anterior em relação ao canal</li>
     * </ol>
     *
     * @param bars lista de candles para análise
     * @return {@link Signal} com tipo, timestamp, preço e metadata dos indicadores
     */
    @Override
    public Signal checkSignal(List<Bar> bars) {
        if (bars == null) return Signal.none(name());

        int minBars = Math.max(entryPeriod, useATRFilter ? atrPeriod : 0) + 1;
        if (bars.size() < minBars) return Signal.none(name());

        Bar current  = bars.get(bars.size() - 1);
        Bar previous = bars.get(bars.size() - 2);

        // Canais calculados excluindo o candle atual para comparação com nível histórico
        double entryHigh = getHighest(bars, entryPeriod, true);
        double entryLow  = getLowest(bars, entryPeriod, true);

        if (!Double.isFinite(entryHigh) || !Double.isFinite(entryLow)) {
            return Signal.none(name());
        }

        Map<String, Object> meta = new HashMap<>();
        meta.put("entryPeriod",    entryPeriod);
        meta.put("exitPeriod",     exitPeriod);
        meta.put("entryHigh",      entryHigh);
        meta.put("entryLow",       entryLow);
        meta.put("currentClose",   current.close());
        meta.put("previousClose",  previous.close());

        // Filtro de expansão de ATR: confirma que o rompimento ocorre com momentum crescente
        if (useATRFilter) {
            boolean atrExpanding = isATRExpanding(bars, atrPeriod, minATRExpansion);
            meta.put("useATRFilter", true);
            meta.put("atrExpanding", atrExpanding);

            // ATR não expande — rompimento em mercado comprimido, propenso a falso breakout
            if (!atrExpanding) {
                return Signal.none(name());
            }
        }

        // BUY: candle anterior dentro do canal, candle atual acima — momento exato do rompimento
        if (current.close() > entryHigh && previous.close() <= entryHigh) {
            meta.put("pattern", "donchian_breakout_up");
            return Signal.buy(name(), current.timestamp(), current.close(), meta);
        }

        // SELL: candle anterior dentro do canal, candle atual abaixo — momento exato do rompimento
        if (current.close() < entryLow && previous.close() >= entryLow) {
            meta.put("pattern", "donchian_breakout_down");
            return Signal.sell(name(), current.timestamp(), current.close(), meta);
        }

        return Signal.none(name());
    }

    /**
     * Retorna a máxima ({@code high}) mais alta dos últimos {@code period} candles.
     *
     * @param bars           lista de candles
     * @param period         quantidade de candles para a busca
     * @param excludeCurrent quando {@code true}, exclui o último candle da janela
     * @return máxima encontrada ou {@code NaN} se dados insuficientes
     */
    private static double getHighest(List<Bar> bars, int period, boolean excludeCurrent) {
        int end   = excludeCurrent ? bars.size() - 2 : bars.size() - 1;
        int start = end - period + 1;

        if (start < 0) start = 0;
        if (end < 0)   return Double.NaN;

        double max = Double.NEGATIVE_INFINITY;
        for (int i = start; i <= end; i++) {
            max = Math.max(max, bars.get(i).high());
        }
        return max;
    }

    /**
     * Retorna a mínima ({@code low}) mais baixa dos últimos {@code period} candles.
     *
     * @param bars           lista de candles
     * @param period         quantidade de candles para a busca
     * @param excludeCurrent quando {@code true}, exclui o último candle da janela
     * @return mínima encontrada ou {@code NaN} se dados insuficientes
     */
    private static double getLowest(List<Bar> bars, int period, boolean excludeCurrent) {
        int end   = excludeCurrent ? bars.size() - 2 : bars.size() - 1;
        int start = end - period + 1;

        if (start < 0) start = 0;
        if (end < 0)   return Double.NaN;

        double min = Double.POSITIVE_INFINITY;
        for (int i = start; i <= end; i++) {
            min = Math.min(min, bars.get(i).low());
        }
        return min;
    }

    /**
     * Verifica se o ATR atual está expandindo em relação ao período anterior
     * com a margem mínima configurada.
     * Compara o ATR calculado com o último candle com o ATR calculado sem o último candle.
     *
     * @param bars         lista de candles
     * @param atrPeriod    período do ATR
     * @param minExpansion expansão mínima percentual exigida (ex: {@code 0.05} = 5%)
     * @return {@code true} se {@code currentATR > previousATR * (1 + minExpansion)}
     */
    private static boolean isATRExpanding(List<Bar> bars, int atrPeriod, double minExpansion) {
        if (bars.size() < atrPeriod + 1) return false;

        double currentATR  = atrValue(bars, atrPeriod);
        double previousATR = atrValue(bars.subList(0, bars.size() - 1), atrPeriod);

        if (!Double.isFinite(currentATR) || !Double.isFinite(previousATR)) {
            return false;
        }

        return currentATR > previousATR * (1.0 + minExpansion);
    }

    /**
     * Calcula o ATR para o período informado.
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

            if (i == 0) {
                // Primeiro candle absoluto da lista: sem close anterior disponível
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
}