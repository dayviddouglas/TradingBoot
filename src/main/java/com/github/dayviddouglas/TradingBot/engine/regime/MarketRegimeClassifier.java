package com.github.dayviddouglas.TradingBot.engine.regime;

import com.github.dayviddouglas.TradingBot.model.Bar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Responsável por classificar o regime de mercado a partir de uma janela de candles.
 *
 * Aplica três detectores em sequência sobre os indicadores calculados:
 * <ol>
 *   <li><b>Efficiency Ratio</b> — mede a linearidade do movimento do preço
 *       (Kaufman, 2013)</li>
 *   <li><b>Distância entre EMAs</b> — mede a separação entre a EMA rápida e a lenta,
 *       indicando presença ou ausência de momentum direcional</li>
 *   <li><b>ATR Ratio</b> — compara a volatilidade recente com a histórica,
 *       identificando compressão ou expansão de volatilidade</li>
 * </ol>
 *
 * Os três detectores são avaliados conjuntamente para classificar o regime em:
 * {@link MarketRegime#TRENDING}, {@link MarketRegime#RANGING} ou
 * {@link MarketRegime#CHOPPY} (fallback quando nenhuma condição é satisfeita).
 *
 * Os parâmetros foram recalibrados para candles de 1 minuto:
 * EMAs 8/21 substituem as anteriores 20/50, gerando distância 3-4x maior entre as linhas
 * e tornando o detector de tendência funcional nessa granularidade.
 * O Efficiency Ratio mínimo foi reduzido de 0.30 para 0.20, capturando tendências
 * moderadas reais em gráficos de 1 minuto.
 *
 * Distribuição esperada após recalibração:
 * {@code TRENDING ~15%} | {@code RANGING ~40%} | {@code CHOPPY ~45%}
 */
@Component
public class MarketRegimeClassifier {

    private static final Logger log =
            LoggerFactory.getLogger(MarketRegimeClassifier.class);

    // ═══════════════════════════════════════════════════════════════
    // Parâmetros dos indicadores
    // ═══════════════════════════════════════════════════════════════

    private final int atrFastPeriod;
    private final int atrBasePeriod;
    private final int emaFastPeriod;
    private final int emaSlowPeriod;

    /**
     * Lookback do Efficiency Ratio: 30 períodos equivalem a 15% da janela de 200 candles.
     * Captura a linearidade do movimento nos últimos 30 minutos em gráficos de 1 minuto.
     */
    private static final int EFFICIENCY_RATIO_LOOKBACK = 30;

    // ═══════════════════════════════════════════════════════════════
    // Thresholds — calibrados para candles de 1 minuto
    // ═══════════════════════════════════════════════════════════════

    /**
     * ER mínimo para classificar como {@code TRENDING}.
     * O preço deve ter percorrido pelo menos 20% do caminho em linha reta
     * nos últimos 30 minutos.
     */
    private static final double ER_TRENDING_MIN = 0.20;

    /**
     * ER máximo para classificar como {@code RANGING}.
     * O preço deve ter ficado dentro de uma faixa, com linearidade abaixo de 13%
     * nos últimos 30 minutos.
     */
    private static final double ER_RANGING_MAX = 0.13;

    /**
     * Fator mínimo de distância entre EMAs para {@code TRENDING}.
     * A distância entre EMA rápida e lenta deve superar 20% do ATR rápido,
     * indicando separação suficiente para confirmar momentum direcional.
     */
    private static final double EMA_DISTANCE_TRENDING_FACTOR = 0.20;

    /**
     * Fator máximo de distância entre EMAs para {@code RANGING}.
     * As EMAs devem estar coladas — distância inferior a 40% do ATR rápido —
     * indicando ausência de momentum direcional.
     */
    private static final double EMA_DISTANCE_RANGING_FACTOR = 0.40;

    /**
     * ATR Ratio mínimo para {@code TRENDING}.
     * A volatilidade recente deve ser pelo menos 90% da histórica,
     * descartando períodos de compressão extrema.
     */
    private static final double ATR_RATIO_TRENDING_MIN = 0.90;

    /**
     * ATR Ratio máximo para {@code RANGING}.
     * A volatilidade recente não pode exceder 120% da histórica,
     * descartando expansões abruptas de volatilidade.
     */
    private static final double ATR_RATIO_RANGING_MAX = 1.20;

    // ═══════════════════════════════════════════════════════════════
    // Construtores
    // ═══════════════════════════════════════════════════════════════

    /**
     * Construtor padrão calibrado para candles de 1 minuto.
     * Utiliza EMA 8/21, que gera distância entre linhas 3-4x maior
     * do que as anteriores EMA 20/50, tornando o detector de tendência funcional.
     */
    public MarketRegimeClassifier() {
        this(14, 50, 8, 21);
    }

    /**
     * Construtor parametrizável para testes e calibragem por ativo.
     *
     * @param atrFastPeriod período do ATR rápido, usado para medir volatilidade recente
     * @param atrBasePeriod período do ATR base, usado como referência histórica de volatilidade
     * @param emaFastPeriod período da EMA rápida, usada para calcular a distância entre médias
     * @param emaSlowPeriod período da EMA lenta, usada como referência de momentum
     */
    public MarketRegimeClassifier(
            int atrFastPeriod,
            int atrBasePeriod,
            int emaFastPeriod,
            int emaSlowPeriod
    ) {
        this.atrFastPeriod = atrFastPeriod;
        this.atrBasePeriod = atrBasePeriod;
        this.emaFastPeriod = emaFastPeriod;
        this.emaSlowPeriod = emaSlowPeriod;
    }

    // ═══════════════════════════════════════════════════════════════
    // API pública
    // ═══════════════════════════════════════════════════════════════

    /**
     * Classifica o regime e retorna apenas o {@link MarketRegime}.
     * Mantido para compatibilidade com o
     * {@link com.github.dayviddouglas.TradingBot.engine.confluence.WeightedConfluenceEvaluator}.
     *
     * @param bars janela de candles para análise
     * @return regime classificado
     */
    public MarketRegime classify(List<Bar> bars) {
        return classifyWithMetrics(bars).regime();
    }

    /**
     * Classifica o regime e retorna {@link RegimeMetrics} com todos os valores calculados.
     *
     * Os três detectores são avaliados na seguinte ordem:
     * <ul>
     *   <li><b>TRENDING</b>: ER {@code >=} 0.20 E distância entre EMAs {@code >} ATR × 0.20
     *       E atrRatio {@code >=} 0.90 — as três condições devem ser verdadeiras</li>
     *   <li><b>RANGING</b>: ER {@code <=} 0.13 E distância entre EMAs {@code <} ATR × 0.40
     *       E atrRatio {@code <=} 1.20 — as três condições devem ser verdadeiras</li>
     *   <li><b>CHOPPY</b>: fallback quando nenhuma das condições anteriores é satisfeita</li>
     * </ul>
     *
     * O {@code marketTimestamp} no {@link RegimeMetrics} retornado representa o timestamp
     * do último candle da janela analisada, garantindo que os relatórios utilizem o tempo
     * real de mercado da detecção em vez do tempo de processamento.
     *
     * @param bars janela de candles para análise; retorna {@code CHOPPY} com campos NaN
     *             quando a quantidade de candles for insuficiente
     * @return {@link RegimeMetrics} com regime classificado, indicadores calculados
     *         e timestamp real de mercado
     */
    public RegimeMetrics classifyWithMetrics(List<Bar> bars) {

        if (bars == null
                || bars.size() < Math.max(atrBasePeriod, emaSlowPeriod) + 10) {
            log.debug("REGIME CLASSIFIER | insufficient bars | size={}",
                    bars != null ? bars.size() : 0);
            return buildMetrics(
                    Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, MarketRegime.CHOPPY,
                    null);
        }

        // Timestamp do último candle — referência temporal real de mercado da detecção
        Instant marketTimestamp = bars.get(bars.size() - 1).timestamp();

        double atrFast    = atr(bars, atrFastPeriod);
        double atrBase    = atr(bars, atrBasePeriod);
        double emaFast    = ema(bars, emaFastPeriod);
        double emaSlow    = ema(bars, emaSlowPeriod);
        double efficiency = efficiencyRatio(bars, EFFICIENCY_RATIO_LOOKBACK);

        if (!Double.isFinite(atrFast) || !Double.isFinite(atrBase)
                || !Double.isFinite(emaFast)
                || !Double.isFinite(emaSlow)
                || atrBase <= 0) {
            log.debug("REGIME CLASSIFIER | invalid metrics | "
                            + "atrFast={} atrBase={} emaFast={} emaSlow={} efficiency={}",
                    atrFast, atrBase, emaFast, emaSlow, efficiency);
            return buildMetrics(
                    atrFast, atrBase,
                    Double.NaN, Double.NaN, efficiency,
                    MarketRegime.CHOPPY, marketTimestamp);
        }

        double atrRatio    = atrFast / atrBase;
        double emaDistance = Math.abs(emaFast - emaSlow);

        // Detector TRENDING: movimento linear, EMAs separadas e volatilidade ativa
        if (efficiency >= ER_TRENDING_MIN
                && emaDistance > atrFast * EMA_DISTANCE_TRENDING_FACTOR
                && atrRatio >= ATR_RATIO_TRENDING_MIN) {

            log.debug("REGIME CLASSIFIER | regime=TRENDING | "
                            + "efficiency={} emaDistance={} atrRatio={}",
                    efficiency, emaDistance, atrRatio);

            return buildMetrics(atrFast, atrBase, atrRatio,
                    emaDistance, efficiency, MarketRegime.TRENDING,
                    marketTimestamp);
        }

        // Detector RANGING: movimento lateral, EMAs coladas e volatilidade estável
        if (efficiency <= ER_RANGING_MAX
                && emaDistance < atrFast * EMA_DISTANCE_RANGING_FACTOR
                && atrRatio <= ATR_RATIO_RANGING_MAX) {

            log.debug("REGIME CLASSIFIER | regime=RANGING | "
                            + "efficiency={} emaDistance={} atrRatio={}",
                    efficiency, emaDistance, atrRatio);

            return buildMetrics(atrFast, atrBase, atrRatio,
                    emaDistance, efficiency, MarketRegime.RANGING,
                    marketTimestamp);
        }

        // Fallback CHOPPY: nenhum padrão estrutural identificado
        log.debug("REGIME CLASSIFIER | regime=CHOPPY | "
                        + "efficiency={} emaDistance={} atrRatio={}",
                efficiency, emaDistance, atrRatio);

        return buildMetrics(atrFast, atrBase, atrRatio,
                emaDistance, efficiency, MarketRegime.CHOPPY,
                marketTimestamp);
    }

    // ═══════════════════════════════════════════════════════════════
    // Indicadores técnicos
    // ═══════════════════════════════════════════════════════════════

    /**
     * Calcula o Average True Range (ATR) dos últimos {@code period} candles.
     * O True Range de cada candle é o maior entre:
     * {@code high - low}, {@code |high - close anterior|} e {@code |low - close anterior|}.
     * O primeiro candle da janela usa apenas {@code high - low} por não ter candle anterior.
     * Retorna {@code NaN} quando há menos candles disponíveis que o período.
     *
     * @param bars   lista de candles
     * @param period número de candles para o cálculo
     * @return ATR calculado ou {@code NaN} se dados insuficientes
     */
    private static double atr(List<Bar> bars, int period) {
        if (bars.size() < period) return Double.NaN;

        double sum = 0.0;
        for (int i = bars.size() - period; i < bars.size(); i++) {
            Bar    current = bars.get(i);
            double tr;

            if (i == bars.size() - period) {
                // Primeiro candle da janela: sem candle anterior disponível
                tr = current.high() - current.low();
            } else {
                Bar    previous  = bars.get(i - 1);
                double highLow   = current.high() - current.low();
                double highClose = Math.abs(current.high() - previous.close());
                double lowClose  = Math.abs(current.low()  - previous.close());
                tr = Math.max(highLow, Math.max(highClose, lowClose));
            }

            sum += tr;
        }

        return sum / period;
    }

    /**
     * Calcula a Exponential Moving Average (EMA) sobre os fechamentos dos candles.
     * Inicializa com a média simples dos primeiros {@code period} candles e aplica
     * suavização exponencial com fator {@code k = 2 / (period + 1)} nos candles seguintes.
     * Retorna {@code NaN} quando há menos candles disponíveis que o período.
     *
     * @param bars   lista de candles
     * @param period número de candles para o cálculo
     * @return EMA calculada ou {@code NaN} se dados insuficientes
     */
    private static double ema(List<Bar> bars, int period) {
        if (bars.size() < period) return Double.NaN;

        double k   = 2.0 / (period + 1.0);
        double ema = 0.0;

        // Seed: média simples dos primeiros N fechamentos
        for (int i = 0; i < period; i++) {
            ema += bars.get(i).close();
        }
        ema /= period;

        // Suavização exponencial nos candles restantes
        for (int i = period; i < bars.size(); i++) {
            ema = bars.get(i).close() * k + ema * (1.0 - k);
        }

        return ema;
    }

    /**
     * Calcula o Efficiency Ratio (ER) sobre os últimos {@code lookback} candles.
     * O ER mede a linearidade do movimento: razão entre a distância líquida percorrida
     * em linha reta e a soma total dos passos individuais (sem considerar direção).
     * {@code ER = 1.0} indica tendência perfeita; {@code ER = 0.0} indica retorno ao ponto inicial.
     * Retorna {@code 0.0} quando há dados insuficientes ou o movimento total é zero.
     *
     * @param bars     lista de candles
     * @param lookback número de candles para o cálculo
     * @return ER entre 0.0 e 1.0
     */
    private static double efficiencyRatio(List<Bar> bars, int lookback) {
        if (bars.size() < lookback + 1) return 0.0;

        int    start    = bars.size() - 1 - lookback;
        int    end      = bars.size() - 1;

        // Distância líquida: do ponto de partida ao ponto final em linha reta
        double netMove  = Math.abs(bars.get(end).close() - bars.get(start).close());

        // Soma de todos os passos individuais independentemente da direção
        double totalMove = 0.0;
        for (int i = start + 1; i <= end; i++) {
            totalMove += Math.abs(bars.get(i).close() - bars.get(i - 1).close());
        }

        if (totalMove == 0.0) return 0.0;

        return netMove / totalMove;
    }

    // ═══════════════════════════════════════════════════════════════
    // Factory de RegimeMetrics
    // ═══════════════════════════════════════════════════════════════

    /**
     * Constrói um {@link RegimeMetrics} com todos os valores calculados
     * e o timestamp real de mercado do último candle analisado.
     *
     * @param atrFast         ATR rápido calculado
     * @param atrBase         ATR base calculado
     * @param atrRatio        razão entre ATR rápido e ATR base
     * @param emaDistance     distância absoluta entre EMA rápida e lenta
     * @param efficiency      Efficiency Ratio calculado
     * @param regime          regime classificado pelos detectores
     * @param marketTimestamp timestamp do último candle da janela analisada
     * @return {@link RegimeMetrics} imutável com todos os campos
     */
    private static RegimeMetrics buildMetrics(
            double atrFast,
            double atrBase,
            double atrRatio,
            double emaDistance,
            double efficiency,
            MarketRegime regime,
            Instant marketTimestamp
    ) {
        return new RegimeMetrics(
                atrFast, atrBase, atrRatio,
                emaDistance, efficiency,
                regime, marketTimestamp);
    }
}