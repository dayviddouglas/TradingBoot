package com.github.dayviddouglas.TradingBot.engine.core;

import com.github.dayviddouglas.TradingBot.model.Bar;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Gerencia o histórico local de candles utilizado pelo {@link StrategyEngine}.
 *
 * Mantém em memória os últimos {@code maxBars} candles, aceitando novos candles
 * com deduplicação por timestamp e rejeitando candles fora de ordem.
 * Fornece snapshots imutáveis para avaliação das estratégias e controla
 * o timestamp do último candle processado para evitar reprocessamentos.
 *
 * Todos os métodos públicos são {@code synchronized} pois {@code onBar()} e
 * {@code seedHistory()} podem ser invocados de threads diferentes: a thread
 * leitora do WebSocket e o callback de recebimento do histórico inicial.
 */
public class BarHistory {

    /** Limite máximo de candles mantidos em memória; nunca inferior a 50. */
    private final int maxBars;
    private final List<Bar> bars = new ArrayList<>();
    private Instant lastProcessedTimestamp;

    /**
     * @param maxBars número máximo de candles a manter em memória; valor mínimo efetivo é 50
     */
    public BarHistory(int maxBars) {
        this.maxBars = Math.max(50, maxBars);
    }

    /**
     * Inicializa o histórico com candles carregados da API, substituindo completamente
     * o conteúdo anterior. Aplica o limite de {@code maxBars} e registra o timestamp
     * do candle mais recente como último processado.
     *
     * @param history lista de candles históricos; aceita {@code null}, tratado como lista vazia
     */
    public synchronized void seed(List<Bar> history) {
        bars.clear();

        if (history != null) {
            bars.addAll(history);
        }

        trim();
        updateLastProcessedTimestamp();
    }

    /**
     * Tenta adicionar ou atualizar um candle no histórico.
     *
     * Comportamentos por timestamp:
     * <ul>
     *   <li>Igual ao último: atualiza o candle em formação com os dados OHLC parciais mais recentes;
     *       retorna {@code false} pois não há novo candle fechado</li>
     *   <li>Posterior ao último: adiciona como novo candle fechado;
     *       retorna {@code true} para disparar a avaliação das estratégias</li>
     *   <li>Anterior ao último: rejeita por estar fora de ordem; retorna {@code false}</li>
     *   <li>Primeiro candle: adiciona sem disparar avaliação; retorna {@code false}</li>
     * </ul>
     *
     * @param bar candle a ser processado
     * @return {@code true} se um novo candle fechado foi adicionado e deve disparar avaliação
     */
    public synchronized boolean accept(Bar bar) {
        if (bar == null) return false;

        if (bars.isEmpty()) {
            bars.add(bar);
            return false;
        }

        Bar last = bars.get(bars.size() - 1);

        if (bar.timestamp().equals(last.timestamp())) {
            // Atualiza candle em formação sem disparar avaliação
            bars.set(bars.size() - 1, bar);
            trim();
            return false;
        }

        if (bar.timestamp().isAfter(last.timestamp())) {
            bars.add(bar);
            trim();
            return true;
        }

        // Candle fora de ordem — descartado silenciosamente
        return false;
    }

    /**
     * Verifica se o candle já foi processado com base no timestamp.
     * Evita reprocessamento do mesmo candle quando recebido múltiplas vezes
     * pelo {@link com.github.dayviddouglas.TradingBot.market.TickCandleAggregator}.
     *
     * @param bar candle a verificar
     * @return {@code true} se o timestamp do candle já foi processado
     */
    public synchronized boolean alreadyProcessed(Bar bar) {
        if (bar == null || lastProcessedTimestamp == null) return false;
        return bar.timestamp().equals(lastProcessedTimestamp);
    }

    /**
     * Registra o timestamp do candle como o último processado,
     * atualizando a referência usada por {@link #alreadyProcessed}.
     *
     * @param bar candle que foi processado
     */
    public synchronized void markProcessed(Bar bar) {
        if (bar != null) {
            lastProcessedTimestamp = bar.timestamp();
        }
    }

    /**
     * Retorna cópia imutável do histórico atual para uso como janela de análise
     * pelas estratégias e pelo classificador de regime.
     *
     * @return lista imutável com os candles atuais em ordem cronológica
     */
    public synchronized List<Bar> snapshot() {
        return List.copyOf(bars);
    }

    /**
     * Retorna a quantidade atual de candles no histórico.
     *
     * @return número de candles armazenados
     */
    public synchronized int size() {
        return bars.size();
    }

    /**
     * Verifica se o histórico está vazio.
     *
     * @return {@code true} se não há candles armazenados
     */
    public synchronized boolean isEmpty() {
        return bars.isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════
    // Internos
    // ═══════════════════════════════════════════════════════════════

    /**
     * Remove os candles mais antigos até que o tamanho não exceda {@code maxBars}.
     */
    private void trim() {
        while (bars.size() > maxBars) {
            bars.remove(0);
        }
    }

    /**
     * Atualiza o timestamp do último processado com o candle mais recente do histórico.
     * Chamado após {@link #seed} para sincronizar a referência com o histórico carregado.
     */
    private void updateLastProcessedTimestamp() {
        if (!bars.isEmpty()) {
            lastProcessedTimestamp = bars.get(bars.size() - 1).timestamp();
        }
    }
}