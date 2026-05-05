package com.github.dayviddouglas.TradingBot.strategy;

import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.model.Signal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Estratégia de rompimento (breakout) de máximas e mínimas.
 *
 * Implementa TradingStrategy para ser usada pelo StrategyEngine.
 *
 * Lógica operacional:
 * - Identifica a máxima e a mínima dos últimos N candles (lookback)
 * - Aplica um buffer percentual para evitar sinais em rompimentos marginais
 * - BUY quando o preço fecha acima da máxima + buffer
 * - SELL quando o preço fecha abaixo da mínima - buffer
 *
 * Filtro adicional:
 * - Candle strength: o corpo do candle atual deve ser maior que
 *   bodyMultiplier × corpo médio dos últimos bodyLookback candles
 * - Isso filtra rompimentos fracos (candles pequenos que apenas tocam o nível)
 *
 * Versão melhorada:
 * - bodyMultiplier aumentado de 1.0 para 1.5 (mais conservador)
 * - Exige candle mais forte para confirmar o rompimento
 *
 * Esta estratégia é classificada como TREND_BREAKOUT pelo AtrRiskManager,
 * recebendo thresholds mais permissivos de volatilidade.
 *
 * Status no projeto: fraca no produto Rise/Fall 15m.
 * Evidência de backtest mostra que rompimentos clássicos não funcionam
 * bem com contratos de expiração fixa curta, onde o preço tende a
 * reverter antes do vencimento.
 *
 * ⚠️ Ponto de atenção: Os resultados fracos podem estar relacionados ao
 * produto (Rise/Fall 15m) e não necessariamente à estratégia em si.
 * Em produtos com expiração mais longa ou contratos de continuação,
 * o desempenho poderia ser diferente.
 */
public class BreakoutStrategy implements TradingStrategy {

    /**
     * Período de lookback para identificar máximas e mínimas.
     * Quanto maior, mais significativo é o nível rompido.
     */
    private final int lookback;

    /**
     * Buffer percentual acima/abaixo do nível para confirmar rompimento.
     * Exemplo: 0.0002 = 0.02% acima da máxima para gerar BUY.
     * Evita sinais em rompimentos marginais que podem ser ruído.
     */
    private final double bufferPct;

    // ═══════════════════════════════════════════════════════════════
    // Filtro de força do candle
    //
    // Exige que o candle de rompimento tenha corpo significativo,
    // filtrando dojis e candles de indecisão que tocam o nível
    // mas não demonstram convicção.
    // ═══════════════════════════════════════════════════════════════

    /** Período para calcular o corpo médio dos candles */
    private final int bodyLookback = 14;

    /**
     * Multiplicador para filtro de corpo.
     *
     * O corpo atual deve ser > bodyMultiplier × corpo médio.
     * Aumentado de 1.0 para 1.5 na versão melhorada,
     * exigindo candles mais fortes para confirmar rompimentos.
     */
    private final double bodyMultiplier = 1.5;

    /**
     * Construtor com validação de parâmetros.
     *
     * @param lookback período para identificar máximas/mínimas (mínimo 2)
     * @param bufferPct buffer percentual para confirmar rompimento (>= 0)
     */
    public BreakoutStrategy(int lookback, double bufferPct) {
        if (lookback < 2) throw new IllegalArgumentException("lookback must be >= 2");
        if (bufferPct < 0) throw new IllegalArgumentException("bufferPct must be >= 0");
        this.lookback = lookback;
        this.bufferPct = bufferPct;
    }

    @Override
    public String name() {
        return "BreakoutStrategy";
    }

    /**
     * Avalia se houve rompimento de nível com força suficiente.
     *
     * Fluxo:
     * 1. Identifica máxima e mínima dos últimos N candles (excluindo o atual)
     * 2. Calcula triggers com buffer: buyTrigger = prevHigh × (1 + bufferPct)
     * 3. Verifica força do candle atual (corpo vs corpo médio)
     * 4. Se candle fraco → NONE (rompimento sem convicção)
     * 5. Se close > buyTrigger e candle forte → BUY
     * 6. Se close < sellTrigger e candle forte → SELL
     *
     * O último candle é EXCLUÍDO do cálculo de máxima/mínima para
     * garantir que o rompimento é em relação a um nível histórico,
     * não ao próprio candle atual.
     *
     * @param bars lista de candles para análise
     * @return Signal (BUY, SELL ou NONE) com metadata completa
     */
    @Override
    public Signal checkSignal(List<Bar> bars) {
        if (bars == null) return Signal.none(name());

        // Necessita de barras suficientes para lookback E bodyLookback
        int minBars = Math.max(lookback + 1, bodyLookback + 1);
        if (bars.size() < minBars) return Signal.none(name());

        Bar last = bars.get(bars.size() - 1);
        double lastClose = last.close();

        // ── Identificação de máxima e mínima do período ──
        // Exclui o candle atual (último) para comparar com nível histórico
        double prevHigh = Double.NEGATIVE_INFINITY;
        double prevLow = Double.POSITIVE_INFINITY;

        int start = bars.size() - 1 - lookback;
        int end = bars.size() - 2; // Exclui o candle atual

        for (int i = start; i <= end; i++) {
            Bar b = bars.get(i);
            prevHigh = Math.max(prevHigh, b.high());
            prevLow = Math.min(prevLow, b.low());
        }

        // ── Cálculo dos triggers com buffer ──
        // O buffer evita sinais em rompimentos marginais (ruído)
        double buyTrigger = prevHigh * (1.0 + bufferPct);
        double sellTrigger = prevLow * (1.0 - bufferPct);

        // ── Filtro de força do candle ──
        // O corpo do candle de rompimento deve ser significativamente
        // maior que o corpo médio, indicando convicção no movimento
        double currentBody = Math.abs(last.close() - last.open());
        double avgBody = averageBody(bars, bodyLookback);
        boolean strongCandle = Double.isFinite(avgBody) && currentBody > (avgBody * bodyMultiplier);

        // Metadata para rastreabilidade
        Map<String, Object> meta = new HashMap<>();
        meta.put("lookback", lookback);
        meta.put("bufferPct", bufferPct);
        meta.put("prevHigh", prevHigh);
        meta.put("prevLow", prevLow);
        meta.put("buyTrigger", buyTrigger);
        meta.put("sellTrigger", sellTrigger);
        meta.put("close", lastClose);
        meta.put("currentBody", currentBody);
        meta.put("avgBody", avgBody);
        meta.put("bodyMultiplier", bodyMultiplier);
        meta.put("strongCandle", strongCandle);

        // Candle fraco: rompimento sem convicção → sem sinal
        if (!strongCandle) return Signal.none(name());

        // ── Rompimento de alta ──
        // Preço fechou acima da máxima histórica + buffer com corpo forte
        if (lastClose > buyTrigger) {
            return Signal.buy(name(), last.timestamp(), lastClose, meta);
        }

        // ── Rompimento de baixa ──
        // Preço fechou abaixo da mínima histórica - buffer com corpo forte
        if (lastClose < sellTrigger) {
            return Signal.sell(name(), last.timestamp(), lastClose, meta);
        }

        return Signal.none(name());
    }

    /**
     * Calcula o corpo médio (|close - open|) dos últimos N candles.
     *
     * Usado como referência para o filtro de força:
     * o corpo do candle de rompimento deve ser > bodyMultiplier × avgBody.
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