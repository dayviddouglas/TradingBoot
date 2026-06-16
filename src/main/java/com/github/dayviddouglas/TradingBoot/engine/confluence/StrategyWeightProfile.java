package com.github.dayviddouglas.TradingBoot.engine.confluence;

import com.github.dayviddouglas.TradingBoot.engine.regime.MarketRegime;

import java.util.Map;

/**
 * Define os pesos de cada estratégia por regime de mercado, utilizados pelo
 * {@link WeightedConfluenceEvaluator} para calcular os scores ponderados de
 * {@code buyScore} e {@code sellScore} no modo {@code CONFLUENCE}.
 *
 * A lógica de ponderação segue a coerência entre família de estratégia e regime:
 * <ul>
 *   <li>Estratégias de reversão ({@code BollingerMeanReversion}, {@code ZScoreMeanReversion},
 *       {@code PinBar}, {@code SupportResistance}) recebem peso alto em {@code RANGING}
 *       e peso baixo em {@code TRENDING}</li>
 *   <li>Estratégias de tendência/breakout ({@code EmaRsi}, {@code Breakout},
 *       {@code KeltnerChannel}, {@code DonchianBreakout}) recebem peso alto em {@code TRENDING}
 *       e peso baixo em {@code RANGING}</li>
 *   <li>Em {@code CHOPPY}, todos os pesos são {@code 0.2}, tornando praticamente impossível
 *       atingir o {@code minDecisionScore} da {@link ConfluenceRule}. Na prática, o
 *       {@link WeightedConfluenceEvaluator} já bloqueia {@code CHOPPY} antes de consultar
 *       os pesos; os valores de {@code CHOPPY} existem como proteção adicional</li>
 * </ul>
 *
 * As chaves dos mapas devem corresponder exatamente ao retorno de {@code strategy.name()}
 * de cada implementação de {@link com.github.dayviddouglas.TradingBoot.strategy.TradingStrategy}.
 * Estratégias não mapeadas recebem o peso padrão {@code 1.0} via {@code getOrDefault},
 * sem nenhum aviso em log.
 *
 * Os pesos foram definidos empiricamente e não foram validados por backtest
 * segmentado por regime.
 *
 * Esta é uma classe utilitária final e não instanciável.
 */
public final class StrategyWeightProfile {

    private StrategyWeightProfile() {
    }

    /**
     * Retorna o mapa imutável de pesos para o regime informado.
     *
     * Interpretação dos valores:
     * <ul>
     *   <li>{@code > 1.0}: estratégia favorecida neste regime; voto vale mais</li>
     *   <li>{@code = 1.0}: peso neutro; sem ajuste</li>
     *   <li>{@code < 1.0}: estratégia desfavorecida neste regime; voto vale menos</li>
     *   <li>{@code = 0.2}: peso mínimo prático; estratégia quase irrelevante</li>
     * </ul>
     *
     * Exemplo em {@code RANGING} com 2 estratégias de reversão (peso 1.4 cada):
     * ambas votando {@code BUY} resultam em {@code buyScore = 2.8}, suficiente para
     * superar o {@code minDecisionScore = 2.4} da {@link ConfluenceRule#DEFAULT}.
     *
     * @param regime regime de mercado atual classificado pelo {@link com.github.dayviddouglas.TradingBoot.engine.regime.MarketRegimeClassifier}
     * @return mapa imutável com chave igual ao nome da estratégia e valor igual ao peso
     */
    public static Map<String, Double> weightsFor(MarketRegime regime) {
        return switch (regime) {

            // Estratégias de tendência e breakout são favorecidas.
            // Estratégias de reversão são desfavorecidas, pois rompimentos
            // são mais confiáveis e sinais de reversão tendem a ser prematuros.
            case TRENDING -> Map.of(
                    "EmaRsi",                 1.4,
                    "Breakout",               1.6,
                    "KeltnerChannel",         1.4,
                    "DonchianBreakout",       1.7,
                    "BollingerMeanReversion", 0.5,
                    "ZScoreMeanReversion",    0.5,
                    "PinBar",                 0.7,
                    "SupportResistance",      0.6
            );

            // Estratégias de reversão são favorecidas.
            // Estratégias de breakout são desfavorecidas, pois o preço
            // tende a retornar à média e rompimentos tendem a ser falsos.
            case RANGING -> Map.of(
                    "EmaRsi",                 0.7,
                    "Breakout",               0.5,
                    "KeltnerChannel",         0.5,
                    "DonchianBreakout",       0.6,
                    "BollingerMeanReversion", 1.4,
                    "ZScoreMeanReversion",    1.4,
                    "PinBar",                 1.6,
                    "SupportResistance",      1.3
            );

            // Todos os pesos são mínimos para dificultar ao máximo atingir
            // o minDecisionScore da ConfluenceRule em mercado sem direção definida.
            // Na prática, o WeightedConfluenceEvaluator já bloqueia CHOPPY
            // antes de consultar este mapa.
            case CHOPPY -> Map.of(
                    "EmaRsi",                 0.2,
                    "Breakout",               0.2,
                    "KeltnerChannel",         0.2,
                    "DonchianBreakout",       0.2,
                    "BollingerMeanReversion", 0.2,
                    "ZScoreMeanReversion",    0.2,
                    "PinBar",                 0.2,
                    "SupportResistance",      0.2
            );
        };
    }

    /**
     * Retorna o peso de uma estratégia específica para o regime informado.
     * Quando a estratégia não estiver mapeada, retorna {@code 1.0} como peso neutro.
     *
     * @param regime       regime de mercado atual
     * @param strategyName nome da estratégia; deve corresponder ao retorno de {@code strategy.name()}
     * @return peso configurado para a estratégia no regime, ou {@code 1.0} se não mapeada
     */
    public static double getWeight(MarketRegime regime, String strategyName) {
        return weightsFor(regime).getOrDefault(strategyName, 1.0);
    }
}