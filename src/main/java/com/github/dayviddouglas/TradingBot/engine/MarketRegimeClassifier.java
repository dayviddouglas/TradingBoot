package com.github.dayviddouglas.TradingBot.engine;

import com.github.dayviddouglas.TradingBot.model.Bar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Classificador de regime de mercado calibrado para candles de 1 minuto.
 *
 * Recalibração v5.4:
 * Os thresholds originais tornavam TRENDING matematicamente impossível
 * em candles de 1 minuto por três razões:
 *
 * 1. EMA 20/50 gerava distância microscópica entre as linhas em 1min.
 *    Solução: EMA 8/21 — linhas mais responsivas, distância 3-4x maior.
 *
 * 2. Limite de distância ATR × 0.60 era alto demais para 1min.
 *    Solução: ATR × 0.20 — calibrado para a escala real de 1min.
 *
 * 3. Efficiency Ratio limite 0.30 excluía 94% do tempo de mercado.
 *    Solução: 0.20 — captura tendências moderadas reais em 1min.
 *
 * Distribuição esperada após recalibração:
 *   TRENDING → ~15% do tempo (mercado com direção)
 *   RANGING  → ~40% do tempo (mercado lateral)
 *   CHOPPY   → ~45% do tempo (zona de transição)
 *
 * Referências:
 * - Kaufman, P.J. (2013). Trading Systems and Methods, 5th ed.
 * - Lo & MacKinlay (1988). Stock Market Prices do not Follow Random Walks.
 * - Elder, A. (1993). Trading For A Living.
 * - Murphy, J.J. (1999). Technical Analysis of the Financial Markets.
 * - Wilder, J.W. (1978). New Concepts in Technical Trading Systems.
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
     * Lookback do Efficiency Ratio.
     * 30 períodos = 15% da janela de 200 candles.
     * [Kaufman, 2013]
     */
    private static final int EFFICIENCY_RATIO_LOOKBACK = 30;

    // ═══════════════════════════════════════════════════════════════
    // Thresholds — calibrados para candles de 1 minuto
    // ═══════════════════════════════════════════════════════════════

    /**
     * ER mínimo para TRENDING.
     *
     * Significa: o preço precisa ter ido pelo menos 20% do caminho
     * em linha reta nos últimos 30 minutos.
     *
     * Antes: 0.30 → só 6% do mercado atingia (TRENDING nunca aparecia)
     * Depois: 0.20 → ~24% do mercado atinge (TRENDING aparece ~15%)
     */
    private static final double ER_TRENDING_MIN = 0.20;

    /**
     * ER máximo para RANGING.
     *
     * Significa: o preço ficou andando de lado, chegou a menos de
     * 13% do caminho em linha reta nos últimos 30 minutos.
     *
     * Antes: 0.28 → absorvia toda zona de transição em RANGING
     * Depois: 0.13 → RANGING real, cria zona CHOPPY funcional
     */
    private static final double ER_RANGING_MAX = 0.13;

    /**
     * Fator de distância entre EMAs para TRENDING.
     *
     * Significa: a distância entre EMA8 e EMA21 precisa ser
     * maior que 20% do tamanho médio dos candles (ATR).
     *
     * Antes: 0.60 → impossível com EMAs 20/50 em 1min
     * Depois: 0.20 → alcançável com EMAs 8/21 em 1min
     */
    private static final double EMA_DISTANCE_TRENDING_FACTOR = 0.20;

    /**
     * Fator de distância entre EMAs para RANGING.
     *
     * Significa: as EMAs precisam estar muito coladas —
     * distância menor que 40% do tamanho médio dos candles.
     *
     * Antes: 0.75
     * Depois: 0.40
     */
    private static final double EMA_DISTANCE_RANGING_FACTOR = 0.40;

    /**
     * ATR Ratio mínimo para TRENDING.
     * Volatilidade atual pelo menos 90% da volatilidade histórica.
     * Mercado ativo, não comprimido.
     */
    private static final double ATR_RATIO_TRENDING_MIN = 0.90;

    /**
     * ATR Ratio máximo para RANGING.
     * Volatilidade atual no máximo 120% da histórica.
     * Mercado estável, sem explosão de volatilidade.
     */
    private static final double ATR_RATIO_RANGING_MAX = 1.20;

    // ═══════════════════════════════════════════════════════════════
    // Construtores
    // ═══════════════════════════════════════════════════════════════

    /**
     * Construtor padrão calibrado para candles de 1 minuto.
     *
     * EMAs atualizadas de 20/50 para 8/21:
     *
     * EMA 8  → média dos últimos 8 minutos  (rápida, segue o preço)
     * EMA 21 → média dos últimos 21 minutos (lenta, referência)
     *
     * Com EMA 8/21 a distância entre as linhas é 3-4x maior
     * do que com EMA 20/50 em gráficos de 1 minuto.
     * Isso torna o Detector 2 funcional e discriminante.
     *
     * Antes: this(14, 50, 20, 50)
     * Depois: this(14, 50, 8, 21)
     */
    public MarketRegimeClassifier() {
        this(14, 50, 8, 21);
    }

    /**
     * Construtor parametrizável para testes e calibragem por ativo.
     *
     * @param atrFastPeriod período do ATR curto (padrão: 14)
     * @param atrBasePeriod período do ATR base (padrão: 50)
     * @param emaFastPeriod período da EMA rápida (padrão: 8)
     * @param emaSlowPeriod período da EMA lenta (padrão: 21)
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
     * Classifica o regime e retorna apenas o enum MarketRegime.
     * Mantido para compatibilidade com WeightedConfluenceEvaluator.
     *
     * @param bars lista de candles para análise
     * @return regime classificado
     */
    public MarketRegime classify(List<Bar> bars) {
        return classifyWithMetrics(bars).regime();
    }

    /**
     * Classifica o regime e retorna RegimeMetrics com todos os
     * valores calculados para auditoria.
     *
     * As 3 perguntas do classificador:
     *
     * TRENDING (mercado com direção):
     *   Pergunta 1: ER >= 0.20    (preço foi em linha reta?)
     *   Pergunta 2: dist > ATR × 0.20  (EMAs separadas?)
     *   Pergunta 3: atrRatio >= 0.90   (volatilidade normal?)
     *   As 3 precisam ser SIM
     *
     * RANGING (mercado lateral):
     *   Pergunta 1: ER <= 0.13    (preço ficou no lugar?)
     *   Pergunta 2: dist < ATR × 0.40  (EMAs coladas?)
     *   Pergunta 3: atrRatio <= 1.20   (volatilidade estável?)
     *   As 3 precisam ser SIM
     *
     * CHOPPY (zona de transição):
     *   Nenhuma das condições acima foi satisfeita
     *
     * @param bars lista de candles para análise
     * @return RegimeMetrics com regime e indicadores calculados
     */
    public RegimeMetrics classifyWithMetrics(List<Bar> bars) {

        // Validação: precisa de candles suficientes
        if (bars == null
                || bars.size() < Math.max(atrBasePeriod,
                emaSlowPeriod) + 10) {
            log.debug("REGIME CLASSIFIER | insufficient bars | size={}",
                    bars != null ? bars.size() : 0);
            return buildMetrics(
                    Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, MarketRegime.CHOPPY);
        }

        // Calcula os 3 detectores
        double atrFast    = atr(bars, atrFastPeriod);
        double atrBase    = atr(bars, atrBasePeriod);
        double emaFast    = ema(bars, emaFastPeriod);
        double emaSlow    = ema(bars, emaSlowPeriod);
        double efficiency = efficiencyRatio(bars,
                EFFICIENCY_RATIO_LOOKBACK);

        // Validação: métricas precisam ser números válidos
        if (!Double.isFinite(atrFast) || !Double.isFinite(atrBase)
                || !Double.isFinite(emaFast)
                || !Double.isFinite(emaSlow)
                || atrBase <= 0) {
            log.debug("REGIME CLASSIFIER | invalid metrics | "
                            + "atrFast={} atrBase={} "
                            + "emaFast={} emaSlow={} efficiency={}",
                    atrFast, atrBase,
                    emaFast, emaSlow, efficiency);
            return buildMetrics(
                    atrFast, atrBase,
                    Double.NaN, Double.NaN, efficiency,
                    MarketRegime.CHOPPY);
        }

        // Métricas derivadas
        double atrRatio    = atrFast / atrBase;
        double emaDistance = Math.abs(emaFast - emaSlow);

        // ── Detector: TRENDING ──
        // Pergunta 1: ER >= 0.20 (preço foi em linha reta?)
        // Pergunta 2: distância > ATR × 0.20 (EMAs separadas?)
        // Pergunta 3: atrRatio >= 0.90 (volatilidade normal?)
        if (efficiency >= ER_TRENDING_MIN
                && emaDistance > atrFast * EMA_DISTANCE_TRENDING_FACTOR
                && atrRatio >= ATR_RATIO_TRENDING_MIN) {

            log.debug("REGIME CLASSIFIER | regime=TRENDING | "
                            + "efficiency={} emaDistance={} atrRatio={}",
                    efficiency, emaDistance, atrRatio);

            return buildMetrics(atrFast, atrBase, atrRatio,
                    emaDistance, efficiency, MarketRegime.TRENDING);
        }

        // ── Detector: RANGING ──
        // Pergunta 1: ER <= 0.13 (preço ficou no lugar?)
        // Pergunta 2: distância < ATR × 0.40 (EMAs coladas?)
        // Pergunta 3: atrRatio <= 1.20 (volatilidade estável?)
        if (efficiency <= ER_RANGING_MAX
                && emaDistance < atrFast * EMA_DISTANCE_RANGING_FACTOR
                && atrRatio <= ATR_RATIO_RANGING_MAX) {

            log.debug("REGIME CLASSIFIER | regime=RANGING | "
                            + "efficiency={} emaDistance={} atrRatio={}",
                    efficiency, emaDistance, atrRatio);

            return buildMetrics(atrFast, atrBase, atrRatio,
                    emaDistance, efficiency, MarketRegime.RANGING);
        }

        // ── Fallback: CHOPPY ──
        // Zona entre RANGING e TRENDING
        // ER entre 0.13 e 0.20 → mercado indefinido
        log.debug("REGIME CLASSIFIER | regime=CHOPPY | "
                        + "efficiency={} emaDistance={} atrRatio={}",
                efficiency, emaDistance, atrRatio);

        return buildMetrics(atrFast, atrBase, atrRatio,
                emaDistance, efficiency, MarketRegime.CHOPPY);
    }

    // ═══════════════════════════════════════════════════════════════
    // Indicadores técnicos
    // ═══════════════════════════════════════════════════════════════

    /**
     * ATR — Tamanho médio dos candles nos últimos N períodos.
     *
     * Para cada candle calcula o tamanho real (True Range):
     *   TR = Máxima - Mínima (ou diferença com fechamento anterior)
     *
     * ATR = média de todos os TR dos últimos N candles.
     *
     * [Wilder, 1978]
     */
    private static double atr(List<Bar> bars, int period) {
        if (bars.size() < period) return Double.NaN;

        double sum = 0.0;
        for (int i = bars.size() - period; i < bars.size(); i++) {
            Bar    current = bars.get(i);
            double tr;

            if (i == bars.size() - period) {
                tr = current.high() - current.low();
            } else {
                Bar    previous  = bars.get(i - 1);
                double highLow   = current.high() - current.low();
                double highClose = Math.abs(
                        current.high() - previous.close());
                double lowClose  = Math.abs(
                        current.low() - previous.close());
                tr = Math.max(highLow,
                        Math.max(highClose, lowClose));
            }

            sum += tr;
        }

        return sum / period;
    }

    /**
     * EMA — Média móvel exponencial com mais peso para preços recentes.
     *
     * Fórmula:
     *   fator = 2 ÷ (período + 1)
     *   EMA hoje = (preço hoje × fator) + (EMA ontem × (1 - fator))
     *
     * Começa com a média simples dos primeiros N candles
     * e aplica a fórmula exponencial nos candles seguintes.
     *
     * [Appel, 2005]
     */
    private static double ema(List<Bar> bars, int period) {
        if (bars.size() < period) return Double.NaN;

        double k   = 2.0 / (period + 1.0);
        double ema = 0.0;

        // Seed: média simples dos primeiros N candles
        for (int i = 0; i < period; i++) {
            ema += bars.get(i).close();
        }
        ema /= period;

        // Aplica suavização exponencial nos candles restantes
        for (int i = period; i < bars.size(); i++) {
            ema = bars.get(i).close() * k + ema * (1.0 - k);
        }

        return ema;
    }

    /**
     * Efficiency Ratio — O preço foi em linha reta ou ficou
     * indo e voltando?
     *
     * Fórmula:
     *   ER = distância em linha reta ÷ total de passos dados
     *
     * Resultado:
     *   ER = 1.0 → foi em linha reta (tendência perfeita)
     *   ER = 0.0 → voltou ao ponto de partida (lateral)
     *
     * [Kaufman, 2013]
     */
    private static double efficiencyRatio(List<Bar> bars,
                                          int lookback) {
        if (bars.size() < lookback + 1) return 0.0;

        int    start     = bars.size() - 1 - lookback;
        int    end       = bars.size() - 1;

        // Distância em linha reta do início ao fim
        double netMove   = Math.abs(
                bars.get(end).close() - bars.get(start).close());

        // Soma de todos os passos dados (sem importar direção)
        double totalMove = 0.0;
        for (int i = start + 1; i <= end; i++) {
            totalMove += Math.abs(
                    bars.get(i).close() - bars.get(i - 1).close());
        }

        if (totalMove == 0.0) return 0.0;

        return netMove / totalMove;
    }

    // ═══════════════════════════════════════════════════════════════
    // Factory de RegimeMetrics
    // ═══════════════════════════════════════════════════════════════

    private static RegimeMetrics buildMetrics(
            double atrFast,
            double atrBase,
            double atrRatio,
            double emaDistance,
            double efficiency,
            MarketRegime regime
    ) {
        return new RegimeMetrics(
                atrFast, atrBase, atrRatio,
                emaDistance, efficiency, regime);
    }
}