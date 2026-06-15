package com.github.dayviddouglas.TradingBot.model;

import java.time.Instant;
import java.util.Map;

/**
 * Representa um sinal operacional emitido pelo {@link com.github.dayviddouglas.TradingBot.engine.core.SignalEmitter}.
 *
 * Um sinal encapsula a decisão gerada pelo {@code DecisionEvaluator} após a avaliação
 * das estratégias habilitadas sobre o histórico de candles. É consumido pelo
 * {@link com.github.dayviddouglas.TradingBot.bot.SignalHandler}, que o encaminha ao
 * {@link com.github.dayviddouglas.TradingBot.deriv.DerivTradeService} para execução.
 *
 * Instâncias são construídas exclusivamente pelos factory methods estáticos:
 * {@link #buy}, {@link #sell} e {@link #none}. O construtor privado garante
 * que todos os campos sejam preenchidos de forma consistente.
 *
 * O mapa {@code metadata} é sempre imutável e pode conter campos como:
 * {@code symbol}, {@code decisionMode}, {@code decisionStrategies},
 * {@code regime}, {@code buyScore}, {@code sellScore} e {@code weightedVotes},
 * conforme o modo de decisão ativo.
 */
public class Signal {

    /** Representa a direção do sinal operacional. */
    public enum Type { BUY, SELL, NONE }

    private final Type type;

    /** Nome da estratégia ou mecanismo que gerou o sinal (ex: {@code "WeightedConfluence"}). */
    private final String strategy;

    /** Timestamp do candle que originou o sinal; {@code null} para sinais do tipo {@code NONE}. */
    private final Instant timestamp;

    /** Preço de fechamento do candle que originou o sinal; {@code NaN} para sinais do tipo {@code NONE}. */
    private final double price;

    /** Mapa imutável com dados adicionais para rastreabilidade e relatório da operação. */
    private final Map<String, Object> metadata;

    private Signal(Type type, String strategy, Instant timestamp,
                   double price, Map<String, Object> metadata) {
        this.type      = type;
        this.strategy  = strategy;
        this.timestamp = timestamp;
        this.price     = price;
        this.metadata  = metadata;
    }

    /**
     * Cria um sinal do tipo {@code NONE} indicando ausência de condição operacional.
     *
     * @param strategy nome da estratégia ou mecanismo avaliador
     * @return sinal sem direção, com timestamp e preço indefinidos
     */
    public static Signal none(String strategy) {
        return new Signal(Type.NONE, strategy, null, Double.NaN, Map.of());
    }

    /**
     * Cria um sinal de compra ({@code BUY}).
     *
     * @param strategy  nome da estratégia ou mecanismo avaliador
     * @param timestamp timestamp do candle que originou o sinal
     * @param price     preço de fechamento do candle
     * @param metadata  mapa de metadados; {@code null} é tratado como mapa vazio
     * @return sinal de compra imutável
     */
    public static Signal buy(String strategy, Instant timestamp,
                             double price, Map<String, Object> metadata) {
        return new Signal(Type.BUY, strategy, timestamp, price,
                metadata == null ? Map.of() : Map.copyOf(metadata));
    }

    /**
     * Cria um sinal de venda ({@code SELL}).
     *
     * @param strategy  nome da estratégia ou mecanismo avaliador
     * @param timestamp timestamp do candle que originou o sinal
     * @param price     preço de fechamento do candle
     * @param metadata  mapa de metadados; {@code null} é tratado como mapa vazio
     * @return sinal de venda imutável
     */
    public static Signal sell(String strategy, Instant timestamp,
                              double price, Map<String, Object> metadata) {
        return new Signal(Type.SELL, strategy, timestamp, price,
                metadata == null ? Map.of() : Map.copyOf(metadata));
    }

    public Type getType()                    { return type; }
    public String getStrategy()              { return strategy; }
    public Instant getTimestamp()            { return timestamp; }
    public double getPrice()                 { return price; }
    public Map<String, Object> getMetadata() { return metadata; }

    @Override
    public String toString() {
        return "Signal{" +
                "type=" + type +
                ", strategy='" + strategy + '\'' +
                ", timestamp=" + timestamp +
                ", price=" + price +
                ", metadata=" + metadata +
                '}';
    }
}