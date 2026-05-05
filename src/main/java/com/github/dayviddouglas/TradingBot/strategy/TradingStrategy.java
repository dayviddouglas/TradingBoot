package com.github.dayviddouglas.TradingBot.strategy;

import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.model.Signal;

import java.util.List;

/**
 * Interface base para todas as estratégias de trading do sistema.
 *
 * Toda estratégia que quiser participar do pipeline de decisão
 * (StrategyEngine) deve implementar esta interface.
 *
 * O contrato é simples e intencional:
 * - name(): identifica a estratégia para logs, metadata, pesos e relatórios
 * - checkSignal(): avalia os candles e retorna um sinal (BUY, SELL ou NONE)
 *
 * Padrão arquitetural: Strategy Pattern
 * Cada implementação encapsula uma lógica de análise técnica diferente,
 * permitindo que o StrategyEngine execute qualquer combinação de
 * estratégias de forma polimórfica, sem conhecer os detalhes de cada uma.
 *
 * Integração com o sistema:
 * 1. Implementar esta interface
 * 2. Adicionar builder no StrategiesConfigLoader
 * 3. Adicionar bloco no strategies.json
 * → A estratégia passa a funcionar em runtime e backtest automaticamente
 *
 * Implementações existentes:
 * - BollingerMeanReversionStrategy (reversão à média via Bollinger)
 * - ZScoreMeanReversionStrategy (reversão via desvio estatístico)
 * - EmaRsiStrategy (tendência via EMA + RSI)
 * - BreakoutStrategy (rompimento simples de níveis)
 * - DonchianBreakoutStrategy (breakout Turtle Traders)
 * - KeltnerChannelStrategy (breakout via canal ATR)
 * - PinBarStrategy (rejeição via padrão de vela)
 * - SupportResistanceStrategy (rejeição em níveis com múltiplos toques)
 *
 * ⚠️ Pontos importantes para implementadores:
 *
 * 1. O retorno de name() DEVE bater com:
 *    - As chaves no StrategyWeightProfile (para ponderação por regime)
 *    - As chaves no AtrRiskManager (para classificação de confluência)
 *    - O nome usado no StrategiesConfigLoader (para construção)
 *    Inconsistência de nomes causa fallback silencioso para peso 1.0 ou
 *    classificação incorreta de confluência.
 *
 * 2. checkSignal() DEVE:
 *    - Retornar Signal.none(name()) quando não há sinal (nunca null)
 *    - Ser puro/determinístico: mesma entrada → mesma saída
 *    - Não manter estado interno entre chamadas
 *    - Validar null e tamanho mínimo dos bars antes de processar
 *    - Incluir metadata no Signal para rastreabilidade
 *
 * 3. Thread-safety:
 *    - checkSignal() pode ser chamado de threads diferentes
 *    - Implementações não devem manter estado mutável entre chamadas
 *    - O uso de List<Bar> como entrada (não array) facilita operações imutáveis
 *
 * 4. Performance:
 *    - checkSignal() roda na thread do WebSocket reader (via StrategyEngine)
 *    - Cálculos pesados podem atrasar o recebimento de novos ticks
 *    - Para estratégias complexas, considere pré-cálculo ou cache de indicadores
 *
 * 💡 Sugestão: Em evolução futura, considere adicionar:
 * - default String family(): para classificar automaticamente como
 *   "REVERSAL" ou "TREND_BREAKOUT" sem depender de Sets externos
 * - default int minBarsRequired(): para que o engine saiba antecipadamente
 *   quantas barras cada estratégia precisa, evitando chamadas desnecessárias
 */
public interface TradingStrategy {

    /**
     * Retorna o nome identificador da estratégia.
     *
     * Este nome é usado em:
     * - Logs operacionais (FINAL SIGNAL | strategy=...)
     * - Metadata do Signal (decisionStrategies)
     * - StrategyWeightProfile (peso por regime)
     * - AtrRiskManager (classificação TREND vs REVERSAL)
     * - TradeReportService (relatório operacional)
     * - BacktestReport (identificação nos resultados)
     *
     * ⚠️ CRÍTICO: O nome retornado deve ser EXATAMENTE o mesmo usado
     * como chave nos mapas de StrategyWeightProfile e nos Sets do
     * AtrRiskManager. Qualquer divergência causa comportamento incorreto
     * silencioso (peso default 1.0 ou classificação errada).
     *
     * @return nome único da estratégia (ex: "BollingerMeanReversion")
     */
    String name();

    /**
     * Avalia os candles e retorna um sinal de trading.
     *
     * Contrato:
     * - bars estão em ordem cronológica ASCENDENTE
     * - bars.get(bars.size()-1) é o candle mais recente
     * - bars.get(0) é o candle mais antigo na janela
     * - O método NUNCA deve retornar null (use Signal.none(name()))
     * - O método deve validar bars != null e tamanho mínimo
     * - O método deve ser determinístico (sem randomização)
     * - O método não deve modificar a lista bars
     *
     * O Signal retornado pode ser:
     * - Signal.buy(...): oportunidade de compra identificada
     * - Signal.sell(...): oportunidade de venda identificada
     * - Signal.none(name()): sem oportunidade clara
     *
     * O StrategyEngine decide o que fazer com o sinal baseado no
     * DecisionMode configurado (SINGLE, VOTING ou CONFLUENCE).
     *
     * @param bars lista de candles em ordem cronológica ascendente.
     *             O último elemento é o candle mais recente.
     *             Pode ser null (deve retornar NONE).
     * @return Signal com tipo (BUY/SELL/NONE), timestamp, preço e metadata
     */
    Signal checkSignal(List<Bar> bars);
}