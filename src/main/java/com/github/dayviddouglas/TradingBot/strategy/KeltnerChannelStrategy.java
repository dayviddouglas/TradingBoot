package com.github.dayviddouglas.TradingBot.strategy;

import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.model.Signal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Estratégia de breakout baseada no Canal de Keltner.
 *
 * Implementa TradingStrategy para uso pelo StrategyEngine.
 *
 * Conceito:
 * O Canal de Keltner usa EMA como linha central e ATR como medida de
 * volatilidade para definir as bandas:
 * - Canal superior = EMA + (ATR × multiplicador)
 * - Canal inferior = EMA - (ATR × multiplicador)
 *
 * Diferença em relação ao Bollinger:
 * - Bollinger usa desvio padrão (baseado em preço de fechamento)
 * - Keltner usa ATR (baseado em True Range, incluindo gaps)
 * - Keltner tende a ser mais suave e menos reativo a spikes
 *
 * Lógica operacional:
 * - BUY quando o preço fecha acima do canal superior (breakout up)
 * - SELL quando o preço fecha abaixo do canal inferior (breakout down)
 * - Exige que o candle anterior estivesse DENTRO do canal
 *   (garante detecção do momento exato do rompimento)
 *
 * Filtro de força:
 * - Opcionalmente exige candle de corpo forte (body > bodyMultiplier × avgBody)
 * - Aumentado de 1.2 para 1.5 na versão corrigida (mais conservador)
 *
 * Versão corrigida:
 * - EMA calculada sobre toda a série (não janela móvel)
 * - bodyMultiplier aumentado para 1.5
 *
 * Classificada como TREND_BREAKOUT pelo projeto.
 *
 * Status: não priorizada recentemente nos testes.
 * A família de breakout não mostrou edge consistente no
 * produto Rise/Fall 15m.
 *
 * Referência: [Chester Keltner, 1960, "How To Make Money in Commodities"]
 *             Versão moderna popularizada por Linda Raschke.
 *
 * @see BollingerMeanReversionStrategy para abordagem de reversão com bandas
 * @see BreakoutStrategy para breakout mais simples sem canal
 */
public class KeltnerChannelStrategy implements TradingStrategy {

    /** Período da EMA central do canal */
    private final int emaPeriod;

    /** Período do ATR para largura do canal */
    private final int atrPeriod;

    /**
     * Multiplicador do ATR para construção das bandas.
     * Valores maiores = canal mais largo = menos sinais, mais seletivo.
     * Padrão: 2.5 (mais conservador que o 2.0 original de Keltner).
     */
    private final double atrMultiplier;

    /** Se true, exige candle de corpo forte para confirmar rompimento */
    private final boolean requireStrongCandle;

    // ═══════════════════════════════════════════════════════════════
    // Filtro de força do candle
    //
    // Mesmo conceito do BreakoutStrategy: exige que o candle de
    // rompimento tenha corpo significativo para filtrar falsos breakouts.
    // ═══════════════════════════════════════════════════════════════

    /** Período para calcular corpo médio dos candles */
    private final int bodyLookback = 10;

    /**
     * Multiplicador de força do candle.
     * Aumentado de 1.2 para 1.5 na versão corrigida.
     */
    private final double bodyMultiplier = 1.5;

    /**
     * Construtor com validação de parâmetros.
     *
     * @param emaPeriod período da EMA central (mínimo 2)
     * @param atrPeriod período do ATR para largura das bandas (mínimo 2)
     * @param atrMultiplier multiplicador do ATR (deve ser > 0)
     * @param requireStrongCandle se deve exigir candle forte
     */
    public KeltnerChannelStrategy(
            int emaPeriod,
            int atrPeriod,
            double atrMultiplier,
            boolean requireStrongCandle
    ) {
        if (emaPeriod < 2) throw new IllegalArgumentException("emaPeriod must be >= 2");
        if (atrPeriod < 2) throw new IllegalArgumentException("atrPeriod must be >= 2");
        if (atrMultiplier <= 0) throw new IllegalArgumentException("atrMultiplier must be > 0");

        this.emaPeriod = emaPeriod;
        this.atrPeriod = atrPeriod;
        this.atrMultiplier = atrMultiplier;
        this.requireStrongCandle = requireStrongCandle;
    }

    @Override
    public String name() {
        return "KeltnerChannelStrategy";
    }

    /**
     * Avalia se houve rompimento do Canal de Keltner.
     *
     * Fluxo:
     * 1. Calcula EMA central (sobre toda a série)
     * 2. Calcula ATR para definir largura do canal
     * 3. Monta canais: upper = EMA + ATR × mult, lower = EMA - ATR × mult
     * 4. Se requireStrongCandle, verifica corpo do candle
     * 5. Se previous.close dentro do canal E current.close fora → rompimento
     *
     * A verificação previous vs current garante que o sinal é emitido
     * apenas no momento do rompimento, não enquanto o preço permanece
     * acima/abaixo do canal.
     *
     * @param bars lista de candles para análise
     * @return Signal (BUY, SELL ou NONE) com metadata
     */
    @Override
    public Signal checkSignal(List<Bar> bars) {
        if (bars == null) return Signal.none(name());

        // Precisa de barras suficientes para EMA + margem e ATR
        int minBars = Math.max(emaPeriod + 10, atrPeriod) + 1;
        if (bars.size() < minBars) return Signal.none(name());

        Bar current = bars.get(bars.size() - 1);
        Bar previous = bars.get(bars.size() - 2);

        // ── Construção do Canal de Keltner ──
        // EMA CORRIGIDA: calculada sobre toda a série
        double ema = emaCloseCorrect(bars, emaPeriod);
        double atr = atrValue(bars, atrPeriod);

        if (!Double.isFinite(ema) || !Double.isFinite(atr)) {
            return Signal.none(name());
        }

        // Bandas: EMA ± (ATR × multiplicador)
        double upperChannel = ema + (atr * atrMultiplier);
        double lowerChannel = ema - (atr * atrMultiplier);

        Map<String, Object> meta = new HashMap<>();
        meta.put("emaPeriod", emaPeriod);
        meta.put("atrPeriod", atrPeriod);
        meta.put("atrMultiplier", atrMultiplier);
        meta.put("ema", ema);
        meta.put("atr", atr);
        meta.put("upperChannel", upperChannel);
        meta.put("lowerChannel", lowerChannel);
        meta.put("currentClose", current.close());
        meta.put("previousClose", previous.close());

        // ── Filtro de força do candle (opcional) ──
        if (requireStrongCandle) {
            double currentBody = Math.abs(current.close() - current.open());
            double avgBody = averageBody(bars, bodyLookback);
            boolean strongCandle = Double.isFinite(avgBody) && currentBody > (avgBody * bodyMultiplier);

            meta.put("currentBody", currentBody);
            meta.put("avgBody", avgBody);
            meta.put("strongCandle", strongCandle);
            meta.put("bodyMultiplier", bodyMultiplier);

            // Candle fraco: rompimento sem convicção
            if (!strongCandle) {
                return Signal.none(name());
            }
        }

        // ── BUY: Breakout acima do canal superior ──
        // previous dentro do canal + current acima = momento do rompimento
        if (previous.close() <= upperChannel && current.close() > upperChannel) {
            meta.put("pattern", "breakout_up");
            return Signal.buy(name(), current.timestamp(), current.close(), meta);
        }

        // ── SELL: Breakout abaixo do canal inferior ──
        if (previous.close() >= lowerChannel && current.close() < lowerChannel) {
            meta.put("pattern", "breakout_down");
            return Signal.sell(name(), current.timestamp(), current.close(), meta);
        }

        return Signal.none(name());
    }

    // ═══════════════════════════════════════════════════════════════
    // Indicadores técnicos
    // ═══════════════════════════════════════════════════════════════

    /**
     * EMA corrigida calculada sobre toda a série histórica.
     *
     * Seed com SMA dos primeiros N candles, depois suavização
     * exponencial até o último candle da série.
     *
     * @param bars lista completa de candles
     * @param period período da EMA
     * @return valor atual da EMA ou NaN se dados insuficientes
     */
    private static double emaCloseCorrect(List<Bar> bars, int period) {
        if (bars.size() < period) return Double.NaN;

        double k = 2.0 / (period + 1.0);

        // Seed: SMA dos primeiros candles
        double ema = 0.0;
        for (int i = 0; i < period; i++) {
            ema += bars.get(i).close();
        }
        ema /= period;

        // Suavização exponencial
        for (int i = period; i < bars.size(); i++) {
            ema = bars.get(i).close() * k + ema * (1.0 - k);
        }

        return ema;
    }

    /**
     * Calcula o ATR para definir a largura do canal.
     *
     * @param bars lista de candles
     * @param period período do ATR
     * @return ATR ou NaN se dados insuficientes
     */
    private static double atrValue(List<Bar> bars, int period) {
        if (bars.size() < period) return Double.NaN;

        double sum = 0.0;
        int start = bars.size() - period;

        for (int i = start; i < bars.size(); i++) {
            Bar bar = bars.get(i);
            double tr;

            if (i == start) {
                tr = bar.high() - bar.low();
            } else {
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

    /**
     * Calcula o corpo médio dos últimos N candles para filtro de força.
     *
     * @param bars lista de candles
     * @param lookback quantidade de candles para a média
     * @return corpo médio ou NaN se dados insuficientes
     */
    private static double averageBody(List<Bar> bars, int lookback) {
        if (bars.size() < lookback) return Double.NaN;

        double sum = 0.0;
        for (int i = bars.size() - lookback; i < bars.size(); i++) {
            Bar b = bars.get(i);
            sum += Math.abs(b.close() - b.open());
        }
        return sum / lookback;
    }
}