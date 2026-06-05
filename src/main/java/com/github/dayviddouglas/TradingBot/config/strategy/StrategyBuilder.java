package com.github.dayviddouglas.TradingBot.config.strategy;

import com.github.dayviddouglas.TradingBot.strategy.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Responsável por construir instâncias de TradingStrategy
 * a partir da configuração do strategies.json.
 *
 * Extraído do StrategiesConfigLoader para respeitar SRP:
 * - StrategiesConfigLoader carrega e valida os profiles
 * - StrategyBuilder constrói as instâncias de estratégia
 *
 * Para adicionar nova estratégia:
 * 1. Criar classe que implementa TradingStrategy
 * 2. Adicionar método buildXxx() aqui
 * 3. Chamar esse método em build()
 * 4. Adicionar bloco no strategies.json
 */
@Component
public class StrategyBuilder {

    /**
     * Constrói a lista de estratégias habilitadas para o profile.
     *
     * Apenas estratégias com "enabled: true" são instanciadas.
     * A ordem de construção define a ordem de avaliação no engine.
     *
     * @param strategies mapa de configurações por estratégia
     * @return lista de estratégias habilitadas e configuradas
     */
    public List<TradingStrategy> build(Map<String, Map<String, Object>> strategies) {
        List<TradingStrategy> list = new ArrayList<>();

        if (strategies == null) return list;

        buildEmaRsi(strategies, list);
        buildSupportResistance(strategies, list);
        buildPinBar(strategies, list);
        buildBreakout(strategies, list);
        buildBollinger(strategies, list);
        buildZScoreMeanReversion(strategies, list);
        buildKeltner(strategies, list);
        buildDonchian1(strategies, list);
        buildDonchian2(strategies, list);

        return list;
    }

    // ═══════════════════════════════════════════════════════════════
    // Builders individuais
    // ═══════════════════════════════════════════════════════════════

    private void buildEmaRsi(
            Map<String, Map<String, Object>> strategies,
            List<TradingStrategy> list
    ) {
        Map<String, Object> cfg = strategies.get("emaRsi");
        if (!isEnabled(cfg)) return;

        list.add(new EmaRsiStrategy(
                getInt(cfg, "emaFast", 50),
                getInt(cfg, "emaSlow", 200),
                getInt(cfg, "rsiPeriod", 14),
                getDouble(cfg, "rsiBuyThreshold", 65.0),
                getDouble(cfg, "rsiSellThreshold", 35.0)
        ));
    }

    private void buildSupportResistance(
            Map<String, Map<String, Object>> strategies,
            List<TradingStrategy> list
    ) {
        Map<String, Object> cfg = strategies.get("supportResistance");
        if (!isEnabled(cfg)) return;

        list.add(new SupportResistanceStrategy(
                getInt(cfg, "lookback", 480),
                getDouble(cfg, "tolerancePct", 0.5)
        ));
    }

    private void buildPinBar(
            Map<String, Map<String, Object>> strategies,
            List<TradingStrategy> list
    ) {
        Map<String, Object> cfg = strategies.get("pinBar");
        if (!isEnabled(cfg)) return;

        list.add(new PinBarStrategy(
                getDouble(cfg, "wickToBodyRatio", 3.5),
                getDouble(cfg, "maxOppositeWickToBody", 0.15),
                getInt(cfg, "srLookback", 480),
                getDouble(cfg, "tolerancePct", 0.5)
        ));
    }

    private void buildBreakout(
            Map<String, Map<String, Object>> strategies,
            List<TradingStrategy> list
    ) {
        Map<String, Object> cfg = strategies.get("breakout");
        if (!isEnabled(cfg)) return;

        list.add(new BreakoutStrategy(
                getInt(cfg, "lookback", 180),
                getDouble(cfg, "bufferPct", 0.0002)
        ));
    }

    private void buildBollinger(
            Map<String, Map<String, Object>> strategies,
            List<TradingStrategy> list
    ) {
        Map<String, Object> cfg = strategies.get("bollingerMeanReversion");
        if (!isEnabled(cfg)) return;

        list.add(new BollingerMeanReversionStrategy(
                getInt(cfg, "period", 20),
                getDouble(cfg, "stdDevMultiplier", 2.0),
                getDouble(cfg, "entryThreshold", 0.98),
                getBoolean(cfg, "useRsiConfirmation", true),
                getInt(cfg, "rsiPeriod", 14),
                getDouble(cfg, "rsiOverbought", 70.0),
                getDouble(cfg, "rsiOversold", 30.0)
        ));
    }

    private void buildZScoreMeanReversion(
            Map<String, Map<String, Object>> strategies,
            List<TradingStrategy> list
    ) {
        Map<String, Object> cfg = strategies.get("zScoreMeanReversion");
        if (!isEnabled(cfg)) return;

        list.add(new ZScoreMeanReversionStrategy(
                getInt(cfg, "period", 20),
                getDouble(cfg, "entryZScore", 2.2)
        ));
    }

    private void buildKeltner(
            Map<String, Map<String, Object>> strategies,
            List<TradingStrategy> list
    ) {
        Map<String, Object> cfg = strategies.get("keltnerChannel");
        if (!isEnabled(cfg)) return;

        list.add(new KeltnerChannelStrategy(
                getInt(cfg, "emaPeriod", 20),
                getInt(cfg, "atrPeriod", 14),
                getDouble(cfg, "atrMultiplier", 2.5),
                getBoolean(cfg, "requireStrongCandle", true)
        ));
    }

    private void buildDonchian1(
            Map<String, Map<String, Object>> strategies,
            List<TradingStrategy> list
    ) {
        Map<String, Object> cfg = strategies.get("donchianSystem1");
        if (!isEnabled(cfg)) return;

        list.add(new DonchianBreakoutStrategy(
                getInt(cfg, "entryPeriod", 20),
                getInt(cfg, "exitPeriod", 10),
                getBoolean(cfg, "useATRFilter", true),
                getInt(cfg, "atrPeriod", 14),
                getDouble(cfg, "minATRExpansion", 0.05)
        ));
    }

    private void buildDonchian2(
            Map<String, Map<String, Object>> strategies,
            List<TradingStrategy> list
    ) {
        Map<String, Object> cfg = strategies.get("donchianSystem2");
        if (!isEnabled(cfg)) return;

        list.add(new DonchianBreakoutStrategy(
                getInt(cfg, "entryPeriod", 55),
                getInt(cfg, "exitPeriod", 20),
                getBoolean(cfg, "useATRFilter", true),
                getInt(cfg, "atrPeriod", 14),
                getDouble(cfg, "minATRExpansion", 0.10)
        ));
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers de leitura de configuração
    // ═══════════════════════════════════════════════════════════════

    private boolean isEnabled(Map<String, Object> cfg) {
        if (cfg == null) return false;
        Object enabled = cfg.get("enabled");
        if (enabled instanceof Boolean bool) return bool;
        return enabled != null && "true".equalsIgnoreCase(enabled.toString());
    }

    private int getInt(Map<String, Object> cfg, String key, int defaultValue) {
        if (cfg == null) return defaultValue;
        Object value = cfg.get(key);
        if (value instanceof Number number) return number.intValue();
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private double getDouble(Map<String, Object> cfg, String key, double defaultValue) {
        if (cfg == null) return defaultValue;
        Object value = cfg.get(key);
        if (value instanceof Number number) return number.doubleValue();
        if (value != null) {
            try {
                return Double.parseDouble(value.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private boolean getBoolean(Map<String, Object> cfg, String key, boolean defaultValue) {
        if (cfg == null) return defaultValue;
        Object value = cfg.get(key);
        if (value instanceof Boolean bool) return bool;
        return value != null && "true".equalsIgnoreCase(value.toString());
    }
}
