package com.github.dayviddouglas.TradingBoot.risk;

import com.github.dayviddouglas.TradingBoot.model.Bar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Responsável por avaliar o risco de cada operação com base na volatilidade
 * atual do ativo medida pelo ATR, retornando uma decisão de permitir,
 * reduzir o stake ou bloquear o trade.
 *
 * A avaliação considera dois fatores principais:
 * <ul>
 *   <li><b>Tipo de confluência</b>: classifica as estratégias decisoras como
 *       {@code TREND_BREAKOUT} ou {@code REVERSAL_RANGE} e aplica thresholds
 *       distintos para cada perfil de operação</li>
 *   <li><b>ATR Ratio</b>: razão entre o ATR rápido (14 períodos) e o ATR baseline
 *       ajustado por sessão de mercado. Quando o ratio indica volatilidade
 *       excessiva para o tipo de operação, o stake é reduzido proporcionalmente
 *       ou o trade é bloqueado</li>
 * </ul>
 *
 * A redução de stake é proporcional à posição do ATR Ratio entre os thresholds
 * de allow e block: quanto mais próximo do block, menor o stake resultante.
 *
 * Métricas acumuladas ({@code totalEvaluations}, {@code totalAllowed},
 * {@code totalReduced}, {@code totalBlocked}) são mantidas via {@link AtomicLong}
 * para consulta via {@link #getStatistics()}.
 */
@Component
public class AtrRiskManager {

    private static final Logger log = LoggerFactory.getLogger(AtrRiskManager.class);

    /** Período do ATR rápido, usado para medir a volatilidade recente. */
    private static final int ATR_FAST_PERIOD = 14;

    /** Período base do ATR de referência histórica; ajustado por sessão de mercado. */
    private static final int ATR_BASELINE_PERIOD = 50;

    /**
     * Estratégias classificadas como tendência/breakout.
     * Recebem thresholds mais permissivos de volatilidade, pois operam
     * a favor do movimento e toleram maior volatilidade.
     */
    private static final Set<String> TREND_BREAKOUT_STRATEGIES = Set.of(
            "EmaRsiStrategy",
            "BreakoutStrategy",
            "KeltnerChannelStrategy",
            "DonchianBreakoutStrategy"
    );

    /**
     * Estratégias classificadas como reversão/range.
     * Recebem thresholds mais conservadores, pois operam contra o movimento
     * e são mais sensíveis a explosões de volatilidade.
     */
    private static final Set<String> REVERSAL_RANGE_STRATEGIES = Set.of(
            "BollingerMeanReversionStrategy",
            "PinBarStrategy",
            "SupportResistanceStrategy"
    );

    /** ATR Ratio até o qual o stake completo é liberado para operações de tendência. */
    private static final double TREND_ALLOW_THRESHOLD   = 1.5;

    /** ATR Ratio a partir do qual o trade é bloqueado para operações de tendência. */
    private static final double TREND_BLOCK_THRESHOLD   = 2.2;

    /** ATR Ratio até o qual o stake completo é liberado para operações de reversão. */
    private static final double REVERSAL_ALLOW_THRESHOLD = 1.15;

    /** ATR Ratio a partir do qual o trade é bloqueado para operações de reversão. */
    private static final double REVERSAL_BLOCK_THRESHOLD = 1.5;

    private final AtomicLong totalEvaluations = new AtomicLong(0);
    private final AtomicLong totalAllowed     = new AtomicLong(0);
    private final AtomicLong totalReduced     = new AtomicLong(0);
    private final AtomicLong totalBlocked     = new AtomicLong(0);

    /**
     * Avalia o risco da operação e retorna a decisão com o stake ajustado.
     *
     * Quando os dados forem insuficientes para calcular os ATRs, o trade é
     * liberado com o stake original e o {@code riskMode} indica a causa.
     * Quando o ATR baseline for zero, idem. Esses casos evitam que
     * falhas de cálculo bloqueiem operações indevidamente.
     *
     * @param symbol             símbolo do ativo
     * @param bars               histórico de candles; deve conter pelo menos
     *                           {@value ATR_BASELINE_PERIOD} barras para cálculo completo
     * @param baseAmount         stake base configurado no strategies.json
     * @param decisionStrategies nomes das estratégias que geraram o sinal,
     *                           usados para classificar o tipo de confluência
     * @return decisão de risco com stake ajustado, modo, métricas e motivo
     */
    public AtrRiskDecision evaluate(
            String symbol,
            List<Bar> bars,
            double baseAmount,
            List<String> decisionStrategies
    ) {
        totalEvaluations.incrementAndGet();

        // Dados insuficientes para calcular ATR — libera com stake original
        if (bars == null || bars.size() < ATR_BASELINE_PERIOD) {
            log.warn("ATR RISK SKIP | symbol={} | reason=insufficient bars ({} < {})",
                    symbol, bars != null ? bars.size() : 0, ATR_BASELINE_PERIOD);
            return new AtrRiskDecision(
                    true, baseAmount, "INSUFFICIENT_DATA",
                    0.0, 0.0, 0.0, "UNKNOWN",
                    "Not enough bars for ATR calculation");
        }

        // Sem estratégias decisoras — impossível classificar o tipo de confluência
        if (decisionStrategies == null || decisionStrategies.isEmpty()) {
            log.warn("ATR RISK SKIP | symbol={} | reason=no decision strategies available", symbol);
            return new AtrRiskDecision(
                    true, baseAmount, "NO_STRATEGIES",
                    0.0, 0.0, 0.0, "UNKNOWN",
                    "No decision strategies available");
        }

        double atrFast    = calculateATR(bars, ATR_FAST_PERIOD);
        Bar    lastBar    = bars.get(bars.size() - 1);
        double atrBaseline = calculateBaselineATR(bars, lastBar.timestamp());

        // ATR baseline zero impede o cálculo do ratio — libera com stake original
        if (atrBaseline == 0.0) {
            log.warn("ATR RISK SKIP | symbol={} | reason=baseline ATR is zero", symbol);
            return new AtrRiskDecision(
                    true, baseAmount, "ZERO_BASELINE",
                    atrFast, atrBaseline, 0.0, "UNKNOWN",
                    "Baseline ATR is zero");
        }

        double atrRatio      = atrFast / atrBaseline;
        String confluenceType = classifyConfluence(decisionStrategies);

        // Seleciona os thresholds conforme o perfil de operação
        double allowThreshold = "TREND_BREAKOUT".equals(confluenceType)
                ? TREND_ALLOW_THRESHOLD
                : REVERSAL_ALLOW_THRESHOLD;
        double blockThreshold = "TREND_BREAKOUT".equals(confluenceType)
                ? TREND_BLOCK_THRESHOLD
                : REVERSAL_BLOCK_THRESHOLD;

        // Stake ajustado por interpolação linear entre allow e block threshold
        double adjustedAmount = calculateProportionalStake(
                baseAmount, atrRatio, allowThreshold, blockThreshold);

        boolean allowTrade;
        String  riskMode;
        String  reason;

        if (adjustedAmount == 0.0) {
            allowTrade = false;
            riskMode   = "BLOCK";
            reason     = String.format("Volatility too high for %s setup", confluenceType);

            totalBlocked.incrementAndGet();
            long blockRate = (totalBlocked.get() * 100) / totalEvaluations.get();

            log.error("ATR RISK BLOCK | symbol={} | type={} | atrFast={} | atrBase={} "
                            + "| atrRatio={} | blockRate={}% | reason={}",
                    symbol, confluenceType,
                    String.format("%.6f", atrFast),
                    String.format("%.6f", atrBaseline),
                    String.format("%.2f", atrRatio),
                    blockRate, reason);

        } else if (adjustedAmount < baseAmount) {
            allowTrade = true;
            riskMode   = "REDUCE_STAKE";
            reason     = String.format("ATR elevated for %s setup", confluenceType);

            totalReduced.incrementAndGet();
            long reduceRate = (totalReduced.get() * 100) / totalEvaluations.get();

            log.warn("ATR RISK REDUCE | symbol={} | type={} | atrFast={} | atrBase={} "
                            + "| atrRatio={} | amount={} -> {} ({:.1f}%) | reduceRate={}%",
                    symbol, confluenceType,
                    String.format("%.6f", atrFast),
                    String.format("%.6f", atrBaseline),
                    String.format("%.2f", atrRatio),
                    baseAmount, adjustedAmount,
                    (adjustedAmount / baseAmount * 100), reduceRate);

        } else {
            allowTrade = true;
            riskMode   = "ALLOW";
            reason     = "ATR within acceptable range";

            totalAllowed.incrementAndGet();

            log.info("ATR RISK OK | symbol={} | type={} | atrFast={} | atrBase={} "
                            + "| atrRatio={} | amount={}",
                    symbol, confluenceType,
                    String.format("%.6f", atrFast),
                    String.format("%.6f", atrBaseline),
                    String.format("%.2f", atrRatio),
                    adjustedAmount);
        }

        return new AtrRiskDecision(
                allowTrade, adjustedAmount, riskMode,
                atrFast, atrBaseline, atrRatio,
                confluenceType, reason);
    }

    /**
     * Calcula o stake ajustado por interpolação linear entre os thresholds.
     *
     * <ul>
     *   <li>{@code atrRatio <= allowThreshold}: stake completo ({@code baseAmount})</li>
     *   <li>{@code atrRatio >= blockThreshold}: stake zero (trade bloqueado)</li>
     *   <li>zona intermediária: redução proporcional à posição entre os dois thresholds</li>
     * </ul>
     *
     * @param baseAmount     stake original configurado
     * @param atrRatio       razão entre ATR rápido e baseline
     * @param allowThreshold limiar inferior do range de ajuste
     * @param blockThreshold limiar superior do range de ajuste
     * @return stake ajustado entre {@code baseAmount} e {@code 0.0}
     */
    private double calculateProportionalStake(
            double baseAmount,
            double atrRatio,
            double allowThreshold,
            double blockThreshold
    ) {
        if (atrRatio <= allowThreshold) return baseAmount;
        if (atrRatio >= blockThreshold) return 0.0;

        // Interpolação linear: quanto mais próximo do blockThreshold, menor o stake
        double range           = blockThreshold - allowThreshold;
        double distance        = atrRatio - allowThreshold;
        double reductionFactor = 1.0 - (distance / range);

        return baseAmount * reductionFactor;
    }

    /**
     * Calcula o ATR baseline com o período ajustado pela sessão de mercado do timestamp.
     *
     * Diferentes sessões apresentam perfis distintos de volatilidade:
     * <ul>
     *   <li>Sessão asiática (00h-08h UTC): volatilidade baixa → período 100 (baseline mais estável)</li>
     *   <li>Sessão londrina (08h-16h UTC): volatilidade moderada-alta → período 50</li>
     *   <li>Sessão NY e overlap (13h-21h UTC): volatilidade alta → período 50</li>
     *   <li>Transição: período 70</li>
     * </ul>
     *
     * @param bars      histórico de candles
     * @param timestamp timestamp do último candle para identificar a sessão; usa padrão se nulo
     * @return ATR calculado com período ajustado pela sessão
     */
    private double calculateBaselineATR(List<Bar> bars, Instant timestamp) {
        if (timestamp == null) {
            return calculateATR(bars, ATR_BASELINE_PERIOD);
        }

        int hour = timestamp.atZone(ZoneOffset.UTC).getHour();

        int adjustedPeriod;

        if (hour >= 8 && hour < 16) {
            // Sessão londrina: volatilidade moderada-alta
            adjustedPeriod = 50;
        } else if (hour >= 13 && hour < 21) {
            // Sessão NY e overlap: volatilidade alta
            adjustedPeriod = 50;
        } else if (hour >= 0 && hour < 8) {
            // Sessão asiática: volatilidade baixa — período maior estabiliza o baseline
            adjustedPeriod = 100;
        } else {
            // Período de transição entre sessões
            adjustedPeriod = 70;
        }

        return calculateATR(bars, Math.min(adjustedPeriod, bars.size()));
    }

    /**
     * Classifica o tipo de confluência com base na maioria das estratégias decisoras.
     * Quando há empate entre tendência e reversão, retorna {@code TREND_BREAKOUT}.
     * Estratégias não mapeadas em nenhum dos conjuntos são ignoradas na contagem.
     *
     * @param decisionStrategies nomes das estratégias que votaram na direção final
     * @return {@code "TREND_BREAKOUT"} ou {@code "REVERSAL_RANGE"}
     */
    private String classifyConfluence(List<String> decisionStrategies) {
        int trendCount    = 0;
        int reversalCount = 0;

        for (String strategy : decisionStrategies) {
            if (TREND_BREAKOUT_STRATEGIES.contains(strategy))    trendCount++;
            else if (REVERSAL_RANGE_STRATEGIES.contains(strategy)) reversalCount++;
        }

        // Empate favorece TREND_BREAKOUT
        return trendCount >= reversalCount ? "TREND_BREAKOUT" : "REVERSAL_RANGE";
    }

    /**
     * Calcula o Average True Range (ATR) para o período informado.
     * O True Range do primeiro candle da janela usa apenas {@code high - low}
     * por não haver candle anterior disponível.
     * Retorna {@code 0.0} quando há menos candles disponíveis que o período.
     *
     * @param bars   histórico de candles
     * @param period número de candles para o cálculo
     * @return ATR calculado ou {@code 0.0} se dados insuficientes
     */
    private double calculateATR(List<Bar> bars, int period) {
        if (bars.size() < period) return 0.0;

        double sum = 0.0;
        for (int i = bars.size() - period; i < bars.size(); i++) {
            Bar    current = bars.get(i);
            double trueRange;

            if (i == bars.size() - period) {
                // Primeiro candle da janela: sem candle anterior disponível
                trueRange = current.high() - current.low();
            } else {
                Bar previous = bars.get(i - 1);
                trueRange = Math.max(
                        current.high() - current.low(),
                        Math.max(
                                Math.abs(current.high() - previous.close()),
                                Math.abs(current.low()  - previous.close())
                        )
                );
            }

            sum += trueRange;
        }

        return sum / period;
    }

    /**
     * Retorna um resumo formatado das estatísticas acumuladas de avaliações de risco.
     * Inclui totais e percentuais de operações permitidas, reduzidas e bloqueadas.
     *
     * @return string com as estatísticas acumuladas ou mensagem indicando ausência de dados
     */
    public String getStatistics() {
        long total = totalEvaluations.get();
        if (total == 0) return "No evaluations yet";

        return String.format(
                "ATR Risk Stats: Total=%d | Allowed=%d (%.1f%%) | Reduced=%d (%.1f%%) | Blocked=%d (%.1f%%)",
                total,
                totalAllowed.get(), (totalAllowed.get() * 100.0 / total),
                totalReduced.get(), (totalReduced.get() * 100.0 / total),
                totalBlocked.get(), (totalBlocked.get() * 100.0 / total)
        );
    }
}