package com.github.dayviddouglas.TradingBot.risk;

import com.github.dayviddouglas.TradingBot.model.Bar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gerenciador de risco baseado em ATR contextual (IMPROVED VERSION)
 *
 * MELHORIAS:
 * - Redução de stake PROPORCIONAL (não mais binária 50%)
 * - ATR baseline ajustado por sessão de mercado
 * - Métricas de bloqueio acumuladas
 * - Classificação dinâmica (preparada para ADX futuro)
 */
@Component
public class AtrRiskManager {

    private static final Logger log = LoggerFactory.getLogger(AtrRiskManager.class);

    // Períodos de ATR
    private static final int ATR_FAST_PERIOD = 14;
    private static final int ATR_BASELINE_PERIOD = 50;

    // Estratégias classificadas por tipo
    private static final Set<String> TREND_BREAKOUT_STRATEGIES = Set.of(
            "EmaRsiStrategy",
            "BreakoutStrategy",
            "KeltnerChannelStrategy",
            "DonchianBreakoutStrategy"
    );

    private static final Set<String> REVERSAL_RANGE_STRATEGIES = Set.of(
            "BollingerMeanReversionStrategy",
            "PinBarStrategy",
            "SupportResistanceStrategy"
    );

    // Limites de ATR CONSERVADORES (baseados em análise estatística)
    private static final double TREND_ALLOW_THRESHOLD = 1.5;      // P75 (mais conservador)
    private static final double TREND_BLOCK_THRESHOLD = 2.2;      // P95

    private static final double REVERSAL_ALLOW_THRESHOLD = 1.15;  // P70
    private static final double REVERSAL_BLOCK_THRESHOLD = 1.5;   // P90

    // Métricas acumuladas
    private final AtomicLong totalEvaluations = new AtomicLong(0);
    private final AtomicLong totalAllowed = new AtomicLong(0);
    private final AtomicLong totalReduced = new AtomicLong(0);
    private final AtomicLong totalBlocked = new AtomicLong(0);

    /**
     * Avalia se um trade deve ser executado considerando o contexto de volatilidade.
     *
     * @param symbol Símbolo do ativo
     * @param bars Lista de barras recentes (deve conter pelo menos ATR_BASELINE_PERIOD barras)
     * @param baseAmount Valor base configurado para o trade
     * @param decisionStrategies Lista de nomes das estratégias que geraram o sinal
     * @return AtrRiskDecision com a decisão de risco
     */
    public AtrRiskDecision evaluate(
            String symbol,
            List<Bar> bars,
            double baseAmount,
            List<String> decisionStrategies
    ) {

        totalEvaluations.incrementAndGet();

        // Validação de dados suficientes
        if (bars == null || bars.size() < ATR_BASELINE_PERIOD) {
            log.warn("ATR RISK SKIP | symbol={} | reason=insufficient bars ({} < {})",
                    symbol, bars != null ? bars.size() : 0, ATR_BASELINE_PERIOD);
            return new AtrRiskDecision(
                    true,
                    baseAmount,
                    "INSUFFICIENT_DATA",
                    0.0,
                    0.0,
                    0.0,
                    "UNKNOWN",
                    "Not enough bars for ATR calculation"
            );
        }

        // Validação de estratégias decisoras
        if (decisionStrategies == null || decisionStrategies.isEmpty()) {
            log.warn("ATR RISK SKIP | symbol={} | reason=no decision strategies available", symbol);
            return new AtrRiskDecision(
                    true,
                    baseAmount,
                    "NO_STRATEGIES",
                    0.0,
                    0.0,
                    0.0,
                    "UNKNOWN",
                    "No decision strategies available"
            );
        }

        // Calcular ATRs
        double atrFast = calculateATR(bars, ATR_FAST_PERIOD);

        // ATR baseline ajustado por sessão (se timestamp disponível)
        Bar lastBar = bars.get(bars.size() - 1);
        double atrBaseline = calculateBaselineATR(bars, lastBar.timestamp());

        if (atrBaseline == 0.0) {
            log.warn("ATR RISK SKIP | symbol={} | reason=baseline ATR is zero", symbol);
            return new AtrRiskDecision(
                    true,
                    baseAmount,
                    "ZERO_BASELINE",
                    atrFast,
                    atrBaseline,
                    0.0,
                    "UNKNOWN",
                    "Baseline ATR is zero"
            );
        }

        double atrRatio = atrFast / atrBaseline;

        // Classificar tipo de confluência
        String confluenceType = classifyConfluence(decisionStrategies);

        // Determinar limites baseados no tipo de confluência
        double allowThreshold;
        double blockThreshold;

        if ("TREND_BREAKOUT".equals(confluenceType)) {
            allowThreshold = TREND_ALLOW_THRESHOLD;
            blockThreshold = TREND_BLOCK_THRESHOLD;
        } else {
            allowThreshold = REVERSAL_ALLOW_THRESHOLD;
            blockThreshold = REVERSAL_BLOCK_THRESHOLD;
        }

        // Calcular stake ajustada (PROPORCIONAL, não binária)
        double adjustedAmount = calculateProportionalStake(
                baseAmount,
                atrRatio,
                allowThreshold,
                blockThreshold
        );

        // Determinar modo de risco
        boolean allowTrade;
        String riskMode;
        String reason;

        if (adjustedAmount == 0.0) {
            // Bloqueado
            allowTrade = false;
            riskMode = "BLOCK";
            reason = String.format("Volatility too high for %s setup", confluenceType);

            totalBlocked.incrementAndGet();
            long blockRate = (totalBlocked.get() * 100) / totalEvaluations.get();

            log.error("ATR RISK BLOCK | symbol={} | type={} | atrFast={} | atrBase={} | atrRatio={} | blockRate={}% | reason={}",
                    symbol, confluenceType, String.format("%.6f", atrFast),
                    String.format("%.6f", atrBaseline), String.format("%.2f", atrRatio), blockRate, reason);

        } else if (adjustedAmount < baseAmount) {
            // Reduzido
            allowTrade = true;
            riskMode = "REDUCE_STAKE";
            reason = String.format("ATR elevated for %s setup", confluenceType);

            totalReduced.incrementAndGet();
            long reduceRate = (totalReduced.get() * 100) / totalEvaluations.get();

            log.warn("ATR RISK REDUCE | symbol={} | type={} | atrFast={} | atrBase={} | atrRatio={} | amount={} -> {} ({:.1f}%) | reduceRate={}%",
                    symbol, confluenceType, String.format("%.6f", atrFast),
                    String.format("%.6f", atrBaseline), String.format("%.2f", atrRatio),
                    baseAmount, adjustedAmount, (adjustedAmount / baseAmount * 100), reduceRate);

        } else {
            // Liberado
            allowTrade = true;
            riskMode = "ALLOW";
            reason = "ATR within acceptable range";

            totalAllowed.incrementAndGet();

            log.info("ATR RISK OK | symbol={} | type={} | atrFast={} | atrBase={} | atrRatio={} | amount={}",
                    symbol, confluenceType, String.format("%.6f", atrFast),
                    String.format("%.6f", atrBaseline), String.format("%.2f", atrRatio), adjustedAmount);
        }

        return new AtrRiskDecision(
                allowTrade,
                adjustedAmount,
                riskMode,
                atrFast,
                atrBaseline,
                atrRatio,
                confluenceType,
                reason
        );
    }

    /**
     * Calcula stake ajustada de forma PROPORCIONAL.
     *
     * Interpolação linear entre allow e block threshold:
     * - atrRatio <= allow → 100% da stake
     * - atrRatio >= block → 0% (bloqueado)
     * - Entre os dois → redução proporcional
     */
    private double calculateProportionalStake(
            double baseAmount,
            double atrRatio,
            double allowThreshold,
            double blockThreshold
    ) {
        // Caso 1: Dentro do threshold normal
        if (atrRatio <= allowThreshold) {
            return baseAmount;
        }

        // Caso 2: Acima do threshold de bloqueio
        if (atrRatio >= blockThreshold) {
            return 0.0;
        }

        // Caso 3: Zona intermediária - interpolação linear
        double range = blockThreshold - allowThreshold;
        double distance = atrRatio - allowThreshold;
        double reductionFactor = 1.0 - (distance / range);

        return baseAmount * reductionFactor;
    }

    /**
     * Calcula ATR baseline ajustado por sessão de mercado.
     *
     * Diferentes sessões têm diferentes perfis de volatilidade.
     * Ajusta o período de lookback baseado na hora UTC.
     */
    private double calculateBaselineATR(List<Bar> bars, Instant timestamp) {
        if (timestamp == null) {
            // Fallback: usar período padrão
            return calculateATR(bars, ATR_BASELINE_PERIOD);
        }

        int hour = timestamp.atZone(ZoneOffset.UTC).getHour();

        // Ajustar período baseado na sessão
        int adjustedPeriod;

        if (hour >= 8 && hour < 16) {
            // London session: volatilidade moderada-alta
            adjustedPeriod = 50;
        } else if (hour >= 13 && hour < 21) {
            // NY session (overlap + pura): volatilidade alta
            adjustedPeriod = 50;
        } else if (hour >= 0 && hour < 8) {
            // Asia/overnight: volatilidade baixa
            adjustedPeriod = 100; // Período maior = baseline mais estável
        } else {
            // Transição
            adjustedPeriod = 70;
        }

        return calculateATR(bars, Math.min(adjustedPeriod, bars.size()));
    }

    /**
     * Classifica o tipo de confluência baseado nas estratégias decisoras.
     */
    private String classifyConfluence(List<String> decisionStrategies) {
        int trendCount = 0;
        int reversalCount = 0;

        for (String strategy : decisionStrategies) {
            if (TREND_BREAKOUT_STRATEGIES.contains(strategy)) {
                trendCount++;
            } else if (REVERSAL_RANGE_STRATEGIES.contains(strategy)) {
                reversalCount++;
            }
        }

        // Maioria define o tipo (empate favorece TREND_BREAKOUT)
        return trendCount >= reversalCount ? "TREND_BREAKOUT" : "REVERSAL_RANGE";
    }

    /**
     * Calcula o Average True Range (ATR) para um período específico.
     */
    private double calculateATR(List<Bar> bars, int period) {
        if (bars.size() < period) {
            return 0.0;
        }

        double sum = 0.0;

        for (int i = bars.size() - period; i < bars.size(); i++) {
            Bar current = bars.get(i);
            double trueRange;

            if (i == bars.size() - period) {
                trueRange = current.high() - current.low();
            } else {
                Bar previous = bars.get(i - 1);
                double highLow = current.high() - current.low();
                double highClose = Math.abs(current.high() - previous.close());
                double lowClose = Math.abs(current.low() - previous.close());
                trueRange = Math.max(highLow, Math.max(highClose, lowClose));
            }

            sum += trueRange;
        }

        return sum / period;
    }

    /**
     * Retorna estatísticas acumuladas de risco.
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