package com.github.dayviddouglas.TradingBot.engine.decision;

/**
 * Define o modo de decisão do {@link com.github.dayviddouglas.TradingBot.engine.core.StrategyEngine}.
 *
 * Configurado por ativo no bloco {@code engine} do strategies.json e determina como o engine
 * processa os sinais das estratégias habilitadas para produzir o sinal final.
 * Cada ativo pode utilizar um modo diferente no mesmo sistema, permitindo configurações híbridas.
 *
 * <pre>
 * Exemplo de configuração no strategies.json:
 * {
 *   "engine": {
 *     "maxBars": 1500,
 *     "decisionMode": "VOTING"
 *   }
 * }
 * </pre>
 *
 * <b>CONFLUENCE</b>:
 * Classifica o regime de mercado ({@code TRENDING}, {@code RANGING}, {@code CHOPPY}) e aplica
 * pesos diferenciados por estratégia conforme o regime. Calcula {@code buyScore} e {@code sellScore}
 * ponderados e exige score mínimo com controle do score de oposição.
 * Requer pelo menos 2 estratégias habilitadas.
 *
 * <b>SINGLE_STRATEGY</b>:
 * Utiliza o sinal direto da única estratégia habilitada sem pesos, score ou regime.
 * Ideal para testar o edge de uma estratégia de forma isolada.
 * Requer exatamente 1 estratégia habilitada.
 *
 * <b>VOTING</b>:
 * Votação conservadora por unanimidade: emite sinal apenas quando todas as estratégias
 * concordam na mesma direção. Qualquer divergência resulta em {@code NONE}.
 * Sem pesos, score ou regime. Requer pelo menos 2 estratégias habilitadas.
 */
public enum DecisionMode {
    CONFLUENCE,
    SINGLE_STRATEGY,
    VOTING
}