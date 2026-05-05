package com.github.dayviddouglas.TradingBot.strategy;

import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.model.Signal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Estratégia de breakout baseada no Canal de Donchian (Turtle Traders).
 *
 * Implementa TradingStrategy para uso pelo StrategyEngine.
 *
 * Conceito original:
 * O Canal de Donchian rastreia a máxima mais alta e a mínima mais baixa
 * dos últimos N períodos. Quando o preço rompe esses níveis, é interpretado
 * como início de uma nova tendência.
 *
 * Esta é a base do famoso sistema Turtle Traders (Richard Dennis, 1983),
 * que usava dois sistemas complementares:
 * - System 1: entry 20, exit 10 (curto prazo, mais frequente)
 * - System 2: entry 55, exit 20 (longo prazo, mais seletivo)
 *
 * Lógica operacional:
 * - BUY quando o close rompe acima da máxima de entryPeriod
 * - SELL quando o close rompe abaixo da mínima de entryPeriod
 * - Filtro opcional de expansão de ATR para confirmar momentum
 *
 * O filtro de ATR é inspirado na abordagem dos Turtles:
 * não operar rompimentos em mercados com volatilidade comprimida,
 * pois tendem a gerar falsos breakouts.
 *
 * Classificada como TREND_BREAKOUT pelo AtrRiskManager.
 *
 * Status no projeto: fraca no produto Rise/Fall 15m.
 * Breakouts clássicos não se adaptaram bem ao contrato de expiração
 * fixa curta, onde a tendência pode não se desenvolver antes do vencimento.
 *
 * ⚠️ Ponto de atenção: O exitPeriod está disponível como parâmetro mas
 * não é utilizado na lógica atual de checkSignal(). No sistema Turtle
 * original, o exitPeriod definia quando FECHAR uma posição já aberta.
 * Em contratos binários de expiração fixa, esse conceito não se aplica.
 *
 * Referência: [Curtis Faith, 2007, Way of the Turtle]
 *
 * @see BreakoutStrategy para uma abordagem de rompimento mais simples
 */
public class DonchianBreakoutStrategy implements TradingStrategy {

    /** Período para calcular os canais de entrada (máxima/mínima de N barras) */
    private final int entryPeriod;

    /**
     * Período para calcular os canais de saída.
     * No sistema Turtle original, define quando fechar posição.
     * Mantido por compatibilidade, mas não usado em contratos binários.
     */
    private final int exitPeriod;

    /** Se true, exige expansão de ATR para confirmar o rompimento */
    private final boolean useATRFilter;

    /** Período para cálculo do ATR no filtro de expansão */
    private final int atrPeriod;

    /**
     * Expansão mínima do ATR para confirmar rompimento.
     * Exemplo: 0.05 = ATR atual deve ser 5% maior que o anterior.
     * Filtra rompimentos em mercados de baixa volatilidade.
     */
    private final double minATRExpansion;

    /**
     * Construtor com validação rigorosa de parâmetros.
     *
     * @param entryPeriod período do canal de entrada (mínimo 2)
     * @param exitPeriod período do canal de saída (mínimo 2)
     * @param useATRFilter se deve exigir expansão de ATR
     * @param atrPeriod período do ATR para o filtro
     * @param minATRExpansion expansão mínima percentual do ATR
     */
    public DonchianBreakoutStrategy(
            int entryPeriod,
            int exitPeriod,
            boolean useATRFilter,
            int atrPeriod,
            double minATRExpansion
    ) {
        if (entryPeriod < 2) throw new IllegalArgumentException("entryPeriod must be >= 2");
        if (exitPeriod < 2) throw new IllegalArgumentException("exitPeriod must be >= 2");
        if (atrPeriod < 2) throw new IllegalArgumentException("atrPeriod must be >= 2");
        if (minATRExpansion < 0) throw new IllegalArgumentException("minATRExpansion must be >= 0");

        this.entryPeriod = entryPeriod;
        this.exitPeriod = exitPeriod;
        this.useATRFilter = useATRFilter;
        this.atrPeriod = atrPeriod;
        this.minATRExpansion = minATRExpansion;
    }

    @Override
    public String name() {
        return "DonchianBreakoutStrategy";
    }

    /**
     * Avalia se houve rompimento do Canal de Donchian.
     *
     * A detecção de rompimento exige duas condições:
     * 1. O candle ATUAL fecha acima/abaixo do canal
     * 2. O candle ANTERIOR estava dentro do canal
     *
     * Essa verificação dupla (current vs previous) evita sinais repetidos
     * quando o preço permanece acima do canal por múltiplos candles.
     * Só gera sinal no momento exato do rompimento.
     *
     * @param bars lista de candles para análise
     * @return Signal (BUY, SELL ou NONE) com metadata
     */
    @Override
    public Signal checkSignal(List<Bar> bars) {
        if (bars == null) return Signal.none(name());

        // Necessita de barras para o canal E para o filtro de ATR
        int minBars = Math.max(entryPeriod, useATRFilter ? atrPeriod : 0) + 1;
        if (bars.size() < minBars) return Signal.none(name());

        Bar current = bars.get(bars.size() - 1);
        Bar previous = bars.get(bars.size() - 2);

        // ── Cálculo dos canais de entrada ──
        // excludeCurrent=true: exclui o candle atual para comparar com nível histórico
        double entryHigh = getHighest(bars, entryPeriod, true);
        double entryLow = getLowest(bars, entryPeriod, true);

        if (!Double.isFinite(entryHigh) || !Double.isFinite(entryLow)) {
            return Signal.none(name());
        }

        Map<String, Object> meta = new HashMap<>();
        meta.put("entryPeriod", entryPeriod);
        meta.put("exitPeriod", exitPeriod);
        meta.put("entryHigh", entryHigh);
        meta.put("entryLow", entryLow);
        meta.put("currentClose", current.close());
        meta.put("previousClose", previous.close());

        // ── Filtro de expansão de ATR (método Turtle Traders) ──
        // Verifica se o ATR atual é maior que o anterior + minATRExpansion%
        // Isso confirma que o rompimento ocorre com momentum crescente,
        // não em mercado comprimido onde breakouts falsos são comuns
        if (useATRFilter) {
            boolean atrExpanding = isATRExpanding(bars, atrPeriod, minATRExpansion);
            meta.put("useATRFilter", true);
            meta.put("atrExpanding", atrExpanding);

            if (!atrExpanding) {
                return Signal.none(name());
            }
        }

        // ── BUY: Rompimento acima do canal superior ──
        // current.close > entryHigh: preço rompeu a máxima
        // previous.close <= entryHigh: candle anterior estava dentro do canal
        // A segunda condição garante que o sinal é emitido apenas UMA VEZ
        if (current.close() > entryHigh && previous.close() <= entryHigh) {
            meta.put("pattern", "donchian_breakout_up");
            return Signal.buy(name(), current.timestamp(), current.close(), meta);
        }

        // ── SELL: Rompimento abaixo do canal inferior ──
        if (current.close() < entryLow && previous.close() >= entryLow) {
            meta.put("pattern", "donchian_breakout_down");
            return Signal.sell(name(), current.timestamp(), current.close(), meta);
        }

        return Signal.none(name());
    }

    /**
     * Retorna a máxima mais alta dos últimos N candles.
     *
     * @param bars lista de candles
     * @param period quantidade de candles para busca
     * @param excludeCurrent se true, exclui o último candle (para comparação)
     * @return máxima mais alta ou NaN se dados insuficientes
     */
    private static double getHighest(List<Bar> bars, int period, boolean excludeCurrent) {
        int end = excludeCurrent ? bars.size() - 2 : bars.size() - 1;
        int start = end - period + 1;

        if (start < 0) start = 0;
        if (end < 0) return Double.NaN;

        double max = Double.NEGATIVE_INFINITY;
        for (int i = start; i <= end; i++) {
            max = Math.max(max, bars.get(i).high());
        }
        return max;
    }

    /**
     * Retorna a mínima mais baixa dos últimos N candles.
     *
     * @param bars lista de candles
     * @param period quantidade de candles para busca
     * @param excludeCurrent se true, exclui o último candle
     * @return mínima mais baixa ou NaN se dados insuficientes
     */
    private static double getLowest(List<Bar> bars, int period, boolean excludeCurrent) {
        int end = excludeCurrent ? bars.size() - 2 : bars.size() - 1;
        int start = end - period + 1;

        if (start < 0) start = 0;
        if (end < 0) return Double.NaN;

        double min = Double.POSITIVE_INFINITY;
        for (int i = start; i <= end; i++) {
            min = Math.min(min, bars.get(i).low());
        }
        return min;
    }

    /**
     * Verifica se o ATR está expandindo (crescendo) em relação ao período anterior.
     *
     * Compara o ATR do período completo (incluindo último candle) com
     * o ATR do período anterior (excluindo último candle).
     *
     * A expansão mínima exigida (minATRExpansion) garante que o rompimento
     * ocorre com volatilidade crescente, não em mercado estagnado.
     *
     * @param bars lista de candles
     * @param atrPeriod período do ATR
     * @param minExpansion expansão mínima percentual (ex: 0.05 = 5%)
     * @return true se ATR atual > ATR anterior × (1 + minExpansion)
     */
    private static boolean isATRExpanding(List<Bar> bars, int atrPeriod, double minExpansion) {
        if (bars.size() < atrPeriod + 1) return false;

        double currentATR = atrValue(bars, atrPeriod);

        // ATR anterior: calcula sem o último candle
        List<Bar> previousBars = bars.subList(0, bars.size() - 1);
        double previousATR = atrValue(previousBars, atrPeriod);

        if (!Double.isFinite(currentATR) || !Double.isFinite(previousATR)) {
            return false;
        }

        return currentATR > previousATR * (1.0 + minExpansion);
    }

    /**
     * Calcula o ATR (Average True Range) para um período específico.
     *
     * @param bars lista de candles
     * @param period período do ATR
     * @return ATR calculado ou NaN se dados insuficientes
     */
    private static double atrValue(List<Bar> bars, int period) {
        if (bars.size() < period) return Double.NaN;

        double sum = 0.0;
        int start = bars.size() - period;

        for (int i = start; i < bars.size(); i++) {
            Bar bar = bars.get(i);
            double tr;

            if (i == 0) {
                // Primeiro candle absoluto: sem close anterior
                tr = bar.high() - bar.low();
            } else {
                // True Range com gaps
                Bar prev = bars.get(i - 1);
                tr = Math.max(
                        bar.high() - bar.low(),
                        Math.max(
                                Math.abs(bar.high() - prev.close()),
                                Math.abs(bar.low() - prev.close())
                        )
                );
            }

            sum += tr;
        }

        return sum / period;
    }
}