package com.github.dayviddouglas.TradingBot.engine;

/**
 * Enum que define o modo de decisão do StrategyEngine.
 *
 * O modo de decisão é configurado por ativo no bloco "engine" do
 * strategies.json e determina como o StrategyEngine processa os
 * sinais das estratégias habilitadas para gerar o sinal final.
 *
 * O sistema suporta três modos, cada um com comportamento distinto:
 *
 * ═══════════════════════════════════════════════════════════════
 *
 * CONFLUENCE:
 * - Usa classificação de regime de mercado (TRENDING, RANGING, CHOPPY)
 * - Aplica pesos diferentes por estratégia conforme o regime
 * - Calcula buyScore e sellScore ponderados
 * - Exige score mínimo e controla o score de oposição
 * - Mais sofisticado, mas depende de calibragem correta dos pesos
 * - Requer pelo menos 2 estratégias habilitadas
 *
 * ═══════════════════════════════════════════════════════════════
 *
 * SINGLE_STRATEGY:
 * - Usa o sinal direto da única estratégia habilitada
 * - Sem pesos, sem score, sem regime
 * - O sinal da estratégia vira o sinal final diretamente
 * - Ideal para testar o edge de uma estratégia isoladamente
 * - Requer exatamente 1 estratégia habilitada
 *
 * ═══════════════════════════════════════════════════════════════
 *
 * VOTING:
 * - Votação conservadora por unanimidade
 * - Sem pesos, sem score, sem regime
 * - Só emite sinal quando TODAS as estratégias concordam na mesma direção
 * - Qualquer divergência resulta em NONE
 * - Mais simples que CONFLUENCE, mais rigoroso que SINGLE_STRATEGY
 * - Requer pelo menos 2 estratégias habilitadas
 *
 * ═══════════════════════════════════════════════════════════════
 *
 * Exemplo de configuração no strategies.json:
 * {
 *   "engine": {
 *     "maxBars": 1500,
 *     "decisionMode": "VOTING"
 *   }
 * }
 *
 * Cada ativo pode usar um modo diferente no mesmo sistema,
 * permitindo configurações híbridas por ativo.
 */
public enum DecisionMode {
    CONFLUENCE,
    SINGLE_STRATEGY,
    VOTING
}