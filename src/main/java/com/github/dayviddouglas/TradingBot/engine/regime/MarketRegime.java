package com.github.dayviddouglas.TradingBot.engine.regime;

/**
 * Classifica o regime (contexto operacional) atual do mercado.
 *
 * O regime é determinado pelo {@link MarketRegimeClassifier} com base em três heurísticas:
 * intensidade do movimento via ATR curto versus ATR base, presença de direção via distância
 * entre EMAs e linearidade do movimento via Efficiency Ratio.
 *
 * O regime influencia o sistema de duas formas distintas conforme o modo de decisão:
 * <ul>
 *   <li>No modo {@code CONFLUENCE}: os pesos das estratégias variam conforme o regime.
 *       Estratégias de reversão são favorecidas em {@code RANGING}; estratégias de breakout
 *       são favorecidas em {@code TRENDING}. Em {@code CHOPPY}, a avaliação é bloqueada.</li>
 *   <li>No modo {@code VOTING}: o regime não influencia a decisão. As estratégias votam
 *       independentemente do contexto de mercado.</li>
 * </ul>
 *
 * O regime não é um sinal de compra ou venda — é uma estimativa do ambiente operacional
 * para ajustar o comportamento do sistema. A classificação é baseada em heurísticas
 * e pode gerar falsos positivos, como classificar um spike momentâneo como {@code TRENDING}.
 *
 * Alinhamento conceitual entre estratégias e regimes:
 * <pre>
 * ┌───────────────────────────┬──────────┬──────────┬─────────┐
 * │ Estratégia                │ TRENDING │ RANGING  │ CHOPPY  │
 * ├───────────────────────────┼──────────┼──────────┼─────────┤
 * │ BollingerMeanReversion    │  fraco   │  forte   │  ruim   │
 * │ ZScoreMeanReversion       │  fraco   │  forte   │  ruim   │
 * │ Breakout                  │  forte   │  fraco   │  ruim   │
 * │ DonchianBreakout          │  forte   │  fraco   │  ruim   │
 * │ KeltnerChannel            │  forte   │  fraco   │  ruim   │
 * │ EmaRsi                    │  forte   │  fraco   │  ruim   │
 * │ PinBar                    │  médio   │  forte   │  ruim   │
 * │ SupportResistance         │  fraco   │  forte   │  ruim   │
 * └───────────────────────────┴──────────┴──────────┴─────────┘
 * </pre>
 */
public enum MarketRegime {

    /**
     * Mercado com direção relativamente clara.
     *
     * Identificado por eficiência de movimento elevada, EMAs bem separadas
     * indicando momentum direcional e ATR consistente com a tendência.
     *
     * Estratégias mais adequadas: {@code Breakout}, {@code DonchianBreakout},
     * {@code KeltnerChannel}, {@code EmaRsi}.
     *
     * Estratégias menos adequadas: {@code BollingerMeanReversion},
     * {@code ZScoreMeanReversion}, {@code SupportResistance}.
     */
    TRENDING,

    /**
     * Mercado lateral com comportamento reversivo.
     *
     * Identificado por eficiência de movimento baixa, EMAs próximas ou cruzando
     * frequentemente e ATR dentro da normalidade histórica.
     *
     * Estratégias mais adequadas: {@code BollingerMeanReversion},
     * {@code ZScoreMeanReversion}, {@code PinBar}, {@code SupportResistance}.
     *
     * Estratégias menos adequadas: {@code Breakout} (gera falsos rompimentos),
     * {@code DonchianBreakout}, {@code EmaRsi} (cruzamentos geram ruído).
     */
    RANGING,

    /**
     * Mercado ruidoso sem padrão definido.
     *
     * Identificado por movimento errático sem tendência clara nem faixa definida,
     * com alto nível de ruído e baixa previsibilidade.
     *
     * No modo {@code CONFLUENCE}, a avaliação é bloqueada e nenhum sinal é emitido.
     * No modo {@code VOTING}, o regime não influencia a decisão — sinais podem ser
     * gerados, mas a qualidade tende a ser inferior neste contexto.
     *
     * Nenhuma família de estratégia costuma apresentar edge consistente neste regime.
     */
    CHOPPY
}