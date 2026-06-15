package com.github.dayviddouglas.TradingBot.strategy;

import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.model.Signal;

import java.util.List;

/**
 * Contrato que todas as estratégias de trading do sistema devem implementar
 * para participar do pipeline de decisão do
 * {@link com.github.dayviddouglas.TradingBot.engine.core.StrategyEngine}.
 *
 * Cada implementação encapsula uma lógica de análise técnica distinta, permitindo
 * que o engine execute qualquer combinação de estratégias de forma polimórfica,
 * sem conhecer os detalhes de cada uma. O modo de decisão ({@code SINGLE_STRATEGY},
 * {@code VOTING} ou {@code CONFLUENCE}) determina como os sinais individuais
 * são combinados para produzir o sinal final.
 *
 * Para adicionar uma nova estratégia ao sistema:
 * <ol>
 *   <li>Implementar esta interface</li>
 *   <li>Adicionar o método {@code buildXxx()} no
 *       {@link com.github.dayviddouglas.TradingBot.config.strategy.StrategyBuilder}</li>
 *   <li>Adicionar bloco correspondente no strategies.json</li>
 *   <li>Registrar a chave no
 *       {@link com.github.dayviddouglas.TradingBot.engine.confluence.StrategyWeightProfile}
 *       para os três regimes</li>
 * </ol>
 *
 * <b>Contrato de {@link #name()}</b>:
 * O valor retornado deve corresponder exatamente às chaves utilizadas no
 * {@link com.github.dayviddouglas.TradingBot.engine.confluence.StrategyWeightProfile}
 * e nos conjuntos do {@link com.github.dayviddouglas.TradingBot.risk.AtrRiskManager}.
 * Divergência resulta em fallback silencioso para peso {@code 1.0} ou classificação
 * incorreta de confluência.
 *
 * <b>Contrato de {@link #checkSignal(List)}</b>:
 * <ul>
 *   <li>Nunca retornar {@code null} — usar {@code Signal.none(name())}</li>
 *   <li>Ser determinístico: mesma entrada produz sempre o mesmo resultado</li>
 *   <li>Não manter estado mutável entre chamadas</li>
 *   <li>Validar {@code null} e tamanho mínimo dos candles antes de processar</li>
 *   <li>Não modificar a lista de candles recebida</li>
 * </ul>
 *
 * Implementações existentes:
 * <ul>
 *   <li>{@link BollingerMeanReversionStrategy} — reversão via Bandas de Bollinger com RSI</li>
 *   <li>{@link ZScoreMeanReversionStrategy} — reversão via desvio padrão populacional</li>
 *   <li>{@link EmaRsiStrategy} — tendência via cruzamento de EMAs com RSI</li>
 *   <li>{@link BreakoutStrategy} — rompimento de máximas e mínimas históricas</li>
 *   <li>{@link DonchianBreakoutStrategy} — breakout via Canal de Donchian</li>
 *   <li>{@link KeltnerChannelStrategy} — breakout via Canal de Keltner</li>
 *   <li>{@link PinBarStrategy} — rejeição via padrão de vela próxima a S/R</li>
 *   <li>{@link SupportResistanceStrategy} — rejeição em níveis com múltiplos toques</li>
 * </ul>
 */
public interface TradingStrategy {

    /**
     * Retorna o identificador único da estratégia.
     *
     * Utilizado em logs operacionais, metadata do {@link Signal},
     * {@link com.github.dayviddouglas.TradingBot.engine.confluence.StrategyWeightProfile}
     * (ponderação por regime), {@link com.github.dayviddouglas.TradingBot.risk.AtrRiskManager}
     * (classificação TREND vs REVERSAL), relatórios operacionais e resultados de backtest.
     *
     * @return nome único da estratégia (ex: {@code "BollingerMeanReversionStrategy"})
     */
    String name();

    /**
     * Avalia a lista de candles e retorna o sinal operacional identificado.
     *
     * Os candles estão em ordem cronológica ascendente: {@code bars.get(0)} é o mais antigo
     * e {@code bars.get(bars.size()-1)} é o mais recente. O método nunca deve modificar a lista.
     *
     * @param bars lista de candles em ordem cronológica ascendente;
     *             pode ser {@code null} (deve retornar {@code NONE})
     * @return {@link Signal} com tipo ({@code BUY}, {@code SELL} ou {@code NONE}),
     *         timestamp e preço do candle mais recente, e metadata dos indicadores;
     *         nunca {@code null}
     */
    Signal checkSignal(List<Bar> bars);
}