package com.github.dayviddouglas.TradingBot.engine.regime;

/**
 * Enum que classifica o regime (contexto) atual do mercado.
 *
 * O regime é determinado pelo MarketRegimeClassifier com base em heurísticas
 * que analisam:
 * - ATR curto vs ATR base (intensidade do movimento)
 * - Distância entre EMAs (presença de direção)
 * - Eficiência do movimento (linearidade vs ziguezague)
 *
 * O regime influencia o comportamento do sistema de duas formas:
 *
 * 1. No modo CONFLUENCE: os pesos das estratégias mudam conforme o regime.
 *    Estratégias de reversão ganham peso em RANGING; estratégias de breakout
 *    ganham peso em TRENDING. Em CHOPPY, a confluência é bloqueada.
 *
 * 2. No modo VOTING: o regime NÃO influencia a decisão. As estratégias
 *    votam independentemente do contexto de mercado.
 *
 * ⚠️ Ponto de atenção: O regime não é um sinal de compra ou venda.
 * Ele é apenas uma tentativa de classificar o ambiente operacional
 * para ajustar o comportamento do sistema conforme o contexto.
 *
 * Limitação conhecida: A classificação é baseada em heurísticas simples
 * e pode gerar falsos positivos (ex: classificar um spike momentâneo
 * como TRENDING). Considere isso ao interpretar decisões baseadas em regime.
 *
 * Alinhamento conceitual entre estratégias e regimes:
 * ┌───────────────────────────┬─────────┬─────────┬─────────┐
 * │ Estratégia                │TRENDING │ RANGING │ CHOPPY  │
 * ├───────────────────────────┼─────────┼─────────┼─────────┤
 * │ BollingerMeanReversion    │  fraco  │  forte  │  ruim   │
 * │ ZScoreMeanReversion       │  fraco  │  forte  │  ruim   │
 * │ Breakout                  │  forte  │  fraco  │  ruim   │
 * │ DonchianBreakout          │  forte  │  fraco  │  ruim   │
 * │ KeltnerChannel            │  forte  │  fraco  │  ruim   │
 * │ EmaRsi                    │  forte  │  fraco  │  ruim   │
 * │ PinBar                    │  médio  │  forte  │  ruim   │
 * │ SupportResistance         │  fraco  │  forte  │  ruim   │
 * └───────────────────────────┴─────────┴─────────┴─────────┘
 */
public enum MarketRegime {

    /**
     * Mercado com direção relativamente clara.
     *
     * Características:
     * - Preço andando de forma mais linear (eficiência alta)
     * - EMAs bem separadas (indicando momentum direcional)
     * - ATR consistente com a tendência
     *
     * Estratégias mais adequadas:
     * - Breakout
     * - DonchianBreakout
     * - KeltnerChannel
     * - EmaRsi
     *
     * Estratégias menos adequadas:
     * - BollingerMeanReversion
     * - ZScoreMeanReversion
     * - SupportResistance
     *
     * Analogia simples: uma estrada reta onde o preço anda para frente.
     */
    TRENDING,

    /**
     * Mercado lateral / reversivo.
     *
     * Características:
     * - Preço oscilando dentro de uma faixa (eficiência baixa)
     * - EMAs próximas ou cruzando frequentemente
     * - ATR dentro da normalidade histórica
     *
     * Estratégias mais adequadas:
     * - BollingerMeanReversion
     * - ZScoreMeanReversion
     * - PinBar
     * - SupportResistance
     *
     * Estratégias menos adequadas:
     * - Breakout (gera muitos falsos rompimentos)
     * - DonchianBreakout
     * - EmaRsi (cruzamentos de EMA geram ruído)
     *
     * Analogia simples: uma praça onde o preço fica andando de um lado para o outro.
     *
     * Este é o regime onde as estratégias atuais do projeto (Bollinger + ZScore)
     * tendem a apresentar melhor desempenho, conforme evidenciado nos backtests.
     */
    RANGING,

    /**
     * Mercado ruim / confuso / ruidoso.
     *
     * Características:
     * - Preço se movendo de forma errática sem padrão
     * - Nem tendência clara nem faixa definida
     * - Muito ruído e pouca previsibilidade
     *
     * Comportamento do sistema:
     * - No modo CONFLUENCE: a avaliação é bloqueada (nenhum sinal é emitido)
     * - No modo VOTING: o regime não influencia (pode operar normalmente)
     *
     * Nenhuma família de estratégia costuma ter edge consistente neste regime.
     * Operar em CHOPPY geralmente degrada a expectância do sistema.
     *
     * ⚠️ Ponto de atenção: O modo VOTING não filtra por regime, então
     * pode gerar sinais em CHOPPY. Isso é intencional mas pode ser
     * uma fonte de trades de baixa qualidade.
     *
     * Analogia simples: uma rua esburacada onde o preço pula e muda de direção o tempo todo.
     */
    CHOPPY
}