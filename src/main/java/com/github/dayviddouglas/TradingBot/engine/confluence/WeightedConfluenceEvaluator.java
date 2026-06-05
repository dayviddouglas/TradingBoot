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
 * Avaliador de confluência ponderada por regime de mercado.
 *
 * Responsabilidade única: orquestrar a avaliação das estratégias
 * e produzir uma ConfluenceDecision com base nos scores ponderados.
 *
 * Após refatoração, delega para componentes especializados:
 * - MarketRegimeClassifier → classifica o regime atual
 * - StrategyWeightProfile  → fornece pesos por regime
 * - ScoreAccumulator       → acumula votos ponderados
 * - ConfluenceRule         → aplica critérios de decisão
 *
 * Fluxo:
 * 1. Classifica regime → bloqueia CHOPPY imediatamente
 * 2. Obtém pesos para o regime atual
 * 3. Roda cada estratégia e acumula votos ponderados
 * 4. Aplica regra de confluência para resolver sinal final
 * 5. Retorna ConfluenceDecision com todos os dados
 */
public class WeightedConfluenceEvaluator {

    private static final Logger log =
            LoggerFactory.getLogger(WeightedConfluenceEvaluator.class);

    private final MarketRegimeClassifier regimeClassifier;
    private final ConfluenceRule rule;

    /**
     * Construtor com valores padrão.
     */
    public WeightedConfluenceEvaluator() {
        this(new MarketRegimeClassifier(), ConfluenceRule.DEFAULT);
    }

    /**
     * Construtor parametrizável para calibragem e testes.
     *
     * @param regimeClassifier classificador de regime
     * @param rule             regra de confluência com critérios de decisão
     */
    public WeightedConfluenceEvaluator(
            MarketRegimeClassifier regimeClassifier,
            ConfluenceRule rule
    ) {
        this.regimeClassifier = regimeClassifier;
        this.rule = rule;
    }

    /**
     * Avalia a confluência ponderada das estratégias.
     *
     * @param bars       lista de candles para análise
     * @param strategies lista de estratégias habilitadas
     * @return ConfluenceDecision com resultado e metadados completos
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

    private boolean isInputInvalid(List<Bar> bars, List<TradingStrategy> strategies) {
        return bars == null
                || strategies == null
                || strategies.isEmpty();
    }
}