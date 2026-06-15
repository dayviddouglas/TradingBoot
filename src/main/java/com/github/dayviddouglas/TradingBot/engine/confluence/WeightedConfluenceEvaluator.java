package com.github.dayviddouglas.TradingBot.engine.confluence;

import com.github.dayviddouglas.TradingBot.engine.regime.MarketRegime;
import com.github.dayviddouglas.TradingBot.engine.regime.MarketRegimeClassifier;
import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.model.Signal;
import com.github.dayviddouglas.TradingBot.strategy.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Responsável por orquestrar a avaliação de confluência ponderada por regime de mercado
 * e produzir uma {@link ConfluenceDecision} com todos os dados da decisão.
 *
 * Fluxo de avaliação:
 * <ol>
 *   <li>Classifica o regime atual via {@link MarketRegimeClassifier}</li>
 *   <li>Bloqueia imediatamente quando o regime for {@code CHOPPY}, retornando
 *       {@link ConfluenceDecision} com {@code NONE}</li>
 *   <li>Obtém os pesos por estratégia para o regime via {@link StrategyWeightProfile}</li>
 *   <li>Executa cada estratégia e acumula os votos ponderados no {@link ScoreAccumulator}</li>
 *   <li>Aplica a {@link ConfluenceRule} para resolver o tipo do sinal final</li>
 *   <li>Retorna {@link ConfluenceDecision} com scores, regime, votos e estratégias decisoras</li>
 * </ol>
 *
 * Delega para componentes especializados, mantendo esta classe focada na orquestração:
 * <ul>
 *   <li>{@link MarketRegimeClassifier} — classifica o regime a partir dos candles</li>
 *   <li>{@link StrategyWeightProfile} — fornece os pesos por regime</li>
 *   <li>{@link ScoreAccumulator} — acumula e expõe os votos ponderados</li>
 *   <li>{@link ConfluenceRule} — aplica os critérios mínimos e resolve o sinal final</li>
 * </ul>
 */
public class WeightedConfluenceEvaluator {

    private static final Logger log =
            LoggerFactory.getLogger(WeightedConfluenceEvaluator.class);

    private final MarketRegimeClassifier regimeClassifier;
    private final ConfluenceRule rule;

    /**
     * Construtor com valores padrão.
     * Utiliza {@link MarketRegimeClassifier} e {@link ConfluenceRule#DEFAULT}.
     */
    public WeightedConfluenceEvaluator() {
        this(new MarketRegimeClassifier(), ConfluenceRule.DEFAULT);
    }

    /**
     * Construtor parametrizável para calibragem e testes unitários.
     *
     * @param regimeClassifier classificador de regime a ser utilizado
     * @param rule             regra de confluência com os critérios mínimos de decisão
     */
    public WeightedConfluenceEvaluator(
            MarketRegimeClassifier regimeClassifier,
            ConfluenceRule rule
    ) {
        this.regimeClassifier = regimeClassifier;
        this.rule = rule;
    }

    /**
     * Avalia a confluência ponderada das estratégias sobre os candles fornecidos.
     * Retorna {@link ConfluenceDecision} com {@code NONE} quando a entrada for inválida
     * ou quando o regime for {@code CHOPPY}.
     *
     * @param bars       candles disponíveis para classificação de regime e avaliação das estratégias
     * @param strategies estratégias habilitadas a serem avaliadas; mínimo de 2 para o modo CONFLUENCE
     * @return {@link ConfluenceDecision} com o resultado completo da avaliação
     */
    public ConfluenceDecision evaluate(
            List<Bar> bars,
            List<TradingStrategy> strategies
    ) {
        if (isInputInvalid(bars, strategies)) {
            log.debug("WEIGHTED CONFLUENCE | no bars or strategies");
            return emptyDecision(MarketRegime.CHOPPY);
        }

        MarketRegime regime = regimeClassifier.classify(bars);

        // CHOPPY é bloqueado antes da acumulação de scores — nenhuma família
        // de estratégia costuma ter edge em mercado sem direção definida
        if (regime == MarketRegime.CHOPPY) {
            log.debug("WEIGHTED CONFLUENCE | blocked by regime=CHOPPY");
            return emptyDecision(regime);
        }

        ScoreAccumulator accumulator = accumulateScores(bars, strategies, regime);

        log.debug("WEIGHTED CONFLUENCE | regime={} | {} | rule={}",
                regime, accumulator.toLogString(), rule.toLogString());

        return buildDecision(accumulator, regime);
    }

    // ═══════════════════════════════════════════════════════════════
    // Acumulação de scores
    // ═══════════════════════════════════════════════════════════════

    /**
     * Executa cada estratégia sobre os candles, obtém o peso correspondente no regime atual
     * e acumula o voto ponderado no {@link ScoreAccumulator}.
     * Estratégias não mapeadas no {@link StrategyWeightProfile} recebem peso {@code 1.0}.
     *
     * @param bars       candles para avaliação das estratégias
     * @param strategies lista de estratégias habilitadas
     * @param regime     regime classificado, utilizado para obter os pesos
     * @return acumulador com todos os votos ponderados registrados
     */
    private ScoreAccumulator accumulateScores(
            List<Bar> bars,
            List<TradingStrategy> strategies,
            MarketRegime regime
    ) {
        Map<String, Double> weights = StrategyWeightProfile.weightsFor(regime);
        ScoreAccumulator accumulator = new ScoreAccumulator();

        for (TradingStrategy strategy : strategies) {
            Signal signal = strategy.checkSignal(bars);
            String strategyName = strategy.name();
            double weight = weights.getOrDefault(strategyName, 1.0);

            accumulator.accumulate(strategyName, signal.getType(), weight);

            if (signal.getType() == Signal.Type.NONE) {
                log.debug("WEIGHTED CONFLUENCE | strategy={} | signal=NONE " +
                                "| regime={} | configuredWeight={}",
                        strategyName, regime, weight);
            }
        }

        return accumulator;
    }

    // ═══════════════════════════════════════════════════════════════
    // Construção da decisão
    // ═══════════════════════════════════════════════════════════════

    /**
     * Aplica a {@link ConfluenceRule} ao acumulador para resolver o tipo final do sinal
     * e constrói a {@link ConfluenceDecision} com todos os dados da avaliação.
     *
     * @param accumulator acumulador com votos ponderados de todas as estratégias
     * @param regime      regime classificado para esta avaliação
     * @return decisão de confluência com tipo final, scores, votos e estratégias decisoras
     */
    private ConfluenceDecision buildDecision(
            ScoreAccumulator accumulator,
            MarketRegime regime
    ) {
        Signal.Type finalType = rule.resolve(accumulator);

        if (finalType == Signal.Type.NONE) {
            log.debug("WEIGHTED CONFLUENCE | rejected | regime={} | {} | {}",
                    regime, accumulator.toLogString(), rule.toLogString());
        }

        List<String> decisionStrategies =
                accumulator.getDecisionStrategies(finalType);

        return new ConfluenceDecision(
                finalType,
                regime,
                accumulator.getBuyScore(),
                accumulator.getSellScore(),
                decisionStrategies,
                accumulator.getVotes()
        );
    }

    /**
     * Constrói uma {@link ConfluenceDecision} vazia com {@code NONE} e scores zerados.
     * Retornada quando a entrada for inválida ou o regime for {@code CHOPPY}.
     *
     * @param regime regime a ser registrado na decisão vazia
     * @return decisão de confluência sem sinal e com listas vazias
     */
    private ConfluenceDecision emptyDecision(MarketRegime regime) {
        return new ConfluenceDecision(
                Signal.Type.NONE,
                regime,
                0.0,
                0.0,
                List.of(),
                List.of()
        );
    }

    // ═══════════════════════════════════════════════════════════════
    // Utilitários
    // ═══════════════════════════════════════════════════════════════

    /**
     * Verifica se a entrada é inválida para iniciar a avaliação.
     * A lista de candles não pode ser nula e a lista de estratégias não pode ser nula nem vazia.
     *
     * @param bars       candles fornecidos para avaliação
     * @param strategies estratégias fornecidas para avaliação
     * @return {@code true} se a entrada for inválida
     */
    private boolean isInputInvalid(List<Bar> bars, List<TradingStrategy> strategies) {
        return bars == null
                || strategies == null
                || strategies.isEmpty();
    }
}