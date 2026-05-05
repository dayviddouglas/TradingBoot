package com.github.dayviddouglas.TradingBot.strategy;

import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.model.Signal;

import java.util.List;
import java.util.Map;

/**
 * Estratégia de reversão à média baseada em Z-Score estatístico.
 *
 * Implementa TradingStrategy para uso pelo StrategyEngine.
 *
 * Conceito:
 * O Z-Score mede quantos desvios padrão o preço atual está distante
 * da média recente. Quando o desvio é extremo (preço "longe demais"),
 * a probabilidade de reversão à média aumenta.
 *
 * Fórmula:
 *   zScore = (close - mean) / stdDev
 *
 * Interpretação:
 * - zScore = 0: preço está exatamente na média
 * - zScore = +2: preço está 2 desvios acima da média (estatisticamente raro)
 * - zScore = -2: preço está 2 desvios abaixo da média (estatisticamente raro)
 *
 * Em distribuição normal:
 * - ~95.4% dos dados estão dentro de ±2 desvios
 * - ~99.7% dos dados estão dentro de ±3 desvios
 *
 * Lógica operacional:
 * - BUY quando zScore <= -entryZScore (preço muito abaixo da média)
 * - SELL quando zScore >= +entryZScore (preço muito acima da média)
 *
 * Motivação para criação:
 * A família de reversão foi a única com pista real de edge no projeto.
 * O Z-Score foi adicionado para complementar a BollingerMeanReversion
 * com uma abordagem mais explicitamente estatística e parametrizável.
 *
 * Diferença em relação ao Bollinger:
 * - Bollinger usa bandas visuais e posição relativa dentro delas
 * - ZScore mede diretamente o desvio estatístico normalizado
 * - ZScore é mais fácil de calibrar (apenas 2 parâmetros)
 * - Ambas são da mesma família (mean reversion), portanto altamente correlacionadas
 *
 * Classificada como REVERSAL_RANGE pelo projeto.
 * Recebe peso alto em RANGING no StrategyWeightProfile.
 *
 * ⚠️ Ponto de atenção: O desvio padrão usado aqui é POPULACIONAL (N),
 * diferente da BollingerMeanReversionStrategy que usa AMOSTRAL (N-1).
 * Para períodos pequenos (< 20), essa diferença pode ser significativa.
 * Considere padronizar para N-1 por consistência.
 *
 * ⚠️ Ponto de atenção: O name() retorna "ZScoreMeanReversion" (sem sufixo),
 * enquanto o Bollinger retorna "BollingerMeanReversionStrategy" (com sufixo).
 * Essa inconsistência pode causar problemas no StrategyWeightProfile e
 * no AtrRiskManager, onde os nomes precisam bater exatamente.
 *
 * Referências:
 * - [Jegadeesh, 1990, Journal of Finance]
 * - [Lo & MacKinlay, 1990, Review of Financial Studies]
 *
 * @see BollingerMeanReversionStrategy para abordagem visual com bandas
 */
public class ZScoreMeanReversionStrategy implements TradingStrategy {

    /**
     * Período para cálculo da média e desvio padrão.
     * Quanto maior, mais suave (menos sinais, mais confiáveis).
     * Quanto menor, mais reativo (mais sinais, mais ruído).
     * Valor típico: 20.
     */
    private final int period;

    /**
     * Z-Score mínimo para gerar sinal de entrada.
     * Valor típico: 2.0 a 2.5.
     * Valores maiores = sinais mais raros mas mais extremos.
     *
     * Com entryZScore = 2.2:
     * - BUY quando preço está 2.2 desvios ABAIXO da média
     * - SELL quando preço está 2.2 desvios ACIMA da média
     */
    private final double entryZScore;

    /**
     * Construtor com validação de parâmetros.
     *
     * @param period período para média e desvio padrão (mínimo 5)
     * @param entryZScore threshold de Z-Score para entrada (deve ser > 0)
     */
    public ZScoreMeanReversionStrategy(int period, double entryZScore) {
        if (period < 5) {
            throw new IllegalArgumentException("period must be >= 5");
        }
        if (entryZScore <= 0) {
            throw new IllegalArgumentException("entryZScore must be > 0");
        }

        this.period = period;
        this.entryZScore = entryZScore;
    }

    /**
     * Nome da estratégia para identificação.
     *
     * ⚠️ Retorna "ZScoreMeanReversion" sem o sufixo "Strategy",
     * diferente de outras estratégias como "BollingerMeanReversionStrategy".
     * Isso deve bater com as chaves no StrategyWeightProfile.
     */
    @Override
    public String name() {
        return "ZScoreMeanReversion";
    }

    /**
     * Avalia se o preço está em desvio estatístico extremo.
     *
     * Fluxo:
     * 1. Extrai a janela dos últimos N candles
     * 2. Calcula média e desvio padrão dos closes
     * 3. Calcula Z-Score: (close - mean) / stdDev
     * 4. Se zScore <= -entryZScore → BUY (preço muito abaixo)
     * 5. Se zScore >= +entryZScore → SELL (preço muito acima)
     * 6. Caso contrário → NONE
     *
     * O metadata usa Map.of() (imutável) porque os parâmetros são
     * poucos e todos conhecidos no momento da criação.
     *
     * @param bars lista de candles para análise
     * @return Signal (BUY, SELL ou NONE) com metadata incluindo zScore calculado
     */
    @Override
    public Signal checkSignal(List<Bar> bars) {
        if (bars == null || bars.size() < period) {
            return Signal.none(name());
        }

        // Extrai os últimos N candles como janela de análise
        int from = bars.size() - period;
        List<Bar> window = bars.subList(from, bars.size());

        // Calcula estatísticas descritivas
        double mean = averageClose(window);
        double stdDev = standardDeviationClose(window, mean);

        // Validação: stdDev zero indica mercado completamente parado
        if (!Double.isFinite(mean) || !Double.isFinite(stdDev) || stdDev <= 0.0) {
            return Signal.none(name());
        }

        Bar last = bars.get(bars.size() - 1);
        double close = last.close();

        // ── Cálculo do Z-Score ──
        // Quantos desvios padrão o preço está distante da média
        double zScore = (close - mean) / stdDev;

        // ── BUY: preço muito abaixo da média ──
        // Z-Score negativo extremo indica oversold estatístico
        // Expectativa: preço tende a retornar em direção à média
        if (zScore <= -entryZScore) {
            return Signal.buy(
                    name(),
                    last.timestamp(),
                    close,
                    Map.of(
                            "mean", mean,
                            "stdDev", stdDev,
                            "zScore", zScore,
                            "period", period,
                            "entryZScore", entryZScore
                    )
            );
        }

        // ── SELL: preço muito acima da média ──
        // Z-Score positivo extremo indica overbought estatístico
        if (zScore >= entryZScore) {
            return Signal.sell(
                    name(),
                    last.timestamp(),
                    close,
                    Map.of(
                            "mean", mean,
                            "stdDev", stdDev,
                            "zScore", zScore,
                            "period", period,
                            "entryZScore", entryZScore
                    )
            );
        }

        // Preço dentro da faixa normal: sem sinal
        return Signal.none(name());
    }

    /**
     * Calcula a média aritmética dos preços de fechamento.
     *
     * @param bars lista de candles da janela de análise
     * @return média dos closes
     */
    private double averageClose(List<Bar> bars) {
        double sum = 0.0;
        for (Bar bar : bars) {
            sum += bar.close();
        }
        return sum / bars.size();
    }

    /**
     * Calcula o desvio padrão POPULACIONAL dos preços de fechamento.
     *
     * Usa N (populacional) em vez de N-1 (amostral).
     *
     * ⚠️ Ponto de atenção: A BollingerMeanReversionStrategy usa N-1 (amostral).
     * Para consistência, considere padronizar ambas para o mesmo método.
     * A diferença é mais significativa para períodos pequenos (< 20).
     *
     * @param bars lista de candles da janela
     * @param mean média pré-calculada
     * @return desvio padrão populacional
     */
    private double standardDeviationClose(List<Bar> bars, double mean) {
        double sumSq = 0.0;
        for (Bar bar : bars) {
            double diff = bar.close() - mean;
            sumSq += diff * diff;
        }
        // N (populacional) — considere usar N-1 para consistência com Bollinger
        return Math.sqrt(sumSq / bars.size());
    }
}