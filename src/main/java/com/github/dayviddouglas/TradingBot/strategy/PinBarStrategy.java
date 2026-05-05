package com.github.dayviddouglas.TradingBot.strategy;

import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.model.Signal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Estratégia de Pin Bar com contexto de Suporte e Resistência.
 *
 * Implementa TradingStrategy para uso pelo StrategyEngine.
 *
 * Conceito:
 * O Pin Bar é um padrão de vela que indica rejeição de preço em um nível.
 * Caracteriza-se por:
 * - Corpo pequeno
 * - Pavio longo em uma direção (indica rejeição)
 * - Pavio curto ou inexistente na direção oposta
 *
 * Tipos de Pin Bar:
 * - Bullish Pin Bar: pavio inferior longo → preço rejeitou nível de suporte
 * - Bearish Pin Bar: pavio superior longo → preço rejeitou nível de resistência
 *
 * Filtro de contexto:
 * Esta estratégia NÃO opera qualquer Pin Bar isolado. Ela exige que o
 * Pin Bar ocorra PRÓXIMO a um nível de Suporte ou Resistência identificado
 * nos últimos N candles. Isso aumenta a confiabilidade do padrão.
 *
 * Versão corrigida:
 * - Tolerância de proximidade agora baseada em ATR (não mais valor fixo)
 * - Isso adapta automaticamente a tolerância à volatilidade do ativo
 *
 * Classificada como reversão/rejeição pelo projeto.
 * Recebe peso alto em RANGING no StrategyWeightProfile.
 *
 * Status: potencial local, mas sem edge consistente isolado no Rise/Fall 15m.
 *
 * ⚠️ Ponto de atenção: A conversão de tolerancePct para toleranceMultiplier
 * usa a fórmula `Math.max(0.3, tolerancePct * 1000)` que pode produzir
 * valores surpreendentes dependendo do input. Com tolerancePct=0.5 (padrão),
 * toleranceMultiplier = 500.0, que é muito alto.
 * Isso parece ser um bug: tolerance = ATR × 500 englobaria praticamente
 * qualquer nível como "próximo". Verifique se a intenção era usar um
 * multiplicador menor (ex: 0.5 × ATR).
 *
 * Referência: [Bulkowski, 2021, Encyclopedia of Chart Patterns]
 */
public class PinBarStrategy implements TradingStrategy {

    /**
     * Ratio mínimo entre pavio principal e corpo para qualificar como Pin Bar.
     * Ex: 3.5 = pavio deve ser pelo menos 3.5x o tamanho do corpo.
     * Valores maiores = padrões mais extremos e raros.
     */
    private final double wickToBodyRatio;

    /**
     * Ratio máximo permitido do pavio oposto em relação ao corpo.
     * Ex: 0.15 = pavio oposto não pode exceder 15% do corpo.
     * Garante que o Pin Bar tenha rejeição clara em uma única direção.
     */
    private final double maxOppositeWickToBody;

    /** Período de lookback para identificar níveis de S/R */
    private final int srLookback;

    /**
     * Multiplicador do ATR para calcular tolerância de proximidade.
     *
     * ⚠️ Ponto de atenção: O cálculo no construtor
     * (tolerancePct * 1000) pode gerar valores excessivos.
     * Veja nota no Javadoc da classe.
     */
    private final double toleranceMultiplier;

    /**
     * Construtor simplificado com valores padrão para S/R.
     *
     * @param wickToBodyRatio ratio mínimo do pavio principal
     * @param maxOppositeWickToBody ratio máximo do pavio oposto
     */
    public PinBarStrategy(double wickToBodyRatio, double maxOppositeWickToBody) {
        this(wickToBodyRatio, maxOppositeWickToBody, 50, 0.5);
    }

    /**
     * Construtor completo com parâmetros de S/R.
     *
     * @param wickToBodyRatio ratio mínimo do pavio principal
     * @param maxOppositeWickToBody ratio máximo do pavio oposto
     * @param srLookback período para identificar níveis S/R
     * @param tolerancePct percentual convertido para multiplicador de ATR
     */
    public PinBarStrategy(double wickToBodyRatio,
                          double maxOppositeWickToBody,
                          int srLookback,
                          double tolerancePct) {
        if (wickToBodyRatio <= 0) throw new IllegalArgumentException("wickToBodyRatio must be > 0");
        if (maxOppositeWickToBody < 0) throw new IllegalArgumentException("maxOppositeWickToBody must be >= 0");
        if (srLookback < 5) throw new IllegalArgumentException("srLookback must be >= 5");

        this.wickToBodyRatio = wickToBodyRatio;
        this.maxOppositeWickToBody = maxOppositeWickToBody;
        this.srLookback = srLookback;
        // Converte percentual para multiplicador do ATR
        // ⚠️ O Math.max(0.3, ...) garante um mínimo, mas o * 1000 pode ser excessivo
        this.toleranceMultiplier = Math.max(0.3, tolerancePct * 1000);
    }

    @Override
    public String name() {
        return "PinBarStrategy";
    }

    /**
     * Avalia se o último candle é um Pin Bar válido próximo a um nível S/R.
     *
     * Fluxo:
     * 1. Calcula tamanho do corpo e pavios
     * 2. Identifica máxima/mínima dos últimos N candles como S/R simplificado
     * 3. Calcula tolerância baseada em ATR
     * 4. Verifica se o candle está próximo a suporte ou resistência
     * 5. Se próximo a suporte + bullish pin bar → BUY
     * 6. Se próximo a resistência + bearish pin bar → SELL
     *
     * @param bars lista de candles para análise
     * @return Signal (BUY, SELL ou NONE) com metadata
     */
    @Override
    public Signal checkSignal(List<Bar> bars) {
        if (bars == null) return Signal.none(name());
        if (bars.size() < srLookback + 1) return Signal.none(name());

        Bar b = bars.get(bars.size() - 1);

        // ── Anatomia do candle ──
        double body = Math.abs(b.close() - b.open());
        // Dojis (body == 0) não são Pin Bars válidos
        if (body == 0.0) return Signal.none(name());

        // Calcula tamanho dos pavios
        double upperWick = b.high() - Math.max(b.open(), b.close());
        double lowerWick = Math.min(b.open(), b.close()) - b.low();

        // Ratios: quantas vezes o pavio é maior que o corpo
        double lowerRatio = lowerWick / body;
        double upperRatio = upperWick / body;

        // ── Identificação simplificada de S/R ──
        // Usa máxima/mínima do período como níveis de referência
        // Abordagem simples mas funcional para Pin Bar contextualizado
        double prevHigh = Double.NEGATIVE_INFINITY;
        double prevLow = Double.POSITIVE_INFINITY;

        int start = bars.size() - 1 - srLookback;
        int end = bars.size() - 2; // Exclui candle atual

        for (int i = start; i <= end; i++) {
            Bar x = bars.get(i);
            prevHigh = Math.max(prevHigh, x.high());
            prevLow = Math.min(prevLow, x.low());
        }

        // ── Tolerância baseada em ATR ──
        // Adapta automaticamente a zona de proximidade à volatilidade do ativo
        double atr = calculateATR(bars, 14);
        double tolerance = atr * toleranceMultiplier;

        // Verifica proximidade com S/R
        boolean nearResistance = Math.abs(b.high() - prevHigh) <= tolerance;
        boolean nearSupport = Math.abs(b.low() - prevLow) <= tolerance;

        // Metadata completa para rastreabilidade
        Map<String, Object> meta = new HashMap<>();
        meta.put("wickToBodyRatio", wickToBodyRatio);
        meta.put("maxOppositeWickToBody", maxOppositeWickToBody);
        meta.put("upperWick", upperWick);
        meta.put("lowerWick", lowerWick);
        meta.put("body", body);
        meta.put("upperRatio", upperRatio);
        meta.put("lowerRatio", lowerRatio);
        meta.put("srLookback", srLookback);
        meta.put("atr", atr);
        meta.put("tolerance", tolerance);
        meta.put("prevHigh", prevHigh);
        meta.put("prevLow", prevLow);
        meta.put("nearResistance", nearResistance);
        meta.put("nearSupport", nearSupport);

        // ── Bullish Pin Bar próximo a suporte ──
        // Pavio inferior longo = preço tentou cair mas foi rejeitado
        // Pavio superior curto = pouca pressão vendedora remanescente
        if (nearSupport && lowerRatio >= wickToBodyRatio && upperRatio <= maxOppositeWickToBody) {
            return Signal.buy(name(), b.timestamp(), b.close(), meta);
        }

        // ── Bearish Pin Bar próximo a resistência ──
        // Pavio superior longo = preço tentou subir mas foi rejeitado
        // Pavio inferior curto = pouca pressão compradora remanescente
        if (nearResistance && upperRatio >= wickToBodyRatio && lowerRatio <= maxOppositeWickToBody) {
            return Signal.sell(name(), b.timestamp(), b.close(), meta);
        }

        return Signal.none(name());
    }

    /**
     * Calcula o ATR usado para definir a tolerância de proximidade S/R.
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