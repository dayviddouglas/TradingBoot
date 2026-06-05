package com.github.dayviddouglas.TradingBot.engine.confluence;

import com.github.dayviddouglas.TradingBot.engine.decision.DecisionEvaluator;
import com.github.dayviddouglas.TradingBot.engine.decision.EvaluationResult;
import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.model.Signal;
import com.github.dayviddouglas.TradingBot.strategy.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Avaliador de decisão para o modo CONFLUENCE (confluência ponderada por regime).
 *
 * O modo mais sofisticado do sistema. Usa:
 * - Classificação de regime de mercado (TRENDING, RANGING, CHOPPY)
 * - Pesos diferenciados por estratégia conforme o regime
 * - Score ponderado (buyScore e sellScore)
 * - Critérios mínimos de score e máximos de oposição
 *
 * Delega toda a lógica de avaliação para o WeightedConfluenceEvaluator,
 * mantendo o ConfluenceEvaluator focado apenas na adaptação do resultado
 * para o contrato de DecisionEvaluator.
 *
 * Implementa DecisionEvaluator seguindo o padrão Strategy.
 * O construtor aceita injeção do WeightedConfluenceEvaluator para
 * facilitar testes unitários com mocks.
 */
public class ConfluenceEvaluator implements DecisionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceEvaluator.class);

    /**
     * Avaliador de confluência ponderada.
     * Instanciado com valores padrão ou injetado via construtor parametrizável.
     */
    private final WeightedConfluenceEvaluator weightedConfluenceEvaluator;

    /**
     * Construtor padrão com WeightedConfluenceEvaluator de valores padrão.
     * Usado pela DecisionEvaluatorFactory em produção.
     */
    public ConfluenceEvaluator() {
        this(new WeightedConfluenceEvaluator());
    }

    /**
     * Construtor parametrizável para injeção de dependência e testes.
     *
     * @param evaluator instância de WeightedConfluenceEvaluator a usar
     */
    public ConfluenceEvaluator(WeightedConfluenceEvaluator evaluator) {
        this.weightedConfluenceEvaluator = evaluator;
    }

    /**
     * Avalia sinais usando confluência ponderada com regime de mercado.
     *
     * Fluxo:
     * 1. Delega para WeightedConfluenceEvaluator
     * 2. Verifica se a decisão é válida (score suficiente, regime não-CHOPPY)
     * 3. Retorna EvaluationResult com metadata enriquecida de regime e scores
     *
     * A metadata do resultado inclui regime, buyScore, sellScore e votos ponderados
     * para rastreabilidade e auditoria operacional.
     *
     * @param snapshot cópia imutável dos candles
     * @param strategies lista de estratégias habilitadas (mínimo 2)
     * @param symbol símbolo do ativo para logs
     * @return resultado da avaliação de confluência
     */
    @Override
    public EvaluationResult evaluate(List<Bar> snapshot, List<TradingStrategy> strategies, String symbol) {
        ConfluenceDecision decision = weightedConfluenceEvaluator.evaluate(snapshot, strategies);

        // Decisão inválida: score insuficiente, conflito ou regime CHOPPY
        if (!decision.isValid()) {
            log.debug("No weighted confluence | symbol={} | regime={} | buyScore={} | sellScore={}",
                    symbol, decision.regime(), decision.buyScore(), decision.sellScore());
            return EvaluationResult.noSignal();
        }

        Signal.Type finalType = decision.finalType();

        // Metadata enriquecida para rastreabilidade e análise por regime
        Map<String, Object> metadata = buildConfluenceMetadata(decision);

        String logMessage = buildLogMessage(finalType, symbol, decision);

        return new EvaluationResult(finalType, "WeightedConfluence", metadata, logMessage);
    }

    /**
     * Constrói a metadata enriquecida específica do modo CONFLUENCE.
     *
     * Inclui regime, scores e votos ponderados para:
     * - Auditoria operacional
     * - Análise de performance por regime
     * - Debug de decisões questionáveis
     *
     * @param decision decisão completa da confluência
     * @return mapa de metadata para o Signal final
     */
    private Map<String, Object> buildConfluenceMetadata(ConfluenceDecision decision) {
        return Map.of(
                "decisionStrategies", decision.decisionStrategies(),
                "regime", decision.regime().name(),
                "buyScore", decision.buyScore(),
                "sellScore", decision.sellScore(),
                "weightedVotes", decision.weightedVotes().toString()
        );
    }

    /**
     * Formata a mensagem de log para o sinal final de confluência.
     *
     * @param type tipo do sinal
     * @param symbol símbolo do ativo
     * @param decision decisão de confluência com scores e regime
     * @return mensagem formatada para log
     */
    private String buildLogMessage(Signal.Type type, String symbol, ConfluenceDecision decision) {
        return String.format(
                "FINAL SIGNAL %s | symbol=%s | regime=%s | buyScore=%s | sellScore=%s | decisionStrategies=%s",
                type, symbol, decision.regime(), decision.buyScore(),
                decision.sellScore(), decision.decisionStrategies()
        );
    }
}