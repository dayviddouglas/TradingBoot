package com.github.dayviddouglas.TradingBoot.strategy;

import com.github.dayviddouglas.TradingBoot.model.Bar;
import com.github.dayviddouglas.TradingBoot.model.Signal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Estratégia de reversão à média baseada em Bandas de Bollinger.
 *
 * Calcula a posição do preço atual dentro das bandas e gera sinal de
 * reversão quando o preço atinge os extremos:
 * <ul>
 *   <li>{@code SELL}: preço nos percentis superiores da banda
 *       ({@code positionInBand > entryThreshold}), indicando possível exaustão de alta</li>
 *   <li>{@code BUY}: preço nos percentis inferiores da banda
 *       ({@code positionInBand < 1 - entryThreshold}), indicando possível exaustão de baixa</li>
 * </ul>
 *
 * Filtros aplicados antes da geração de sinal:
 * <ul>
 *   <li><b>Band width filter</b>: a largura das bandas deve ser {@code >= 50%} do ATR de 14 períodos.
 *       Evita sinais em períodos de compressão extrema sem volatilidade suficiente.</li>
 *   <li><b>RSI confirmation</b> (opcional): confirma exaustão via RSI Wilder antes de entrar.
 *       RSI {@code > rsiOverbought} para SELL; RSI {@code < rsiOversold} para BUY.</li>
 * </ul>
 *
 * O desvio padrão utiliza correção de Bessel (N-1), estatisticamente mais correto
 * para amostras, especialmente com períodos curtos. O RSI utiliza o smoothing de
 * Wilder (média exponencial), não a média simples.
 */
public class BollingerMeanReversionStrategy implements TradingStrategy {

    /** Período da SMA e do desvio padrão amostral. */
    private final int period;

    /**
     * Multiplicador do desvio padrão para construção das bandas.
     * Valores maiores produzem bandas mais largas e menos sinais.
     */
    private final double stdDevMultiplier;

    /**
     * Limiar de posição nas bandas para gerar sinal de reversão.
     * Com {@code 0.96}: SELL quando posição {@code > 0.96}; BUY quando posição {@code < 0.04}.
     */
    private final double entryThreshold;

    /** Quando {@code true}, exige confirmação de RSI antes de gerar sinal. */
    private final boolean useRsiConfirmation;

    /** Período do RSI de Wilder; utilizado apenas quando {@code useRsiConfirmation} é {@code true}. */
    private final int rsiPeriod;

    /** Nível de RSI acima do qual o mercado é considerado sobrecomprado (confirma SELL). */
    private final double rsiOverbought;

    /** Nível de RSI abaixo do qual o mercado é considerado sobrevendido (confirma BUY). */
    private final double rsiOversold;

    /**
     * Constrói a estratégia com validação rigorosa dos parâmetros.
     * Valores inválidos lançam {@link IllegalArgumentException} imediatamente,
     * impedindo configurações que gerariam sinais incorretos em runtime.
     *
     * @param period           período das bandas; mínimo 5 para significância estatística
     * @param stdDevMultiplier multiplicador do desvio padrão; deve ser positivo
     * @param entryThreshold   limiar de posição nas bandas; deve estar em [0, 1]
     * @param useRsiConfirmation se deve exigir confirmação de RSI
     * @param rsiPeriod        período do RSI; mínimo 2
     * @param rsiOverbought    nível de sobrecompra; deve ser maior que {@code rsiOversold}
     * @param rsiOversold      nível de sobrevenda
     */
    public BollingerMeanReversionStrategy(
            int period,
            double stdDevMultiplier,
            double entryThreshold,
            boolean useRsiConfirmation,
            int rsiPeriod,
            double rsiOverbought,
            double rsiOversold
    ) {
        if (period < 5)
            throw new IllegalArgumentException("period must be >= 5");
        if (stdDevMultiplier <= 0)
            throw new IllegalArgumentException("stdDevMultiplier must be > 0");
        if (entryThreshold < 0 || entryThreshold > 1)
            throw new IllegalArgumentException("entryThreshold must be in [0,1]");
        if (rsiPeriod < 2)
            throw new IllegalArgumentException("rsiPeriod must be >= 2");
        if (rsiOverbought <= rsiOversold)
            throw new IllegalArgumentException("rsiOverbought must be > rsiOversold");

        this.period            = period;
        this.stdDevMultiplier  = stdDevMultiplier;
        this.entryThreshold    = entryThreshold;
        this.useRsiConfirmation = useRsiConfirmation;
        this.rsiPeriod         = rsiPeriod;
        this.rsiOverbought     = rsiOverbought;
        this.rsiOversold       = rsiOversold;
    }

    /**
     * Identificador da estratégia utilizado em logs, metadata do sinal,
     * {@link com.github.dayviddouglas.TradingBoot.engine.confluence.StrategyWeightProfile}
     * e classificação no {@link com.github.dayviddouglas.TradingBoot.risk.AtrRiskManager}.
     */
    @Override
    public String name() {
        return "BollingerMeanReversionStrategy";
    }

    /**
     * Avalia os candles e retorna um sinal de reversão baseado nas Bandas de Bollinger.
     *
     * Fluxo de avaliação:
     * <ol>
     *   <li>Valida quantidade mínima de barras</li>
     *   <li>Calcula SMA e desvio padrão amostral (N-1) para construir as bandas</li>
     *   <li>Aplica filtro de largura de banda: {@code bandWidth >= atr14 * 0.5}</li>
     *   <li>Calcula a posição do preço nas bandas: {@code (close - lower) / (upper - lower)}</li>
     *   <li>Quando próximo ao topo ({@code position > entryThreshold}) e RSI confirma → SELL</li>
     *   <li>Quando próximo à base ({@code position < 1 - entryThreshold}) e RSI confirma → BUY</li>
     *   <li>Zona neutra → NONE</li>
     * </ol>
     *
     * @param bars lista de candles para análise
     * @return {@link Signal} com tipo, timestamp, preço e metadata dos indicadores
     */
    @Override
    public Signal checkSignal(List<Bar> bars) {
        if (bars == null) return Signal.none(name());

        int minBars = Math.max(period, useRsiConfirmation ? rsiPeriod + 1 : 0);
        if (bars.size() < minBars) return Signal.none(name());

        Bar    last  = bars.get(bars.size() - 1);
        double close = last.close();

        double sma      = smaClose(bars, period);
        double stdDev   = stdDevCloseCorrect(bars, period, sma);
        double upperBand = sma + (stdDev * stdDevMultiplier);
        double lowerBand = sma - (stdDev * stdDevMultiplier);

        if (!Double.isFinite(sma) || !Double.isFinite(stdDev)) {
            return Signal.none(name());
        }

        // Filtro de largura de banda: evita sinais em mercado comprimido sem volatilidade suficiente
        double atr          = calculateATR(bars, 14);
        double bandWidth    = upperBand - lowerBand;
        double minBandWidth = atr * 0.5;

        if (bandWidth < minBandWidth) {
            return Signal.none(name());
        }

        // Posição do preço: 0.0 = banda inferior, 0.5 = SMA, 1.0 = banda superior
        double bandRange      = upperBand - lowerBand;
        double positionInBand = (close - lowerBand) / bandRange;

        Map<String, Object> meta = new HashMap<>();
        meta.put("period",          period);
        meta.put("stdDevMultiplier", stdDevMultiplier);
        meta.put("entryThreshold",  entryThreshold);
        meta.put("sma",             sma);
        meta.put("stdDev",          stdDev);
        meta.put("upperBand",       upperBand);
        meta.put("lowerBand",       lowerBand);
        meta.put("bandWidth",       bandWidth);
        meta.put("close",           close);
        meta.put("positionInBand",  positionInBand);

        // SELL: preço nos percentis superiores da banda — possível exaustão de alta
        if (positionInBand > entryThreshold) {
            if (useRsiConfirmation) {
                double rsi = rsiWilder(bars, rsiPeriod);
                meta.put("rsiVal",       rsi);
                meta.put("rsiOverbought", rsiOverbought);

                // RSI não confirma exaustão de alta — sem sinal
                if (!Double.isFinite(rsi) || rsi < rsiOverbought) {
                    return Signal.none(name());
                }
            }

            meta.put("pattern", "overbought_reversion");
            return Signal.sell(name(), last.timestamp(), close, meta);
        }

        // BUY: preço nos percentis inferiores da banda — possível exaustão de baixa
        if (positionInBand < (1.0 - entryThreshold)) {
            if (useRsiConfirmation) {
                double rsi = rsiWilder(bars, rsiPeriod);
                meta.put("rsiVal",    rsi);
                meta.put("rsiOversold", rsiOversold);

                // RSI não confirma exaustão de baixa — sem sinal
                if (!Double.isFinite(rsi) || rsi > rsiOversold) {
                    return Signal.none(name());
                }
            }

            meta.put("pattern", "oversold_reversion");
            return Signal.buy(name(), last.timestamp(), close, meta);
        }

        // Preço na zona neutra das bandas
        return Signal.none(name());
    }

    // ═══════════════════════════════════════════════════════════════
    // Indicadores técnicos
    // ═══════════════════════════════════════════════════════════════

    /**
     * Calcula a Média Móvel Simples (SMA) dos preços de fechamento dos últimos {@code period} candles.
     *
     * @param bars   lista de candles
     * @param period número de candles para a média
     * @return SMA calculada ou {@code NaN} se dados insuficientes
     */
    private static double smaClose(List<Bar> bars, int period) {
        if (bars.size() < period) return Double.NaN;

        double sum   = 0.0;
        int    start = bars.size() - period;

        for (int i = start; i < bars.size(); i++) {
            sum += bars.get(i).close();
        }

        return sum / period;
    }

    /**
     * Calcula o desvio padrão amostral (N-1) dos preços de fechamento.
     * A correção de Bessel (N-1) produz estimativa não-enviesada da variância da população,
     * sendo estatisticamente mais correta que o desvio padrão populacional (N) para amostras.
     *
     * @param bars   lista de candles
     * @param period número de candles para o cálculo
     * @param mean   SMA pré-calculada, evitando recálculo
     * @return desvio padrão amostral ou {@code NaN} se dados insuficientes ou média inválida
     */
    private static double stdDevCloseCorrect(List<Bar> bars, int period, double mean) {
        if (bars.size() < period || !Double.isFinite(mean)) return Double.NaN;

        double sum   = 0.0;
        int    start = bars.size() - period;

        for (int i = start; i < bars.size(); i++) {
            double diff = bars.get(i).close() - mean;
            sum += diff * diff;
        }

        // Divisor N-1 (correção de Bessel) para estimativa não-enviesada
        return Math.sqrt(sum / (period - 1));
    }

    /**
     * Calcula o RSI utilizando o smoothing de Wilder (média exponencial).
     *
     * Fase 1 (seed): média simples dos ganhos e perdas dos primeiros {@code period} candles.
     * Fase 2 (smoothing): suavização exponencial com fator {@code (period-1)/period}.
     * Quando {@code avgLoss == 0}, retorna {@code 100.0} (sem perdas = sobrecompra máxima).
     *
     * @param bars   lista de candles; necessita de pelo menos {@code period + 1} candles
     * @param period período do RSI; clássico: 14
     * @return RSI entre 0 e 100, ou {@code NaN} se dados insuficientes
     */
    private static double rsiWilder(List<Bar> bars, int period) {
        if (bars.size() < period + 1) return Double.NaN;

        double[] changes = new double[bars.size() - 1];
        for (int i = 1; i < bars.size(); i++) {
            changes[i - 1] = bars.get(i).close() - bars.get(i - 1).close();
        }

        // Fase 1: seed com média simples dos primeiros N períodos
        double avgGain = 0.0;
        double avgLoss = 0.0;

        for (int i = 0; i < period; i++) {
            if (changes[i] > 0) avgGain += changes[i];
            else                 avgLoss += -changes[i];
        }

        avgGain /= period;
        avgLoss /= period;

        // Fase 2: Wilder smoothing com fator (period-1)/period
        for (int i = period; i < changes.length; i++) {
            double gain = changes[i] > 0 ? changes[i] : 0.0;
            double loss = changes[i] < 0 ? -changes[i] : 0.0;

            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;
        }

        // Sem perdas no período = sobrecompra máxima
        if (avgLoss == 0.0) return 100.0;

        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    /**
     * Calcula o ATR de 14 períodos utilizado no filtro de largura de banda.
     * Serve como referência de volatilidade "normal" para determinar se as bandas
     * estão suficientemente largas para que o sinal de reversão tenha significância.
     *
     * @param bars   lista de candles
     * @param period período do ATR
     * @return ATR calculado ou {@code NaN} se dados insuficientes
     */
    private static double calculateATR(List<Bar> bars, int period) {
        if (bars.size() < period) return Double.NaN;

        double sum = 0.0;
        for (int i = bars.size() - period; i < bars.size(); i++) {
            Bar    current = bars.get(i);
            double tr;

            if (i == bars.size() - period) {
                tr = current.high() - current.low();
            } else {
                Bar previous = bars.get(i - 1);
                tr = Math.max(
                        current.high() - current.low(),
                        Math.max(
                                Math.abs(current.high() - previous.close()),
                                Math.abs(current.low()  - previous.close())
                        )
                );
            }

            sum += tr;
        }

        return sum / period;
    }
}