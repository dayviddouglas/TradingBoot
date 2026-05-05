package com.github.dayviddouglas.TradingBot.strategy;

import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.model.Signal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Estratégia de confirmação de tendência via EMA + RSI.
 *
 * Implementa TradingStrategy para uso pelo StrategyEngine.
 *
 * Lógica operacional:
 * - Calcula duas EMAs (rápida e lenta) sobre toda a série histórica
 * - Se EMA rápida > EMA lenta → tendência de alta
 * - Se EMA rápida < EMA lenta → tendência de baixa
 * - Usa RSI como filtro de confirmação de momentum:
 *   - BUY: tendência de alta + RSI >= rsiBuyThreshold
 *   - SELL: tendência de baixa + RSI <= rsiSellThreshold
 *
 * Filtro anti-chop:
 * - A distância entre as EMAs deve ser > emaDistanceFactor × avgRange
 * - Isso evita sinais quando as EMAs estão muito próximas (mercado lateral)
 * - Aumentado de 0.20 para 0.50 na versão corrigida (mais conservador)
 *
 * Versão corrigida:
 * - EMA agora calculada sobre TODA a série histórica (não janela móvel)
 * - RSI agora usa Wilder smoothing (média exponencial, não simples)
 * - emaDistanceFactor aumentado para 0.5 (filtra melhor mercado choppy)
 *
 * Classificada como tendência/momentum pelo projeto.
 * Recebe peso alto em TRENDING e baixo em RANGING no StrategyWeightProfile.
 *
 * Status no projeto: sem destaque recente, rejeitada na maioria dos testes.
 * O produto Rise/Fall 15m não favorece estratégias de tendência clássicas
 * porque a tendência pode não se sustentar até o vencimento do contrato.
 *
 * ⚠️ Ponto de atenção: O nome retornado por name() é "EmaRsiStrategy"
 * (sem prefixo). Isso deve bater com as chaves no StrategyWeightProfile.
 * No AtrRiskManager, a chave usada é "EmaRsiStrategy" (com sufixo),
 * o que pode causar desalinhamento.
 *
 * Referências:
 * - [Wilder, 1978, New Concepts in Technical Trading Systems] (para RSI)
 * - [Elder, 1993, Trading for a Living] (para EMA crossover)
 */
public class EmaRsiStrategy implements TradingStrategy {

    /** Período da EMA rápida (ex: 50) */
    private final int emaFast;

    /** Período da EMA lenta (ex: 200) */
    private final int emaSlow;

    /** Período do RSI para confirmação de momentum */
    private final int rsiPeriod;

    /**
     * Threshold de RSI para confirmar compra.
     * RSI >= rsiBuyThreshold indica momentum de alta suficiente.
     * Valores típicos: 55-70 dependendo da agressividade.
     */
    private final double rsiBuyThreshold;

    /**
     * Threshold de RSI para confirmar venda.
     * RSI <= rsiSellThreshold indica momentum de baixa suficiente.
     * Valores típicos: 30-45 dependendo da agressividade.
     */
    private final double rsiSellThreshold;

    // ═══════════════════════════════════════════════════════════════
    // Filtro anti-chop
    //
    // Evita sinais quando as EMAs estão muito próximas (mercado lateral).
    // A distância entre as EMAs é comparada com o range médio dos candles.
    // Se a distância for muito pequena, as EMAs estão "coladas" e os
    // cruzamentos são provavelmente ruído, não tendência real.
    // ═══════════════════════════════════════════════════════════════

    /** Período para calcular o range médio dos candles */
    private final int rangeLookback = 14;

    /**
     * Fator multiplicador para filtro de distância entre EMAs.
     *
     * Aumentado de 0.20 para 0.50 na versão corrigida.
     * Isso exige que as EMAs estejam mais separadas para gerar sinal,
     * filtrando melhor períodos de indecisão/choppy.
     *
     * Exemplo: se avgRange = 0.0005 e emaDistanceFactor = 0.5,
     * a distância entre EMAs deve ser > 0.00025 para operar.
     */
    private final double emaDistanceFactor = 0.5;

    /**
     * Construtor com validação de parâmetros.
     *
     * Validações importantes:
     * - emaFast DEVE ser menor que emaSlow (senão não faz sentido como crossover)
     * - Todos os períodos devem ser >= 2 para cálculos válidos
     *
     * @param emaFast período da EMA rápida
     * @param emaSlow período da EMA lenta (deve ser > emaFast)
     * @param rsiPeriod período do RSI
     * @param rsiBuyThreshold threshold de RSI para BUY
     * @param rsiSellThreshold threshold de RSI para SELL
     */
    public EmaRsiStrategy(int emaFast, int emaSlow, int rsiPeriod, double rsiBuyThreshold, double rsiSellThreshold) {
        if (emaFast < 2 || emaSlow < 2 || rsiPeriod < 2) throw new IllegalArgumentException("Periods must be >= 2");
        // EMA rápida deve ter período menor para reagir mais rápido
        if (emaFast >= emaSlow) throw new IllegalArgumentException("emaFast must be < emaSlow");
        this.emaFast = emaFast;
        this.emaSlow = emaSlow;
        this.rsiPeriod = rsiPeriod;
        this.rsiBuyThreshold = rsiBuyThreshold;
        this.rsiSellThreshold = rsiSellThreshold;
    }

    @Override
    public String name() {
        return "EmaRsiStrategy";
    }

    /**
     * Avalia tendência via EMA crossover com confirmação de RSI.
     *
     * Fluxo:
     * 1. Calcula EMA rápida e lenta sobre toda a série
     * 2. Calcula RSI com Wilder smoothing
     * 3. Aplica filtro anti-chop (distância entre EMAs vs range médio)
     * 4. Se filtro não aprovado → NONE
     * 5. Se upTrend (emaFast > emaSlow) e RSI confirma → BUY
     * 6. Se downTrend (emaFast < emaSlow) e RSI confirma → SELL
     *
     * O filtro anti-chop é crucial: sem ele, cruzamentos de EMA em
     * mercado lateral gerariam sinais excessivos e não lucrativos.
     *
     * @param bars lista de candles para análise
     * @return Signal (BUY, SELL ou NONE) com metadata
     */
    @Override
    public Signal checkSignal(List<Bar> bars) {
        if (bars == null) return Signal.none(name());

        // Precisa de barras suficientes para EMA lenta + margem para seed
        int minBars = Math.max(emaSlow + 10, Math.max(rsiPeriod + 1, rangeLookback));
        if (bars.size() < minBars) return Signal.none(name());

        Bar last = bars.get(bars.size() - 1);

        // ── Indicadores ──
        // EMA CORRIGIDA: calculada sobre toda a série (não apenas janela)
        double emaFastVal = emaCloseCorrect(bars, emaFast);
        double emaSlowVal = emaCloseCorrect(bars, emaSlow);

        // RSI CORRIGIDO: usa Wilder smoothing em vez de SMA simples
        double rsiVal = rsiWilder(bars, rsiPeriod);

        // Validação de cálculos
        if (!Double.isFinite(emaFastVal) || !Double.isFinite(emaSlowVal) || !Double.isFinite(rsiVal)) {
            return Signal.none(name());
        }

        // ── Filtro anti-chop ──
        // Exige separação significativa entre as EMAs para confirmar tendência
        double avgRange = averageRange(bars, rangeLookback);
        double emaDistance = Math.abs(emaFastVal - emaSlowVal);
        boolean trendStrengthOk = Double.isFinite(avgRange) && emaDistance > (avgRange * emaDistanceFactor);

        // EMAs muito próximas: provável mercado lateral → sem sinal
        if (!trendStrengthOk) {
            return Signal.none(name());
        }

        // ── Identificação de tendência ──
        boolean upTrend = emaFastVal > emaSlowVal;   // EMA rápida acima → alta
        boolean downTrend = emaFastVal < emaSlowVal;  // EMA rápida abaixo → baixa

        // Metadata para rastreabilidade
        Map<String, Object> meta = new HashMap<>();
        meta.put("emaFast", emaFast);
        meta.put("emaSlow", emaSlow);
        meta.put("rsiPeriod", rsiPeriod);
        meta.put("emaFastVal", emaFastVal);
        meta.put("emaSlowVal", emaSlowVal);
        meta.put("rsiVal", rsiVal);
        meta.put("rsiBuyThreshold", rsiBuyThreshold);
        meta.put("rsiSellThreshold", rsiSellThreshold);
        meta.put("avgRange", avgRange);
        meta.put("emaDistance", emaDistance);
        meta.put("emaDistanceFactor", emaDistanceFactor);

        // ── BUY: tendência de alta confirmada por RSI ──
        // upTrend: EMA rápida acima da lenta
        // RSI >= rsiBuyThreshold: momentum de alta presente
        if (upTrend && rsiVal >= rsiBuyThreshold) {
            return Signal.buy(name(), last.timestamp(), last.close(), meta);
        }

        // ── SELL: tendência de baixa confirmada por RSI ──
        if (downTrend && rsiVal <= rsiSellThreshold) {
            return Signal.sell(name(), last.timestamp(), last.close(), meta);
        }

        return Signal.none(name());
    }

    // ═══════════════════════════════════════════════════════════════
    // Indicadores técnicos corrigidos
    // ═══════════════════════════════════════════════════════════════

    /**
     * EMA corrigida: calculada sobre toda a série histórica.
     *
     * CORREÇÃO: A versão original calculava EMA apenas sobre os últimos
     * N candles (janela móvel), o que produzia um valor diferente da EMA
     * "real" que inclui toda a série no seu cálculo exponencial.
     *
     * Fases do cálculo:
     * 1. Seed: SMA dos PRIMEIROS N candles (não dos últimos)
     * 2. Loop: aplica suavização exponencial até o ÚLTIMO candle
     *
     * Isso garante que a EMA reflita todo o histórico disponível,
     * produzindo valores mais estáveis e consistentes.
     *
     * @param bars lista completa de candles
     * @param period período da EMA
     * @return valor atual da EMA ou NaN se dados insuficientes
     */
    private static double emaCloseCorrect(List<Bar> bars, int period) {
        if (bars.size() < period) return Double.NaN;

        // Multiplicador exponencial
        double k = 2.0 / (period + 1.0);

        // Seed: SMA dos PRIMEIROS candles (não dos últimos)
        double ema = 0.0;
        for (int i = 0; i < period; i++) {
            ema += bars.get(i).close();
        }
        ema /= period;

        // Aplicação exponencial sobre o restante da série
        for (int i = period; i < bars.size(); i++) {
            ema = bars.get(i).close() * k + ema * (1.0 - k);
        }

        return ema;
    }

    /**
     * RSI corrigido com Wilder Smoothing.
     *
     * CORREÇÃO: A versão original usava média simples para todo o cálculo.
     * O RSI de Wilder usa:
     * 1. SMA para o período inicial (seed)
     * 2. Suavização exponencial (Wilder) para o restante:
     *    avgGain = (avgGain × (period-1) + gain) / period
     *
     * Referência: [Wilder, 1978]
     *
     * @param bars lista de candles
     * @param period período do RSI
     * @return RSI entre 0-100 ou NaN se dados insuficientes
     */
    private static double rsiWilder(List<Bar> bars, int period) {
        if (bars.size() < period + 1) return Double.NaN;

        // Calcula variações de preço entre candles consecutivos
        double[] changes = new double[bars.size() - 1];
        for (int i = 1; i < bars.size(); i++) {
            changes[i - 1] = bars.get(i).close() - bars.get(i - 1).close();
        }

        // Fase 1: SMA inicial dos primeiros N períodos (seed)
        double avgGain = 0.0;
        double avgLoss = 0.0;

        for (int i = 0; i < period; i++) {
            if (changes[i] > 0) {
                avgGain += changes[i];
            } else {
                avgLoss += -changes[i];
            }
        }

        avgGain /= period;
        avgLoss /= period;

        // Fase 2: Wilder smoothing para os períodos restantes
        for (int i = period; i < changes.length; i++) {
            double gain = changes[i] > 0 ? changes[i] : 0.0;
            double loss = changes[i] < 0 ? -changes[i] : 0.0;

            // Suavização exponencial de Wilder
            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;
        }

        // Cálculo final: RS = avgGain/avgLoss → RSI = 100 - 100/(1+RS)
        if (avgLoss == 0.0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    /**
     * Calcula o range médio (high - low) dos últimos N candles.
     *
     * Usado pelo filtro anti-chop como referência para a distância
     * mínima aceitável entre as EMAs.
     *
     * @param bars lista de candles
     * @param lookback quantidade de candles para a média
     * @return range médio ou NaN se dados insuficientes
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