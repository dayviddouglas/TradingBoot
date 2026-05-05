package com.github.dayviddouglas.TradingBot.strategy;

import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.model.Signal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Estratégia de reversão à média baseada em Bandas de Bollinger.
 *
 * Implementa TradingStrategy para ser usada pelo StrategyEngine
 * em qualquer um dos modos de decisão (SINGLE_STRATEGY, VOTING, CONFLUENCE).
 *
 * Lógica operacional:
 * - Calcula SMA e desvio padrão dos últimos N candles (período)
 * - Monta as bandas: upper = SMA + (stdDev × multiplicador)
 *                     lower = SMA - (stdDev × multiplicador)
 * - Calcula a posição do preço dentro das bandas (0.0 = banda inferior, 1.0 = banda superior)
 * - Gera SELL quando o preço está próximo ao topo (positionInBand > entryThreshold)
 * - Gera BUY quando o preço está próximo à base (positionInBand < 1 - entryThreshold)
 *
 * Filtros adicionais:
 * - Band width filter: exige que a largura das bandas seja >= 50% do ATR
 *   (evita sinais em mercado comprimido sem volatilidade suficiente)
 * - RSI confirmation (opcional): confirma exaustão antes de entrar
 *   (RSI > overbought para SELL, RSI < oversold para BUY)
 *
 * Versão corrigida:
 * - StdDev agora usa N-1 (sample standard deviation, não populacional)
 * - RSI usa Wilder smoothing (média exponencial, não simples)
 * - Lógica de entrada simétrica e limpa
 * - Band width filter baseado em ATR (não mais valor arbitrário)
 *
 * Esta é historicamente a estratégia com melhor evidência de edge no projeto,
 * especialmente em ativos como frxXAUUSD em regime RANGING.
 *
 * Referências:
 * - [Bollinger, 2001, Bollinger on Bollinger Bands]
 * - [Wilder, 1978, New Concepts in Technical Trading Systems] (para RSI)
 *
 * ⚠️ Ponto de atenção: O nome retornado por name() é "BollingerMeanReversionStrategy"
 * (com sufixo "Strategy"). Isso deve bater com as chaves no StrategyWeightProfile
 * e no AtrRiskManager para que pesos e classificação de confluência funcionem
 * corretamente.
 *
 * 💡 Sugestão: Considere padronizar o name() para "BollingerMeanReversion"
 * (sem sufixo) para consistência com ZScoreMeanReversion e outras estratégias.
 * Atualmente há inconsistência nos nomes retornados.
 */
public class BollingerMeanReversionStrategy implements TradingStrategy {

    /** Período da SMA e do desvio padrão (janela de análise) */
    private final int period;

    /**
     * Multiplicador do desvio padrão para construção das bandas.
     * Valor clássico: 2.0. Valores maiores = bandas mais largas = menos sinais.
     * No projeto, metais usam 2.2 (mais voláteis) e forex usa 2.0.
     */
    private final double stdDevMultiplier;

    /**
     * Limiar de posição nas bandas para gerar sinal.
     * Exemplo com 0.96:
     * - SELL quando posição > 0.96 (preço nos 4% superiores da banda)
     * - BUY quando posição < 0.04 (preço nos 4% inferiores da banda)
     */
    private final double entryThreshold;

    /** Se true, exige confirmação de RSI para gerar sinal */
    private final boolean useRsiConfirmation;

    /** Período do RSI (só usado se useRsiConfirmation = true) */
    private final int rsiPeriod;

    /** Nível de RSI para considerar sobrecompra (SELL) */
    private final double rsiOverbought;

    /** Nível de RSI para considerar sobrevenda (BUY) */
    private final double rsiOversold;

    /**
     * Construtor com validação rigorosa de parâmetros.
     *
     * Implementa fail-fast: valores inválidos causam exceção imediata,
     * impedindo criação de estratégias mal configuradas que gerariam
     * sinais incorretos ou erros de runtime.
     *
     * @param period período das bandas (mínimo 5 para significância estatística)
     * @param stdDevMultiplier multiplicador do desvio padrão (deve ser positivo)
     * @param entryThreshold limiar de posição [0, 1] para gerar sinal
     * @param useRsiConfirmation se deve usar RSI como filtro adicional
     * @param rsiPeriod período do RSI
     * @param rsiOverbought nível de sobrecompra do RSI
     * @param rsiOversold nível de sobrevenda do RSI
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
        if (period < 5) throw new IllegalArgumentException("period must be >= 5");
        if (stdDevMultiplier <= 0) throw new IllegalArgumentException("stdDevMultiplier must be > 0");
        if (entryThreshold < 0 || entryThreshold > 1) throw new IllegalArgumentException("entryThreshold must be in [0,1]");
        if (rsiPeriod < 2) throw new IllegalArgumentException("rsiPeriod must be >= 2");
        if (rsiOverbought <= rsiOversold) throw new IllegalArgumentException("rsiOverbought must be > rsiOversold");

        this.period = period;
        this.stdDevMultiplier = stdDevMultiplier;
        this.entryThreshold = entryThreshold;
        this.useRsiConfirmation = useRsiConfirmation;
        this.rsiPeriod = rsiPeriod;
        this.rsiOverbought = rsiOverbought;
        this.rsiOversold = rsiOversold;
    }

    /**
     * Nome da estratégia usado para identificação em logs, metadata e pesos.
     *
     * ⚠️ Este nome DEVE bater com as chaves no StrategyWeightProfile
     * e nos Sets do AtrRiskManager para que a ponderação por regime
     * e a classificação de confluência funcionem corretamente.
     */
    @Override
    public String name() {
        return "BollingerMeanReversionStrategy";
    }

    /**
     * Avalia os candles e retorna um sinal de trading.
     *
     * Fluxo de avaliação:
     * 1. Valida dados de entrada (quantidade mínima de barras)
     * 2. Calcula Bandas de Bollinger (SMA ± stdDev × multiplicador)
     * 3. Aplica filtro de largura de banda (bandWidth >= 50% do ATR)
     * 4. Calcula posição do preço dentro das bandas (0.0 a 1.0)
     * 5. Se próximo ao topo e RSI confirma sobrecompra → SELL
     * 6. Se próximo à base e RSI confirma sobrevenda → BUY
     * 7. Caso contrário → NONE
     *
     * O metadata do sinal inclui todos os indicadores calculados,
     * facilitando análise e debug das decisões.
     *
     * @param bars lista de candles para análise
     * @return Signal (BUY, SELL ou NONE) com metadata completa
     */
    @Override
    public Signal checkSignal(List<Bar> bars) {
        if (bars == null) return Signal.none(name());

        // Necessita de barras suficientes para período de Bollinger e RSI
        int minBars = Math.max(period, useRsiConfirmation ? rsiPeriod + 1 : 0);
        if (bars.size() < minBars) return Signal.none(name());

        Bar last = bars.get(bars.size() - 1);
        double close = last.close();

        // ── Cálculo das Bandas de Bollinger ──
        double sma = smaClose(bars, period);
        // StdDev corrigido: usa N-1 (sample, não population)
        double stdDev = stdDevCloseCorrect(bars, period, sma);
        double upperBand = sma + (stdDev * stdDevMultiplier);
        double lowerBand = sma - (stdDev * stdDevMultiplier);

        // Validação de cálculos (NaN indica dados insuficientes ou inválidos)
        if (!Double.isFinite(sma) || !Double.isFinite(stdDev)) {
            return Signal.none(name());
        }

        // ── Filtro de largura de banda ──
        // Evita sinais quando as bandas estão muito estreitas (mercado comprimido)
        // A largura mínima é 50% do ATR: garante volatilidade suficiente
        double atr = calculateATR(bars, 14);
        double bandWidth = upperBand - lowerBand;
        double minBandWidth = atr * 0.5;

        if (bandWidth < minBandWidth) {
            return Signal.none(name());
        }

        // ── Posição do preço nas bandas ──
        // 0.0 = na banda inferior
        // 0.5 = na SMA (meio)
        // 1.0 = na banda superior
        double bandRange = upperBand - lowerBand;
        double positionInBand = (close - lowerBand) / bandRange;

        // Metadata para rastreabilidade (incluída em qualquer sinal, inclusive NONE)
        Map<String, Object> meta = new HashMap<>();
        meta.put("period", period);
        meta.put("stdDevMultiplier", stdDevMultiplier);
        meta.put("entryThreshold", entryThreshold);
        meta.put("sma", sma);
        meta.put("stdDev", stdDev);
        meta.put("upperBand", upperBand);
        meta.put("lowerBand", lowerBand);
        meta.put("bandWidth", bandWidth);
        meta.put("close", close);
        meta.put("positionInBand", positionInBand);

        // ── SELL: Preço nos percentis superiores da banda ──
        // Indica possível exaustão de alta e reversão para baixo
        if (positionInBand > entryThreshold) {

            // Confirmação de RSI (opcional): exige sobrecompra
            if (useRsiConfirmation) {
                double rsi = rsiWilder(bars, rsiPeriod);
                meta.put("rsiVal", rsi);
                meta.put("rsiOverbought", rsiOverbought);

                // RSI não confirma exaustão → sem sinal
                if (!Double.isFinite(rsi) || rsi < rsiOverbought) {
                    return Signal.none(name());
                }
            }

            meta.put("pattern", "overbought_reversion");
            return Signal.sell(name(), last.timestamp(), close, meta);
        }

        // ── BUY: Preço nos percentis inferiores da banda ──
        // Indica possível exaustão de baixa e reversão para cima
        if (positionInBand < (1.0 - entryThreshold)) {

            // Confirmação de RSI (opcional): exige sobrevenda
            if (useRsiConfirmation) {
                double rsi = rsiWilder(bars, rsiPeriod);
                meta.put("rsiVal", rsi);
                meta.put("rsiOversold", rsiOversold);

                // RSI não confirma exaustão → sem sinal
                if (!Double.isFinite(rsi) || rsi > rsiOversold) {
                    return Signal.none(name());
                }
            }

            meta.put("pattern", "oversold_reversion");
            return Signal.buy(name(), last.timestamp(), close, meta);
        }

        // Preço na zona neutra das bandas: sem sinal
        return Signal.none(name());
    }

    // ═══════════════════════════════════════════════════════════════
    // Indicadores técnicos (implementações internas)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Calcula a Média Móvel Simples (SMA) dos preços de fechamento.
     *
     * @param bars lista de candles
     * @param period quantidade de candles para a média
     * @return SMA ou NaN se dados insuficientes
     */
    private static double smaClose(List<Bar> bars, int period) {
        if (bars.size() < period) return Double.NaN;

        double sum = 0.0;
        int start = bars.size() - period;

        for (int i = start; i < bars.size(); i++) {
            sum += bars.get(i).close();
        }

        return sum / period;
    }

    /**
     * Calcula o Desvio Padrão AMOSTRAL (N-1) dos preços de fechamento.
     *
     * CORREÇÃO importante: usa N-1 (sample standard deviation) em vez de
     * N (population standard deviation). A diferença é relevante para
     * períodos pequenos (< 30) onde N e N-1 produzem resultados
     * significativamente diferentes.
     *
     * A fórmula amostral (N-1) é estatisticamente mais correta quando
     * se trabalha com uma amostra da população total de preços.
     *
     * Referência: Bessel's correction para estimativa não-enviesada de variância
     *
     * @param bars lista de candles
     * @param period quantidade de candles para o cálculo
     * @param mean SMA pré-calculada (evita recálculo)
     * @return desvio padrão amostral ou NaN se dados insuficientes
     */
    private static double stdDevCloseCorrect(List<Bar> bars, int period, double mean) {
        if (bars.size() < period || !Double.isFinite(mean)) return Double.NaN;

        double sum = 0.0;
        int start = bars.size() - period;

        for (int i = start; i < bars.size(); i++) {
            double diff = bars.get(i).close() - mean;
            sum += diff * diff;
        }

        // N-1 (Bessel's correction) para estimativa não-enviesada
        return Math.sqrt(sum / (period - 1));
    }

    /**
     * Calcula o RSI usando Wilder Smoothing (exponential moving average).
     *
     * CORREÇÃO: A versão original usava média simples para todo o cálculo.
     * O RSI de Wilder usa:
     * 1. SMA para o período inicial (seed)
     * 2. Média exponencial (Wilder smoothing) para o restante
     *
     * Fórmula de Wilder smoothing:
     * avgGain[i] = (avgGain[i-1] × (period-1) + gain[i]) / period
     * avgLoss[i] = (avgLoss[i-1] × (period-1) + loss[i]) / period
     *
     * RS = avgGain / avgLoss
     * RSI = 100 - (100 / (1 + RS))
     *
     * Casos especiais:
     * - avgLoss == 0: RSI = 100 (sem perdas = sobrecompra extrema)
     *
     * Referência: [Wilder, 1978, New Concepts in Technical Trading Systems]
     *
     * @param bars lista de candles (precisa de period + 1 mínimo)
     * @param period período do RSI (clássico: 14)
     * @return RSI entre 0 e 100 ou NaN se dados insuficientes
     */
    private static double rsiWilder(List<Bar> bars, int period) {
        if (bars.size() < period + 1) return Double.NaN;

        // Calcula variações de preço (close[i] - close[i-1])
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
                avgLoss += -changes[i]; // Inverte sinal para positivo
            }
        }

        avgGain /= period;
        avgLoss /= period;

        // Fase 2: Wilder smoothing para os períodos restantes
        // Suavização exponencial com fator (period-1)/period
        for (int i = period; i < changes.length; i++) {
            double gain = changes[i] > 0 ? changes[i] : 0.0;
            double loss = changes[i] < 0 ? -changes[i] : 0.0;

            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;
        }

        // Cálculo final do RSI
        if (avgLoss == 0.0) return 100.0; // Sem perdas = sobrecompra máxima
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    /**
     * Calcula o ATR usado no filtro de largura de banda.
     *
     * O ATR serve como referência de volatilidade "normal" para determinar
     * se as bandas de Bollinger estão suficientemente largas para que
     * um sinal de reversão tenha significância.
     *
     * @param bars lista de candles
     * @param period período do ATR
     * @return ATR ou NaN se dados insuficientes
     */
    private static double calculateATR(List<Bar> bars, int period) {
        if (bars.size() < period) return Double.NaN;

        double sum = 0.0;
        for (int i = bars.size() - period; i < bars.size(); i++) {
            Bar current = bars.get(i);
            double tr;

            if (i == bars.size() - period) {
                tr = current.high() - current.low();
            } else {
                Bar previous = bars.get(i - 1);
                tr = Math.max(
                        current.high() - current.low(),
                        Math.max(
                                Math.abs(current.high() - previous.close()),
                                Math.abs(current.low() - previous.close())
                        )
                );
            }

            sum += tr;
        }

        return sum / period;
    }
}