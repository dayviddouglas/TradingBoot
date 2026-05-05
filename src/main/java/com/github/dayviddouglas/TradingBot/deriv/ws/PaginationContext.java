package com.github.dayviddouglas.TradingBot.deriv.ws;

import com.github.dayviddouglas.TradingBot.model.Bar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Contexto de paginação para download de histórico de candles.
 *
 * Mantém o estado de uma requisição de histórico que excedeu o
 * limite de 1000 candles por requisição da API Deriv.
 *
 * Acumula barras de múltiplas páginas e entrega o resultado
 * completo via callback quando a paginação estiver concluída.
 *
 * Os métodos são synchronized para proteger a lista accumulated
 * contra condições de corrida, pois as respostas chegam de forma
 * assíncrona na thread do WebSocket.
 */
public class PaginationContext {

    private final long parentReqId;
    private final String symbol;
    private final int granularitySeconds;
    private final int totalRequested;
    private final BiConsumer<Long, List<Bar>> onComplete;
    private final List<Bar> accumulated = new ArrayList<>();

    public PaginationContext(
            long parentReqId,
            String symbol,
            int granularitySeconds,
            int totalRequested,
            BiConsumer<Long, List<Bar>> onComplete
    ) {
        this.parentReqId = parentReqId;
        this.symbol = symbol;
        this.granularitySeconds = granularitySeconds;
        this.totalRequested = totalRequested;
        this.onComplete = onComplete;
    }

    /**
     * Insere barras no início da lista acumulada.
     *
     * A paginação vai do presente ao passado, então cada nova página
     * contém candles mais antigos que devem ser inseridos no início.
     *
     * @param pageBars barras da página recebida
     */
    public synchronized void prependPage(List<Bar> pageBars) {
        accumulated.addAll(0, pageBars);
    }

    /**
     * Retorna a quantidade de barras acumuladas até o momento.
     *
     * @return tamanho do histórico acumulado
     */
    public synchronized int size() {
        return accumulated.size();
    }

    /**
     * Retorna cópia imutável e ordenada cronologicamente do histórico acumulado.
     *
     * @return lista imutável de barras ordenadas por timestamp
     */
    public synchronized List<Bar> snapshot() {
        List<Bar> sorted = new ArrayList<>(accumulated);
        sorted.sort(Comparator.comparing(Bar::timestamp));
        return List.copyOf(sorted);
    }

    // ═══════════════════════════════════════════════════════════════
    // Getters
    // ═══════════════════════════════════════════════════════════════

    public long getParentReqId() { return parentReqId; }
    public String getSymbol() { return symbol; }
    public int getGranularitySeconds() { return granularitySeconds; }
    public int getTotalRequested() { return totalRequested; }
    public BiConsumer<Long, List<Bar>> getOnComplete() { return onComplete; }
}
