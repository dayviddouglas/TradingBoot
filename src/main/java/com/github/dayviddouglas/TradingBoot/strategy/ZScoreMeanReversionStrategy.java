package com.github.dayviddouglas.TradingBoot.strategy;

import com.github.dayviddouglas.TradingBoot.model.Bar;
import com.github.dayviddouglas.TradingBoot.model.Signal;

import java.util.List;
import java.util.Map;

/**
 * Estratégia de reversão à média baseada em Z-Score estatístico.
 *
 * O Z-Score mede quantos desvios padrão o preço atual está distante da média
 * dos últimos {@code period} candles: {@code zScore = (close - mean) / stdDev}.
 * Quando o desvio é extremo, a probabilidade de retorno à média aumenta:
 * <ul>
 *   <li>{@code BUY}: {@code zScore <= -entryZScore} — preço muito abaixo da média (oversold estatístico)</li>
 *   <li>{@code SELL}: {@code zScore >= +entryZScore} — preço muito acima da média (overbought estatístico)</li>
 * </ul>
 *
 * Em distribuição normal, ~95.4% dos dados estão dentro de ±2 desvios e ~99.7% dentro de ±3,
 * tornando o Z-Score um critério parametrizável de extremidade estatística.
 *
 * Diferencia-se da {@link BollingerMeanReversionStrategy} por medir diretamente o desvio
 * normalizado em vez de usar bandas visuais, sendo mais explicitamente estatístico com apenas
 * dois parâmetros de calibração. Ambas pertencem à mesma família de reversão à média e são
 * altamente correlacionadas.
 *
 * O desvio padrão utilizado é o populacional (divisor N), diferentemente da
 * {@link BollingerMeanReversionStrategy} que usa o amostral (divisor N-1).
 */
public class ZScoreMeanReversionStrategy implements TradingStrategy {

    /**
     * Período para cálculo da média e desvio padrão.
     * Valores maiores produzem menos sinais e mais estáveis; valores menores são mais reativos.
     */
    private final int period;

    /**
     * Z-Score mínimo (em módulo) para gerar sinal de entrada.
     * Com {@code entryZScore = 2.2}: BUY quando o preço está 2.2 desvios abaixo da média;
     * SELL quando está 2.2 desvios acima.
     */
    private final double entryZScore;

    /**
     * @param period      período para cálculo da média e desvio padrão; mínimo 5
     * @param entryZScore threshold de Z-Score para geração de sinal; deve ser positivo
     * @throws IllegalArgumentException se os parâmetros forem inválidos
     */
    public ZScoreMeanReversionStrategy(int period, double entryZScore) {
        if (period < 5) {
            throw new IllegalArgumentException("period must be >= 5");
        }
        if (entryZScore <= 0) {
            throw new IllegalArgumentException("entryZScore must be > 0");
        }

        this.period      = period;
        this.entryZScore = entryZScore;
    }

    /**
     * Identificador da estratégia utilizado em logs, metadata do sinal
     * e no {@link com.github.dayviddouglas.TradingBoot.engine.confluence.StrategyWeightProfile}.
     */
    @Override
    public String name() {
        return "ZScoreMeanReversion";
    }

    /**
     * Avalia se o preço atual está em desvio estatístico extremo em relação à média recente.
     *
     * Fluxo:
     * <ol>
     *   <li>Extrai os últimos {@code period} candles como janela de análise</li>
     *   <li>Calcula a média e o desvio padrão populacional dos fechamentos</li>
     *   <li>Descarta quando o desvio padrão for zero (mercado completamente parado)</li>
     *   <li>Calcula o Z-Score: {@code (close - mean) / stdDev}</li>
     *   <li>Z-Score extremamente negativo → BUY; extremamente positivo → SELL</li>
     * </ol>
     *
     * @param bars lista de candles para análise; mínimo {@code period} candles
     * @return {@link Signal} com tipo, timestamp, preço e metadata dos indicadores
     */
    @Override
    public Signal checkSignal(List<Bar> bars) {
        if (bars == null || bars.size() < period) {
            return Signal.none(name());
        }

        // Extrai a janela dos últimos N candles para cálculo das estatísticas
        int      from   = bars.size() - period;
        List<Bar> window = bars.subList(from, bars.size());

        double mean   = averageClose(window);
        double stdDev = standardDeviationClose(window, mean);

        // StdDev zero indica mercado sem variação — Z-Score seria indefinido
        if (!Double.isFinite(mean) || !Double.isFinite(stdDev) || stdDev <= 0.0) {
            return Signal.none(name());
        }

        Bar    last   = bars.get(bars.size() - 1);
        double close  = last.close();
        double zScore = (close - mean) / stdDev;

        // BUY: preço muito abaixo da média — oversold estatístico
        if (zScore <= -entryZScore) {
            return Signal.buy(
                    name(),
                    last.timestamp(),
                    close,
                    Map.of(
                            "mean",        mean,
                            "stdDev",      stdDev,
                            "zScore",      zScore,
                            "period",      period,
                            "entryZScore", entryZScore
                    )
            );
        }

        // SELL: preço muito acima da média — overbought estatístico
        if (zScore >= entryZScore) {
            return Signal.sell(
                    name(),
                    last.timestamp(),
                    close,
                    Map.of(
                            "mean",        mean,
                            "stdDev",      stdDev,
                            "zScore",      zScore,
                            "period",      period,
                            "entryZScore", entryZScore
                    )
            );
        }

        // Preço dentro da faixa normal — sem sinal
        return Signal.none(name());
    }

    /**
     * Calcula a média aritmética dos preços de fechamento da janela.
     *
     * @param bars candles da janela de análise
     * @return média dos fechamentos
     */
    private double averageClose(List<Bar> bars) {
        double sum = 0.0;
        for (Bar bar : bars) {
            sum += bar.close();
        }
        return sum / bars.size();
    }

    /**
     * Calcula o desvio padrão populacional (divisor N) dos preços de fechamento.
     * Utiliza divisor N em vez de N-1 (amostral), o que pode produzir diferença
     * significativa para períodos pequenos (abaixo de 20 candles).
     *
     * @param bars  candles da janela de análise
     * @param mean  média pré-calculada dos fechamentos
     * @return desvio padrão populacional
     */
    private double standardDeviationClose(List<Bar> bars, double mean) {
        double sumSq = 0.0;
        for (Bar bar : bars) {
            double diff = bar.close() - mean;
            sumSq += diff * diff;
        }
        // Divisor N (populacional)
        return Math.sqrt(sumSq / bars.size());
    }
}