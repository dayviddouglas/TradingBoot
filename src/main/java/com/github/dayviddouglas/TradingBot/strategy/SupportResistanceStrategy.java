package com.github.dayviddouglas.TradingBot.strategy;

import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.model.Signal;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Estratégia de rejeição em níveis reais de suporte e resistência identificados por clustering.
 *
 * Diferencia-se da {@link PinBarStrategy} por detectar níveis com múltiplos toques
 * em vez de usar apenas a máxima/mínima do período. Um nível só é considerado válido
 * quando acumula pelo menos {@code minTouches} toques dentro da tolerância de agrupamento.
 *
 * Algoritmo de clustering:
 * <ol>
 *   <li>Itera sobre os candles do {@code lookback} extraindo highs (resistência) ou lows (suporte)</li>
 *   <li>Para cada valor, verifica se existe cluster próximo (dentro de {@code tolerance})</li>
 *   <li>Se encontrado: incorpora ao cluster via média ponderada, refinando o nível</li>
 *   <li>Se não encontrado: cria novo cluster com um toque</li>
 *   <li>Filtra apenas clusters com {@code >= minTouches} toques</li>
 * </ol>
 *
 * Sinal gerado quando o preço está próximo a um nível validado e o candle mostra rejeição:
 * <ul>
 *   <li><b>SELL em resistência</b>: pavio superior maior que o corpo E candle vermelho ({@code close < open})</li>
 *   <li><b>BUY em suporte</b>: pavio inferior maior que o corpo E candle verde ({@code close > open})</li>
 * </ul>
 *
 * A tolerância é calculada como {@code atr14 × toleranceMultiplier}, adaptando
 * automaticamente o agrupamento e a proximidade à volatilidade do ativo.
 */
public class SupportResistanceStrategy implements TradingStrategy {

    /** Período de lookback para buscar e agrupar os níveis de S/R. */
    private final int lookback;

    /**
     * Número mínimo de toques para validar um cluster como nível de S/R real.
     * Fixo em 2: exige ao menos dois toques para que o nível seja considerado significativo.
     */
    private final int minTouches;

    /**
     * Multiplicador aplicado sobre o ATR de 14 períodos para definir a tolerância
     * de agrupamento e de proximidade. Calculado no construtor a partir de {@code tolerancePct}.
     */
    private final double toleranceMultiplier;

    /**
     * @param lookback     período para buscar e agrupar os níveis; mínimo 5
     * @param tolerancePct percentual convertido para multiplicador de ATR via {@code tolerancePct * 1000}
     * @throws IllegalArgumentException se {@code lookback} for menor que 5
     */
    public SupportResistanceStrategy(int lookback, double tolerancePct) {
        if (lookback < 5) throw new IllegalArgumentException("lookback must be >= 5");
        this.lookback            = lookback;
        this.minTouches          = 2;
        this.toleranceMultiplier = Math.max(0.3, tolerancePct * 1000);
    }

    /**
     * Identificador da estratégia utilizado em logs e metadata do sinal.
     */
    @Override
    public String name() {
        return "SupportResistanceStrategy";
    }

    /**
     * Avalia se o preço está rejeitando um nível real de S/R validado por clustering.
     *
     * Fluxo:
     * <ol>
     *   <li>Calcula a tolerância baseada no ATR de 14 períodos</li>
     *   <li>Detecta níveis reais de resistência (clusters de highs com {@code >= minTouches})</li>
     *   <li>Detecta níveis reais de suporte (clusters de lows com {@code >= minTouches})</li>
     *   <li>Verifica proximidade do candle atual a cada nível e padrão de rejeição</li>
     * </ol>
     *
     * @param bars lista de candles para análise
     * @return {@link Signal} com tipo, timestamp, preço e metadata dos indicadores
     */
    @Override
    public Signal checkSignal(List<Bar> bars) {
        if (bars == null || bars.size() < lookback + 1) return Signal.none(name());

        Bar last = bars.get(bars.size() - 1);

        double atr       = calculateATR(bars, 14);
        double tolerance = atr * toleranceMultiplier;

        List<Double> resistanceLevels = findRealLevels(bars, lookback, tolerance, true);
        List<Double> supportLevels    = findRealLevels(bars, lookback, tolerance, false);

        Map<String, Object> meta = new HashMap<>();
        meta.put("lookback",          lookback);
        meta.put("atr",               atr);
        meta.put("tolerance",         tolerance);
        meta.put("resistanceLevels",  resistanceLevels);
        meta.put("supportLevels",     supportLevels);

        // Verifica rejeição em cada nível de resistência validado
        for (Double resistance : resistanceLevels) {
            boolean nearResistance = Math.abs(last.high() - resistance) <= tolerance;

            if (nearResistance && isRejectionCandle(last, true)) {
                meta.put("pattern", "rejection_resistance");
                meta.put("level",   resistance);
                return Signal.sell(name(), last.timestamp(), last.close(), meta);
            }
        }

        // Verifica rejeição em cada nível de suporte validado
        for (Double support : supportLevels) {
            boolean nearSupport = Math.abs(last.low() - support) <= tolerance;

            if (nearSupport && isRejectionCandle(last, false)) {
                meta.put("pattern", "rejection_support");
                meta.put("level",   support);
                return Signal.buy(name(), last.timestamp(), last.close(), meta);
            }
        }

        return Signal.none(name());
    }

    /**
     * Detecta níveis reais de S/R por clustering de toques.
     * Para cada candle no período, extrai o high (resistência) ou low (suporte) e tenta
     * incorporá-lo a um cluster existente dentro da tolerância via média ponderada.
     * Quando nenhum cluster próximo é encontrado, um novo cluster é criado.
     * Ao final, retorna apenas os clusters com {@code >= minTouches} toques, ordenados.
     *
     * A cópia do mapa ({@code new HashMap<>(touchCounts)}) antes da iteração é necessária
     * para permitir remoção e inserção durante o loop sem {@code ConcurrentModificationException}.
     *
     * @param bars         lista de candles
     * @param lookback     período de análise (candle atual excluído)
     * @param tolerance    distância máxima para agrupar dois preços no mesmo cluster
     * @param isResistance {@code true} para agrupar highs; {@code false} para lows
     * @return lista de níveis validados ordenados de forma crescente
     */
    private List<Double> findRealLevels(List<Bar> bars, int lookback,
                                        double tolerance, boolean isResistance) {
        Map<Double, Integer> touchCounts = new HashMap<>();

        int start = Math.max(0, bars.size() - 1 - lookback);
        int end   = bars.size() - 2; // Exclui o candle atual

        for (int i = start; i <= end; i++) {
            double level = isResistance ? bars.get(i).high() : bars.get(i).low();

            boolean foundCluster = false;

            // Cópia para iteração segura enquanto o mapa original é modificado
            for (Map.Entry<Double, Integer> entry : new HashMap<>(touchCounts).entrySet()) {
                if (Math.abs(level - entry.getKey()) <= tolerance) {
                    double oldLevel = entry.getKey();
                    int    oldCount = entry.getValue();

                    // Média ponderada refina o nível para a zona de maior concentração de toques
                    double newLevel = (oldLevel * oldCount + level) / (oldCount + 1);

                    touchCounts.remove(oldLevel);
                    touchCounts.put(newLevel, oldCount + 1);
                    foundCluster = true;
                    break;
                }
            }

            if (!foundCluster) {
                touchCounts.put(level, 1);
            }
        }

        return touchCounts.entrySet().stream()
                .filter(e -> e.getValue() >= minTouches)
                .map(Map.Entry::getKey)
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Verifica se o candle exibe padrão de rejeição direcional.
     *
     * Para resistência: pavio superior maior que o corpo E fechamento abaixo da abertura (candle vermelho),
     * indicando que o preço tentou subir mas foi efetivamente rejeitado com pressão vendedora.
     *
     * Para suporte: pavio inferior maior que o corpo E fechamento acima da abertura (candle verde),
     * indicando que o preço tentou cair mas foi efetivamente rejeitado com pressão compradora.
     *
     * @param bar          candle a ser analisado
     * @param isResistance {@code true} para verificar rejeição de resistência; {@code false} para suporte
     * @return {@code true} se o candle demonstra rejeição válida na direção esperada
     */
    private boolean isRejectionCandle(Bar bar, boolean isResistance) {
        double body      = Math.abs(bar.close() - bar.open());
        double upperWick = bar.high() - Math.max(bar.open(), bar.close());
        double lowerWick = Math.min(bar.open(), bar.close()) - bar.low();

        if (isResistance) {
            // Pavio superior maior que o corpo e candle vermelho confirmam rejeição da resistência
            return upperWick > body && bar.close() < bar.open();
        } else {
            // Pavio inferior maior que o corpo e candle verde confirmam rejeição do suporte
            return lowerWick > body && bar.close() > bar.open();
        }
    }

    /**
     * Calcula o ATR de 14 períodos utilizado no cálculo da tolerância adaptativa.
     * O primeiro candle da janela usa apenas {@code high - low} por não ter candle anterior.
     *
     * @param bars   lista de candles
     * @param period período do ATR
     * @return ATR calculado ou {@code NaN} se dados insuficientes
     */
    private static double calculateATR(List<Bar> bars, int period) {
        if (bars.size() < period) return Double.NaN;

        double sum = 0.0;
        for (int i = bars.size() - period; i < bars.size(); i++) {
            Bar    current = bars.get(i);
            double tr;

            if (i == bars.size() - period) {
                tr = current.high() - current.low();
            } else {
                Bar previous = bars.get(i - 1);
                tr = Math.max(
                        current.high() - current.low(),
                        Math.max(
                                Math.abs(current.high() - previous.close()),
                                Math.abs(current.low()  - previous.close())
                        )
                );
            }

            sum += tr;
        }

        return sum / period;
    }
}