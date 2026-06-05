package com.github.dayviddouglas.TradingBot.engine.core;

import com.github.dayviddouglas.TradingBot.model.Bar;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Gerencia o histórico local de candles do StrategyEngine.
 *
 * Extraído do StrategyEngine para respeitar SRP:
 * - StrategyEngine orquestra a avaliação
 * - BarHistory gerencia o ciclo de vida dos candles
 *
 * Responsabilidades:
 * - Manter os últimos maxBars candles em memória
 * - Aceitar novos candles com deduplicação por timestamp
 * - Rejeitar candles fora de ordem (timestamp anterior ao último)
 * - Fornecer snapshots imutáveis para avaliação das estratégias
 * - Registrar o timestamp do último candle processado
 *
 * Thread-safety: todos os métodos públicos são synchronized.
 * Necessário porque onBar() e seedHistory() podem ser chamados
 * de threads diferentes (WebSocket reader e callback de histórico).
 */
public class BarHistory {

    private final int maxBars;
    private final List<Bar> bars = new ArrayList<>();
    private Instant lastProcessedTimestamp;

    public BarHistory(int maxBars) {
        this.maxBars = Math.max(50, maxBars);
    }

    /**
     * Inicializa o histórico com candles carregados da API.
     * Substitui completamente o histórico existente.
     *
     * @param history lista de candles históricos (pode ser null)
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
     * Comportamentos:
     * - Mesmo timestamp: atualiza o candle em formação (OHLC parcial)
     * - Timestamp posterior: adiciona como novo candle fechado
     * - Timestamp anterior: rejeita (fora de ordem)
     *
     * @param bar candle a ser processado
     * @return true se o candle foi aceito e deve disparar avaliação
     */
    public synchronized boolean accept(Bar bar) {
        if (bar == null) return false;

        if (bars.isEmpty()) {
            bars.add(bar);
            return false;
        }

        Bar last = bars.get(bars.size() - 1);

        if (bar.timestamp().equals(last.timestamp())) {
            bars.set(bars.size() - 1, bar);
            trim();
            return false;
        }

        if (bar.timestamp().isAfter(last.timestamp())) {
            bars.add(bar);
            trim();
            return true;
        }

        return false;
    }

    /**
     * Verifica se o candle já foi processado (mesmo timestamp).
     *
     * Evita reprocessamento do mesmo candle quando recebido
     * múltiplas vezes pelo TickCandleAggregator.
     *
     * @param bar candle a verificar
     * @return true se o timestamp já foi processado
     */
    public synchronized boolean alreadyProcessed(Bar bar) {
        if (bar == null || lastProcessedTimestamp == null) return false;
        return bar.timestamp().equals(lastProcessedTimestamp);
    }

    /**
     * Marca o timestamp do candle como processado.
     *
     * @param bar candle que foi processado
     */
    public synchronized void markProcessed(Bar bar) {
        if (bar != null) {
            lastProcessedTimestamp = bar.timestamp();
        }
    }

    /**
     * Retorna cópia imutável do histórico atual.
     * Usada pelas estratégias como janela de análise.
     *
     * @return lista imutável de barras
     */
    public synchronized List<Bar> snapshot() {
        return List.copyOf(bars);
    }

    /**
     * Retorna a quantidade atual de candles no histórico.
     *
     * @return tamanho do histórico
     */
    public synchronized int size() {
        return bars.size();
    }

    /**
     * Verifica se o histórico está vazio.
     *
     * @return true se não há candles
     */
    public synchronized boolean isEmpty() {
        return bars.isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════
    // Internos
    // ═══════════════════════════════════════════════════════════════

    private void trim() {
        while (bars.size() > maxBars) {
            bars.remove(0);
        }
    }

    private void updateLastProcessedTimestamp() {
        if (!bars.isEmpty()) {
            lastProcessedTimestamp = bars.get(bars.size() - 1).timestamp();
        }
    }
}
