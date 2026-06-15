package com.github.dayviddouglas.TradingBot.deriv.ws;

import com.github.dayviddouglas.TradingBot.model.Bar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Acumulador de estado de uma requisição de histórico de candles que excedeu
 * o limite por página da API Deriv e requer múltiplas requisições paginadas.
 *
 * Mantém as barras recebidas de cada página em uma lista interna compartilhada,
 * protegida por sincronização, pois as respostas chegam de forma assíncrona
 * na thread do WebSocket. A paginação navega do presente ao passado, portanto
 * cada nova página contém candles mais antigos que são inseridos no início
 * da lista via {@link #prependPage}.
 *
 * Ao concluir todas as páginas, o {@link DerivHistoryPaginator} invoca
 * {@link #snapshot} para obter a lista ordenada cronologicamente e a entrega
 * ao callback {@code onComplete} registrado pelo chamador original.
 */
public class PaginationContext {

    private final long parentReqId;
    private final String symbol;
    private final int granularitySeconds;

    /** Quantidade total de candles solicitados pelo chamador original. */
    private final int totalRequested;

    /** Callback invocado com o ID pai e a lista completa ao concluir todas as páginas. */
    private final BiConsumer<Long, List<Bar>> onComplete;

    /** Lista mutável que acumula as barras de todas as páginas recebidas. */
    private final List<Bar> accumulated = new ArrayList<>();

    /**
     * @param parentReqId        ID pai da requisição de paginação
     * @param symbol             símbolo do ativo cujo histórico está sendo baixado
     * @param granularitySeconds granularidade dos candles em segundos
     * @param totalRequested     quantidade total de candles desejada pelo chamador
     * @param onComplete         callback invocado ao finalizar todas as páginas
     */
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
     * Insere as barras da página recebida no início da lista acumulada.
     * Como a paginação navega do presente ao passado, cada nova página contém
     * candles mais antigos que devem preceder os já acumulados.
     *
     * @param pageBars candles recebidos na página atual
     */
    public synchronized void prependPage(List<Bar> pageBars) {
        accumulated.addAll(0, pageBars);
    }

    /**
     * Retorna a quantidade de candles acumulados até o momento.
     * Utilizado pelo {@link DerivHistoryPaginator} para calcular quantos
     * candles ainda precisam ser solicitados.
     *
     * @return total de candles acumulados
     */
    public synchronized int size() {
        return accumulated.size();
    }

    /**
     * Retorna uma cópia imutável dos candles acumulados, ordenada cronologicamente
     * por timestamp. Chamado pelo {@link DerivHistoryPaginator} ao finalizar a paginação
     * ou ao entregar resultado parcial em caso de erro.
     *
     * @return lista imutável de candles ordenados do mais antigo ao mais recente
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