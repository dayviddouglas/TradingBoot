package com.github.dayviddouglas.TradingBot.engine;

import java.util.Map;

/**
 * Perfil de pesos das estratégias por regime de mercado.
 *
 * Define quanto cada estratégia "vale" dependendo do contexto atual
 * do mercado (TRENDING, RANGING ou CHOPPY). Esses pesos são usados
 * pelo WeightedConfluenceEvaluator no modo CONFLUENCE para calcular
 * o buyScore e sellScore ponderados.
 *
 * A lógica econômica dos pesos é:
 * - Estratégias de reversão (Bollinger, ZScore, PinBar, SupportResistance)
 *   recebem peso ALTO em RANGING e peso BAIXO em TRENDING
 * - Estratégias de tendência/breakout (EmaRsi, Breakout, Keltner, Donchian)
 *   recebem peso ALTO em TRENDING e peso BAIXO em RANGING
 * - Em CHOPPY, todos os pesos são muito baixos (0.2), porque nenhuma
 *   família de estratégia costuma ter edge em mercado ruidoso
 *
 * IMPORTANTE SOBRE AS CHAVES:
 * As chaves do Map DEVEM bater exatamente com o retorno de strategy.name()
 * de cada estratégia. Se houver divergência, o WeightedConfluenceEvaluator
 * usará o peso padrão de 1.0 (via getOrDefault), anulando todo o benefício
 * da ponderação por regime.
 *
 * Mapeamento de chaves:
 * ┌────────────────────────────┬────────────────────────────┐
 * │ Chave no Map               │ Retorno de strategy.name() │
 * ├────────────────────────────┼────────────────────────────┤
 * │ "EmaRsi"                   │ EmaRsiStrategy.name()      │
 * │ "Breakout"                 │ BreakoutStrategy.name()    │
 * │ "KeltnerChannel"           │ KeltnerChannelStrategy     │
 * │ "DonchianBreakout"         │ DonchianBreakoutStrategy   │
 * │ "BollingerMeanReversion"   │ BollingerMeanReversion...  │
 * │ "ZScoreMeanReversion"      │ ZScoreMeanReversion...     │
 * │ "PinBar"                   │ PinBarStrategy.name()      │
 * │ "SupportResistance"        │ SupportResistanceStrategy  │
 * └────────────────────────────┴────────────────────────────┘
 *
 * Classe utilitária final (não instanciável):
 * - Construtor privado impede instanciação
 * - Métodos estáticos acessíveis diretamente
 * - Sem estado mutável (thread-safe por natureza)
 *
 * ⚠️ Ponto de atenção: Os valores dos pesos (1.4, 0.5, etc.) foram
 * definidos empiricamente e NÃO foram validados quantitativamente
 * por backtest segmentado por regime. Eles representam a intenção
 * conceitual de favorecer estratégias coerentes com o regime,
 * mas podem não ser ótimos na prática.
 *
 * ⚠️ Ponto de atenção: Adicionar uma nova estratégia ao sistema exige
 * atualizar TODOS os três mapas aqui (TRENDING, RANGING, CHOPPY).
 * Se esquecer, a nova estratégia cairá no peso padrão 1.0 silenciosamente.
 *
 * 💡 Sugestão: Em evolução futura, considere:
 * - Mover os pesos para o strategies.json por ativo
 * - Calcular pesos automaticamente via backtest por regime
 * - Usar Map.ofEntries() para facilitar adição de novas estratégias
 */
public final class StrategyWeightProfile {

    /**
     * Construtor privado impede instanciação.
     *
     * Esta classe segue o padrão Utility Class: contém apenas métodos
     * estáticos e dados imutáveis. Não faz sentido instanciá-la.
     */
    private StrategyWeightProfile() {
    }

    /**
     * Retorna o mapa de pesos para o regime especificado.
     *
     * Os Maps retornados são imutáveis (Map.of), garantindo que
     * nenhum chamador possa alterar os pesos em runtime.
     *
     * Interpretação dos valores de peso:
     * - > 1.0: estratégia FAVORECIDA neste regime (voto vale mais)
     * - = 1.0: peso neutro (nenhum ajuste)
     * - < 1.0: estratégia DESFAVORECIDA neste regime (voto vale menos)
     * - = 0.2: quase irrelevante (peso mínimo prático)
     *
     * Exemplo prático com 2 estratégias em RANGING:
     * - BollingerMeanReversion BUY: peso 1.4 → buyScore += 1.4
     * - ZScoreMeanReversion BUY: peso 1.4 → buyScore += 1.4
     * - buyScore total = 2.8
     * - Se minDecisionScore do evaluator for 2.4, o sinal é aceito
     *
     * @param regime regime de mercado atual
     * @return mapa imutável de pesos (chave = nome da estratégia, valor = peso)
     */
    public static Map<String, Double> weightsFor(MarketRegime regime) {
        return switch (regime) {

            // ── TRENDING ──
            // Estratégias de tendência/breakout ganham peso alto
            // Estratégias de reversão perdem peso significativamente
            //
            // Lógica: em tendência, rompimentos e continuação são mais
            // confiáveis, enquanto sinais de reversão são prematuros
            case TRENDING -> Map.of(
                    "EmaRsi", 1.4,                    // Momentum forte em tendência
                    "Breakout", 1.6,                  // Rompimentos confiáveis em tendência
                    "KeltnerChannel", 1.4,            // Breakout com volatilidade
                    "DonchianBreakout", 1.7,          // Breakout clássico, peso mais alto
                    "BollingerMeanReversion", 0.5,    // Reversão fraca em tendência
                    "ZScoreMeanReversion", 0.5,       // Reversão fraca em tendência
                    "PinBar", 0.7,                    // Rejeição pode funcionar em retração
                    "SupportResistance", 0.6           // Níveis podem ser rompidos em tendência
            );

            // ── RANGING ──
            // Estratégias de reversão ganham peso alto
            // Estratégias de breakout perdem peso significativamente
            //
            // Lógica: em range, o preço tende a retornar à média,
            // tornando reversão mais confiável e breakouts mais falsos
            //
            // Este é o regime mais relevante para o projeto atual,
            // onde Bollinger e ZScore são as estratégias ativas
            case RANGING -> Map.of(
                    "EmaRsi", 0.7,                    // EMA crossovers são ruidosos em range
                    "Breakout", 0.5,                  // Falsos rompimentos comuns em range
                    "KeltnerChannel", 0.5,            // Breakout falho em range
                    "DonchianBreakout", 0.6,          // Breakout falho em range
                    "BollingerMeanReversion", 1.4,    // Mean reversion forte em range
                    "ZScoreMeanReversion", 1.4,       // Mean reversion forte em range
                    "PinBar", 1.6,                    // Rejeição em extremos funciona bem
                    "SupportResistance", 1.3           // Níveis respeitados em range
            );

            // ── CHOPPY ──
            // Todos os pesos são muito baixos (0.2)
            //
            // Lógica: em mercado confuso, nenhuma família de estratégia
            // costuma ter edge. Manter pesos baixos faz com que seja
            // muito difícil atingir o minDecisionScore do evaluator,
            // efetivamente bloqueando sinais.
            //
            // ⚠️ Ponto de atenção: Na prática, o WeightedConfluenceEvaluator
            // já bloqueia CHOPPY antes de consultar os pesos. Portanto,
            // estes valores nunca são efetivamente usados. Eles existem
            // apenas como proteção adicional caso o bloqueio seja removido
            // no futuro.
            case CHOPPY -> Map.of(
                    "EmaRsi", 0.2,
                    "Breakout", 0.2,
                    "KeltnerChannel", 0.2,
                    "DonchianBreakout", 0.2,
                    "BollingerMeanReversion", 0.2,
                    "ZScoreMeanReversion", 0.2,
                    "PinBar", 0.2,
                    "SupportResistance", 0.2
            );
        };
    }

    /**
     * Retorna o peso de uma estratégia específica para o regime informado.
     *
     * Método de conveniência que encapsula a busca no mapa com fallback.
     *
     * O valor padrão 1.0 (via getOrDefault) é retornado quando:
     * - A estratégia não está mapeada no perfil de pesos
     * - Isso pode acontecer se uma nova estratégia foi adicionada ao sistema
     *   mas não foi incluída nos mapas acima
     *
     * ⚠️ Ponto de atenção: O fallback para 1.0 é silencioso. Uma estratégia
     * não mapeada receberá peso neutro sem nenhum aviso. Considere adicionar
     * um log.warn nesse caso para facilitar detecção de estratégias não configuradas.
     *
     * @param regime regime de mercado
     * @param strategyName nome da estratégia (deve bater com strategy.name())
     * @return peso da estratégia para o regime ou 1.0 se não mapeada
     */
    public static double getWeight(MarketRegime regime, String strategyName) {
        return weightsFor(regime).getOrDefault(strategyName, 1.0);
    }
}