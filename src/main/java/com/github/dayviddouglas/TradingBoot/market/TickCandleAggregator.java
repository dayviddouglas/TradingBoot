package com.github.dayviddouglas.TradingBoot.market;

import com.github.dayviddouglas.TradingBoot.model.Bar;

import java.time.Instant;
import java.util.function.Consumer;

/**
 * Agrega ticks recebidos em tempo real em candles OHLC para uma granularidade fixa em segundos.
 *
 * Cada tick é associado a um bucket temporal calculado como:
 * {@code (epochSec / granularitySeconds) * granularitySeconds}.
 * Quando o bucket do tick recebido é posterior ao bucket atual, o candle em formação é
 * considerado fechado, emitido via {@code onCandleClosed} e um novo candle é iniciado.
 * Ticks com bucket anterior ao atual são ignorados silenciosamente.
 *
 * Regras de agregação:
 * <ul>
 *   <li>Primeiro tick: inicia o primeiro candle com {@code O=H=L=C=quote}</li>
 *   <li>Tick no mesmo bucket: atualiza {@code close}, {@code high} e {@code low}</li>
 *   <li>Tick em bucket posterior: fecha o candle atual, emite e inicia novo candle</li>
 *   <li>Tick em bucket anterior: ignorado (tick fora de ordem)</li>
 * </ul>
 *
 * O campo {@code volume} é sempre {@code 0.0} pois a API da Deriv não fornece
 * volume real para forex e metais.
 */
public class TickCandleAggregator {

    private final int granularitySeconds;

    /** Callback invocado com o candle fechado quando um novo bucket é detectado. */
    private final Consumer<Bar> onCandleClosed;

    /** Epoch do início do bucket atual; {@code -1} indica que nenhum candle foi iniciado. */
    private long currentBucketStartEpoch = -1;

    private double open;
    private double high;
    private double low;
    private double close;

    /** Sempre zero: a API da Deriv não fornece volume real para forex e metais. */
    private double volume;

    /**
     * @param granularitySeconds duração de cada candle em segundos; deve ser positivo
     * @param onCandleClosed     callback invocado com cada candle fechado
     * @throws IllegalArgumentException se {@code granularitySeconds} for menor ou igual a zero
     */
    public TickCandleAggregator(int granularitySeconds, Consumer<Bar> onCandleClosed) {
        if (granularitySeconds <= 0)
            throw new IllegalArgumentException("granularitySeconds must be > 0");
        this.granularitySeconds = granularitySeconds;
        this.onCandleClosed = onCandleClosed;
    }

    /**
     * Processa um tick recebido, atualizando o candle em formação ou fechando-o
     * quando o bucket temporal avança.
     *
     * @param tickEpochSeconds timestamp do tick em epoch seconds (UTC)
     * @param quote            cotação do ativo no momento do tick
     */
    public void onTick(long tickEpochSeconds, double quote) {
        long bucketStart = (tickEpochSeconds / granularitySeconds) * granularitySeconds;

        if (currentBucketStartEpoch < 0) {
            // Primeiro tick recebido — inicia o primeiro candle
            startNew(bucketStart, quote);
            return;
        }

        if (bucketStart == currentBucketStartEpoch) {
            // Tick dentro do bucket atual — atualiza o candle em formação
            close = quote;
            if (quote > high) high = quote;
            if (quote < low)  low  = quote;
            return;
        }

        if (bucketStart > currentBucketStartEpoch) {
            // Novo bucket detectado — fecha o candle atual e emite
            Bar closed = new Bar(
                    Instant.ofEpochSecond(currentBucketStartEpoch),
                    open, high, low, close, volume
            );
            if (onCandleClosed != null) onCandleClosed.accept(closed);

            // Inicia novo candle com o tick atual
            startNew(bucketStart, quote);
        }

        // Tick com bucket anterior ao atual: ignorado silenciosamente (fora de ordem)
    }

    /**
     * Inicializa um novo candle com o tick informado, definindo todos os campos OHLC
     * com a cotação recebida e resetando o volume para zero.
     *
     * @param bucketStart epoch do início do novo bucket
     * @param quote       cotação do tick que inicia o candle
     */
    private void startNew(long bucketStart, double quote) {
        currentBucketStartEpoch = bucketStart;
        open   = quote;
        high   = quote;
        low    = quote;
        close  = quote;
        volume = 0.0;
    }
}