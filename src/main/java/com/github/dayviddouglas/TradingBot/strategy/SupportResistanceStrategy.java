package com.github.dayviddouglas.TradingBot.strategy;

import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.model.Signal;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Estratégia de rejeição em níveis reais de Suporte e Resistência.
 *
 * Implementa TradingStrategy para uso pelo StrategyEngine.
 *
 * Diferencial em relação à PinBarStrategy:
 * - PinBar usa máxima/mínima simples como S/R
 * - Esta estratégia detecta NÍVEIS REAIS com múltiplos toques (clusters)
 * - Um nível só é válido se teve pelo menos minTouches toques no período
 *
 * Lógica de detecção de níveis:
 * 1. Itera sobre os candles do período de lookback
 * 2. Para cada high/low, verifica se existe um cluster próximo (dentro de tolerance)
 * 3. Se sim, incorpora ao cluster existente (média ponderada)
 * 4. Se não, cria novo cluster
 * 5. Filtra apenas clusters com minTouches ou mais
 *
 * Lógica de sinal:
 * - Se o preço está próximo a um nível de resistência E o candle mostra
 *   rejeição (pavio superior > corpo, candle vermelho) → SELL
 * - Se o preço está próximo a um nível de suporte E o candle mostra
 *   rejeição (pavio inferior > corpo, candle verde) → BUY
 *
 * Versão corrigida:
 * - Detecta níveis reais com múltiplos toques (não mais apenas máxima/mínima)
 * - Tolerância baseada em ATR (não hardcoded)
 * - Confirmação melhorada: exige pavio + cor do candle
 *
 * Classificada como reversão/reação pelo projeto.
 *
 * Status: não se destacou nos testes recentes.
 *
 * ⚠️ Ponto de atenção: O mesmo bug de conversão de tolerancePct existe aqui
 * (tolerancePct * 1000). Veja nota na PinBarStrategy.
 *
 * ⚠️ Ponto de atenção: O algoritmo de clustering usa HashMap com iteração
 * e remoção durante a construção de clusters. A criação de new HashMap<>(touchCounts)
 * para iteração segura é correta mas tem custo O(n) por candle analisado.
 * Para lookbacks grandes, considere otimização.
 */
public class SupportResistanceStrategy implements TradingStrategy {

    /** Período de lookback para buscar níveis S/R */
    private final int lookback;

    /**
     * Número mínimo de toques para validar um nível como S/R real.
     * Fixo em 2: precisa de pelo menos 2 toques para ser considerado nível.
     * Valores maiores = níveis mais fortes, mas menos frequentes.
     */
    private final int minTouches;

    /**
     * Multiplicador do ATR para tolerância de agrupamento e proximidade.
     *
     * ⚠️ Mesmo bug de conversão da PinBarStrategy.
     */
    private final double toleranceMultiplier;

    /**
     * Construtor com compatibilidade de assinatura original.
     *
     * @param lookback período para buscar S/R (mínimo 5)
     * @param tolerancePct percentual convertido para multiplicador ATR
     */
    public SupportResistanceStrategy(int lookback, double tolerancePct) {
        if (lookback < 5) throw new IllegalArgumentException("lookback must be >= 5");
        this.lookback = lookback;
        this.minTouches = 2;
        this.toleranceMultiplier = Math.max(0.3, tolerancePct * 1000);
    }

    @Override
    public String name() {
        return "SupportResistanceStrategy";
    }

    /**
     * Avalia se o preço está rejeitando um nível real de S/R.
     *
     * Fluxo:
     * 1. Calcula tolerância baseada em ATR
     * 2. Detecta níveis reais de resistência (clusters de highs)
     * 3. Detecta níveis reais de suporte (clusters de lows)
     * 4. Verifica proximidade + candle de rejeição
     * 5. Se rejeição em resistência → SELL
     * 6. Se rejeição em suporte → BUY
     *
     * @param bars lista de candles para análise
     * @return Signal (BUY, SELL ou NONE) com metadata
     */
    @Override
    public Signal checkSignal(List<Bar> bars) {
        if (bars == null || bars.size() < lookback + 1) return Signal.none(name());

        Bar last = bars.get(bars.size() - 1);

        // Tolerância adaptativa baseada em ATR do ativo
        double atr = calculateATR(bars, 14);
        double tolerance = atr * toleranceMultiplier;

        // ── Detecção de níveis reais ──
        // Busca clusters de toques que validam níveis de S/R
        List<Double> resistanceLevels = findRealLevels(bars, lookback, tolerance, true);
        List<Double> supportLevels = findRealLevels(bars, lookback, tolerance, false);

        Map<String, Object> meta = new HashMap<>();
        meta.put("lookback", lookback);
        meta.put("atr", atr);
        meta.put("tolerance", tolerance);
        meta.put("resistanceLevels", resistanceLevels);
        meta.put("supportLevels", supportLevels);

        // ── Verificação de rejeição em resistência ──
        for (Double resistance : resistanceLevels) {
            boolean nearResistance = Math.abs(last.high() - resistance) <= tolerance;

            // Proximidade + candle de rejeição = sinal de SELL
            if (nearResistance && isRejectionCandle(last, true)) {
                meta.put("pattern", "rejection_resistance");
                meta.put("level", resistance);
                return Signal.sell(name(), last.timestamp(), last.close(), meta);
            }
        }

        // ── Verificação de rejeição em suporte ──
        for (Double support : supportLevels) {
            boolean nearSupport = Math.abs(last.low() - support) <= tolerance;

            if (nearSupport && isRejectionCandle(last, false)) {
                meta.put("pattern", "rejection_support");
                meta.put("level", support);
                return Signal.buy(name(), last.timestamp(), last.close(), meta);
            }
        }

        return Signal.none(name());
    }

    /**
     * Detecta níveis reais de S/R via clustering de toques.
     *
     * Algoritmo:
     * 1. Para cada candle no período, extrai high (resistência) ou low (suporte)
     * 2. Verifica se existe um cluster próximo (dentro de tolerance)
     * 3. Se sim: incorpora ao cluster (média ponderada do nível)
     * 4. Se não: cria novo cluster
     * 5. Filtra clusters com >= minTouches toques
     *
     * A média ponderada garante que o nível "migra" para a zona de
     * maior concentração de toques, refinando a precisão do nível.
     *
     * ⚠️ Ponto de atenção: A iteração sobre HashMap com remoção e inserção
     * requer cópia do map (new HashMap<>(touchCounts)). Sem a cópia,
     * ConcurrentModificationException seria lançada.
     *
     * @param bars lista de candles
     * @param lookback período de análise
     * @param tolerance distância máxima para agrupar toques no mesmo cluster
     * @param isResistance true para buscar highs, false para buscar lows
     * @return lista de níveis validados (com minTouches ou mais), ordenada
     */
    private List<Double> findRealLevels(List<Bar> bars, int lookback, double tolerance, boolean isResistance) {
        // Map: nível (média do cluster) → número de toques
        Map<Double, Integer> touchCounts = new HashMap<>();

        int start = Math.max(0, bars.size() - 1 - lookback);
        int end = bars.size() - 2; // Exclui candle atual

        for (int i = start; i <= end; i++) {
            double level = isResistance ? bars.get(i).high() : bars.get(i).low();

            boolean foundCluster = false;
            // Cópia para iteração segura (o map é modificado dentro do loop)
            for (Map.Entry<Double, Integer> entry : new HashMap<>(touchCounts).entrySet()) {
                if (Math.abs(level - entry.getKey()) <= tolerance) {
                    // Cluster encontrado: atualiza com média ponderada
                    double oldLevel = entry.getKey();
                    int oldCount = entry.getValue();
                    // A média ponderada refina o nível para a zona mais "tocada"
                    double newLevel = (oldLevel * oldCount + level) / (oldCount + 1);

                    touchCounts.remove(oldLevel);
                    touchCounts.put(newLevel, oldCount + 1);
                    foundCluster = true;
                    break;
                }
            }

            // Nenhum cluster próximo: cria novo
            if (!foundCluster) {
                touchCounts.put(level, 1);
            }
        }

        // Retorna apenas níveis validados (com toques suficientes)
        return touchCounts.entrySet().stream()
                .filter(e -> e.getValue() >= minTouches)
                .map(Map.Entry::getKey)
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Verifica se o candle mostra padrão de rejeição.
     *
     * Para resistência:
     * - Pavio superior > corpo (tentou subir mas falhou)
     * - Candle vermelho (close < open): pressão vendedora venceu
     *
     * Para suporte:
     * - Pavio inferior > corpo (tentou cair mas falhou)
     * - Candle verde (close > open): pressão compradora venceu
     *
     * A combinação de pavio + cor confirma que o nível foi
     * efetivamente rejeitado, não apenas "tocado".
     *
     * @param bar candle a ser analisado
     * @param isResistance true para verificar rejeição de resistência
     * @return true se o candle mostra rejeição válida
     */
    private boolean isRejectionCandle(Bar bar, boolean isResistance) {
        double body = Math.abs(bar.close() - bar.open());
        double upperWick = bar.high() - Math.max(bar.open(), bar.close());
        double lowerWick = Math.min(bar.open(), bar.close()) - bar.low();

        if (isResistance) {
            // Rejeição de resistência: pavio superior > corpo E candle vermelho
            return upperWick > body && bar.close() < bar.open();
        } else {
            // Rejeição de suporte: pavio inferior > corpo E candle verde
            return lowerWick > body && bar.close() > bar.open();
        }
    }

    /**
     * Calcula o ATR usado para tolerância adaptativa.
     */
    private static double calculateATR(List<Bar> bars, int period) {
        if (bars.size() < period) return Double.NaN;

        double sum = 0.0;
        for (int i = bars.size() - period; i < bars.size(); i++) {
            Bar current = bars.get(i);
            double tr;

            if (i == bars.size() - period) {
                tr = current.high() - current.low();
            } else {
                Bar previous = bars.get(i - 1);
                tr = Math.max(
                        current.high() - current.low(),
                        Math.max(
                                Math.abs(current.high() - previous.close()),
                                Math.abs(current.low() - previous.close())
                        )
                );
            }

            sum += tr;
        }

        return sum / period;
    }
}