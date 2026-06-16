package com.github.dayviddouglas.TradingBoot.strategy;

import com.github.dayviddouglas.TradingBoot.model.Bar;
import com.github.dayviddouglas.TradingBoot.model.Signal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Estratégia de rejeição de preço baseada no padrão Pin Bar com confirmação de contexto S/R.
 *
 * Um Pin Bar é identificado pela presença de pavio longo em uma direção e corpo pequeno,
 * indicando que o preço tentou mover-se em uma direção mas foi rejeitado.
 * Para aumentar a confiabilidade, esta estratégia exige que o padrão ocorra
 * próximo a um nível de suporte ou resistência identificado nos últimos {@code srLookback} candles:
 * <ul>
 *   <li><b>Bullish Pin Bar próximo a suporte</b>: pavio inferior longo
 *       ({@code lowerWick/body >= wickToBodyRatio}) com pavio oposto curto
 *       ({@code upperWick/body <= maxOppositeWickToBody}) → {@code BUY}</li>
 *   <li><b>Bearish Pin Bar próximo a resistência</b>: pavio superior longo
 *       ({@code upperWick/body >= wickToBodyRatio}) com pavio oposto curto
 *       ({@code lowerWick/body <= maxOppositeWickToBody}) → {@code SELL}</li>
 * </ul>
 *
 * A tolerância de proximidade é calculada como {@code atr14 × toleranceMultiplier},
 * adaptando automaticamente a zona de proximidade à volatilidade do ativo.
 * O nível de suporte/resistência é a mínima/máxima dos últimos {@code srLookback} candles
 * (excluindo o candle atual).
 */
public class PinBarStrategy implements TradingStrategy {

    /**
     * Ratio mínimo entre pavio principal e corpo para qualificar o padrão como Pin Bar.
     * Exemplo: {@code 3.5} exige que o pavio seja pelo menos 3.5x o tamanho do corpo.
     */
    private final double wickToBodyRatio;

    /**
     * Ratio máximo permitido do pavio oposto em relação ao corpo.
     * Garante que o Pin Bar tenha rejeição clara em uma única direção.
     * Exemplo: {@code 0.15} significa que o pavio oposto não pode exceder 15% do corpo.
     */
    private final double maxOppositeWickToBody;

    /** Período de lookback para identificar os níveis de suporte e resistência. */
    private final int srLookback;

    /**
     * Multiplicador aplicado sobre o ATR de 14 períodos para definir a tolerância de proximidade.
     * Calculado no construtor a partir do {@code tolerancePct} informado.
     */
    private final double toleranceMultiplier;

    /**
     * Construtor simplificado com valores padrão para os parâmetros de S/R.
     *
     * @param wickToBodyRatio        ratio mínimo do pavio principal em relação ao corpo
     * @param maxOppositeWickToBody  ratio máximo do pavio oposto em relação ao corpo
     */
    public PinBarStrategy(double wickToBodyRatio, double maxOppositeWickToBody) {
        this(wickToBodyRatio, maxOppositeWickToBody, 50, 0.5);
    }

    /**
     * Construtor completo com todos os parâmetros configuráveis.
     *
     * @param wickToBodyRatio        ratio mínimo do pavio principal em relação ao corpo; deve ser positivo
     * @param maxOppositeWickToBody  ratio máximo do pavio oposto em relação ao corpo; deve ser {@code >= 0}
     * @param srLookback             período para identificar níveis de S/R; mínimo 5
     * @param tolerancePct           percentual convertido para multiplicador de ATR via {@code tolerancePct * 1000}
     * @throws IllegalArgumentException se algum parâmetro for inválido
     */
    public PinBarStrategy(double wickToBodyRatio,
                          double maxOppositeWickToBody,
                          int srLookback,
                          double tolerancePct) {
        if (wickToBodyRatio <= 0)
            throw new IllegalArgumentException("wickToBodyRatio must be > 0");
        if (maxOppositeWickToBody < 0)
            throw new IllegalArgumentException("maxOppositeWickToBody must be >= 0");
        if (srLookback < 5)
            throw new IllegalArgumentException("srLookback must be >= 5");

        this.wickToBodyRatio       = wickToBodyRatio;
        this.maxOppositeWickToBody = maxOppositeWickToBody;
        this.srLookback            = srLookback;
        // Converte o percentual para multiplicador do ATR com mínimo de 0.3
        this.toleranceMultiplier   = Math.max(0.3, tolerancePct * 1000);
    }

    /**
     * Identificador da estratégia utilizado em logs e metadata do sinal.
     */
    @Override
    public String name() {
        return "PinBarStrategy";
    }

    /**
     * Avalia se o último candle é um Pin Bar válido próximo a um nível de S/R.
     *
     * Fluxo:
     * <ol>
     *   <li>Calcula corpo e pavios do candle atual; dojis ({@code body == 0}) são descartados</li>
     *   <li>Identifica a máxima e mínima dos últimos {@code srLookback} candles como níveis de referência</li>
     *   <li>Calcula a tolerância de proximidade: {@code atr14 × toleranceMultiplier}</li>
     *   <li>Bullish Pin Bar próximo a suporte → BUY</li>
     *   <li>Bearish Pin Bar próximo a resistência → SELL</li>
     * </ol>
     *
     * @param bars lista de candles para análise
     * @return {@link Signal} com tipo, timestamp, preço e metadata dos indicadores
     */
    @Override
    public Signal checkSignal(List<Bar> bars) {
        if (bars == null) return Signal.none(name());
        if (bars.size() < srLookback + 1) return Signal.none(name());

        Bar b = bars.get(bars.size() - 1);

        double body = Math.abs(b.close() - b.open());
        // Dojis não caracterizam rejeição direcional
        if (body == 0.0) return Signal.none(name());

        double upperWick = b.high() - Math.max(b.open(), b.close());
        double lowerWick = Math.min(b.open(), b.close()) - b.low();

        double lowerRatio = lowerWick / body;
        double upperRatio = upperWick / body;

        // Identifica a máxima e mínima históricas, excluindo o candle atual
        double prevHigh = Double.NEGATIVE_INFINITY;
        double prevLow  = Double.POSITIVE_INFINITY;

        int start = bars.size() - 1 - srLookback;
        int end   = bars.size() - 2;

        for (int i = start; i <= end; i++) {
            Bar x = bars.get(i);
            prevHigh = Math.max(prevHigh, x.high());
            prevLow  = Math.min(prevLow,  x.low());
        }

        // Tolerância adaptativa: zona de proximidade escala com a volatilidade do ativo
        double atr       = calculateATR(bars, 14);
        double tolerance = atr * toleranceMultiplier;

        boolean nearResistance = Math.abs(b.high() - prevHigh) <= tolerance;
        boolean nearSupport    = Math.abs(b.low()  - prevLow)  <= tolerance;

        Map<String, Object> meta = new HashMap<>();
        meta.put("wickToBodyRatio",       wickToBodyRatio);
        meta.put("maxOppositeWickToBody", maxOppositeWickToBody);
        meta.put("upperWick",             upperWick);
        meta.put("lowerWick",             lowerWick);
        meta.put("body",                  body);
        meta.put("upperRatio",            upperRatio);
        meta.put("lowerRatio",            lowerRatio);
        meta.put("srLookback",            srLookback);
        meta.put("atr",                   atr);
        meta.put("tolerance",             tolerance);
        meta.put("prevHigh",              prevHigh);
        meta.put("prevLow",               prevLow);
        meta.put("nearResistance",        nearResistance);
        meta.put("nearSupport",           nearSupport);

        // Bullish Pin Bar: pavio inferior longo próximo a suporte
        if (nearSupport && lowerRatio >= wickToBodyRatio && upperRatio <= maxOppositeWickToBody) {
            return Signal.buy(name(), b.timestamp(), b.close(), meta);
        }

        // Bearish Pin Bar: pavio superior longo próximo a resistência
        if (nearResistance && upperRatio >= wickToBodyRatio && lowerRatio <= maxOppositeWickToBody) {
            return Signal.sell(name(), b.timestamp(), b.close(), meta);
        }

        return Signal.none(name());
    }

    /**
     * Calcula o ATR de 14 períodos utilizado no cálculo da tolerância de proximidade.
     * O primeiro candle da janela usa apenas {@code high - low} por não ter candle anterior.
     *
     * @param bars   lista de candles
     * @param period período do ATR
     * @return ATR calculado ou {@code NaN} se dados insuficientes
     */
    private static double calculateATR(List<Bar> bars, int period) {
        if (bars.size() < period) return Double.NaN;

        double sum = 0.0;
        for (int i = bars.size() - period; i < bars.size(); i++) {
            Bar    current = bars.get(i);
            double tr;

            if (i == bars.size() - period) {
                tr = current.high() - current.low();
            } else {
                Bar previous = bars.get(i - 1);
                tr = Math.max(
                        current.high() - current.low(),
                        Math.max(
                                Math.abs(current.high() - previous.close()),
                                Math.abs(current.low()  - previous.close())
                        )
                );
            }

            sum += tr;
        }

        return sum / period;
    }
}