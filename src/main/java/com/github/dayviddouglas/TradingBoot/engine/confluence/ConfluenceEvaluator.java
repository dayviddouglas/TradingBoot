package com.github.dayviddouglas.TradingBoot.engine.confluence;

import com.github.dayviddouglas.TradingBoot.engine.decision.DecisionEvaluator;
import com.github.dayviddouglas.TradingBoot.engine.decision.EvaluationResult;
import com.github.dayviddouglas.TradingBoot.model.Bar;
import com.github.dayviddouglas.TradingBoot.model.Signal;
import com.github.dayviddouglas.TradingBoot.strategy.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Implementação de {@link DecisionEvaluator} para o modo {@code CONFLUENCE}.
 *
 * Coordena a avaliação de confluência ponderada delegando toda a lógica de classificação
 * de regime, cálculo de scores e aplicação das regras ao {@link WeightedConfluenceEvaluator}.
 * Após receber a {@link ConfluenceDecision}, adapta o resultado para o contrato de
 * {@link EvaluationResult}, enriquecendo a metadata com regime, scores e votos ponderados.
 *
 * Quando a decisão não atinge os critérios mínimos da {@link ConfluenceRule}
 * (score insuficiente, conflito ou regime {@code CHOPPY}), retorna
 * {@link EvaluationResult#noSignal()} sem emitir sinal.
 */
public class ConfluenceEvaluator implements DecisionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceEvaluator.class);

    /** Responsável pela classificação de regime, cálculo de scores e resolução do sinal final. */
    private final WeightedConfluenceEvaluator weightedConfluenceEvaluator;

    /**
     * Construtor padrão com {@link WeightedConfluenceEvaluator} configurado com valores padrão.
     * Utilizado pelo {@code DecisionEvaluatorFactory} em produção.
     */
    public ConfluenceEvaluator() {
        this(new WeightedConfluenceEvaluator());
    }

    /**
     * Construtor parametrizável para injeção de dependência e testes unitários.
     *
     * @param evaluator instância de {@link WeightedConfluenceEvaluator} a ser utilizada
     */
    public ConfluenceEvaluator(WeightedConfluenceEvaluator evaluator) {
        this.weightedConfluenceEvaluator = evaluator;
    }

    /**
     * Avalia os sinais das estratégias usando confluência ponderada com regime de mercado.
     *
     * Fluxo de avaliação:
     * <ol>
     *   <li>Delega para {@link WeightedConfluenceEvaluator#evaluate} para classificar o regime,
     *       calcular os scores e resolver o tipo do sinal</li>
     *   <li>Verifica se a decisão é válida via {@link ConfluenceDecision#isValid()}</li>
     *   <li>Constrói o {@link EvaluationResult} com metadata enriquecida para rastreabilidade</li>
     * </ol>
     *
     * @param snapshot   cópia imutável dos candles disponíveis para avaliação
     * @param strategies lista de estratégias habilitadas; mínimo de 2 para o modo CONFLUENCE
     * @param symbol     símbolo do ativo, utilizado apenas para os logs de diagnóstico
     * @return resultado da avaliação com tipo do sinal e metadata enriquecida,
     *         ou {@link EvaluationResult#noSignal()} quando os critérios não são atingidos
     */
    @Override
    public EvaluationResult evaluate(List<Bar> snapshot, List<TradingStrategy> strategies, String symbol) {
        ConfluenceDecision decision = weightedConfluenceEvaluator.evaluate(snapshot, strategies);

        if (!decision.isValid()) {
            log.debug("No weighted confluence | symbol={} | regime={} | buyScore={} | sellScore={}",
                    symbol, decision.regime(), decision.buyScore(), decision.sellScore());
            return EvaluationResult.noSignal();
        }

        Signal.Type finalType = decision.finalType();

        Map<String, Object> metadata = buildConfluenceMetadata(decision);

        String logMessage = buildLogMessage(finalType, symbol, decision);

        return new EvaluationResult(finalType, "WeightedConfluence", metadata, logMessage);
    }

    /**
     * Constrói o mapa de metadata enriquecida com os dados específicos do modo {@code CONFLUENCE}.
     * Inclui regime, scores e representação textual dos votos ponderados para auditoria operacional
     * e análise de performance por regime.
     *
     * @param decision decisão completa retornada pelo {@link WeightedConfluenceEvaluator}
     * @return mapa imutável de metadata para compor o {@code Signal} final
     */
    private Map<String, Object> buildConfluenceMetadata(ConfluenceDecision decision) {
        return Map.of(
                "decisionStrategies", decision.decisionStrategies(),
                "regime",             decision.regime().name(),
                "buyScore",           decision.buyScore(),
                "sellScore",          decision.sellScore(),
                "weightedVotes",      decision.weightedVotes().toString()
        );
    }

    /**
     * Formata a mensagem de log estruturada para o sinal final emitido no modo {@code CONFLUENCE}.
     *
     * @param type     tipo do sinal final: {@code BUY} ou {@code SELL}
     * @param symbol   símbolo do ativo
     * @param decision decisão com regime, scores e estratégias decisoras
     * @return mensagem formatada para registro no log operacional
     */
    private String buildLogMessage(Signal.Type type, String symbol, ConfluenceDecision decision) {
        return String.format(
                "FINAL SIGNAL %s | symbol=%s | regime=%s | buyScore=%s | sellScore=%s | decisionStrategies=%s",
                type, symbol, decision.regime(), decision.buyScore(),
                decision.sellScore(), decision.decisionStrategies()
        );
    }
}