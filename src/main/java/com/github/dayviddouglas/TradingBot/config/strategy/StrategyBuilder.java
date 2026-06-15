package com.github.dayviddouglas.TradingBot.config.strategy;

import com.github.dayviddouglas.TradingBot.strategy.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Responsável por construir instâncias de {@link TradingStrategy}
 * a partir da configuração lida do strategies.json.
 *
 * Cada estratégia é construída somente se estiver habilitada no JSON
 * ({@code enabled: true}). Os parâmetros de cada estratégia são lidos
 * diretamente do mapa de configuração, com valores padrão aplicados quando
 * a chave está ausente ou não conversível.
 *
 * Para adicionar nova estratégia:
 * 1. Criar classe que implementa {@link TradingStrategy}
 * 2. Adicionar método {@code buildXxx()} aqui
 * 3. Chamar esse método em {@link #build(Map)}
 * 4. Adicionar bloco correspondente no strategies.json
 */
@Component
public class StrategyBuilder {

    /**
     * Constrói a lista de estratégias habilitadas para o profile.
     * A ordem de chamada dos builders define a ordem de avaliação no engine.
     * Estratégias com {@code enabled: false} ou ausentes do mapa são ignoradas.
     *
     * @param strategies mapa de nome da estratégia para seus parâmetros de configuração
     * @return lista de instâncias de estratégias habilitadas e configuradas
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

    /**
     * Constrói a {@link EmaRsiStrategy} a partir do bloco {@code emaRsi} do JSON.
     * Combina cruzamento de EMAs rápida/lenta com filtro de RSI Wilder para geração de sinal.
     */
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

    /**
     * Constrói a {@link SupportResistanceStrategy} a partir do bloco {@code supportResistance}.
     * Identifica zonas de suporte e resistência por clustering de múltiplos toques
     * dentro de uma janela de lookback.
     */
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

    /**
     * Constrói a {@link PinBarStrategy} a partir do bloco {@code pinBar}.
     * Detecta padrões de rejeição de preço (pin bars) com confirmação por zonas
     * de suporte e resistência calculadas via ATR.
     */
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

    /**
     * Constrói a {@link BreakoutStrategy} a partir do bloco {@code breakout}.
     * Sinaliza rompimento de máximas e mínimas da janela de lookback,
     * com buffer percentual para evitar falsos rompimentos.
     */
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

    /**
     * Constrói a {@link BollingerMeanReversionStrategy} a partir do bloco
     * {@code bollingerMeanReversion}. Utiliza desvio padrão amostral (N-1)
     * e confirmação opcional via RSI Wilder.
     */
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

    /**
     * Constrói a {@link ZScoreMeanReversionStrategy} a partir do bloco
     * {@code zScoreMeanReversion}. Utiliza desvio padrão populacional (N)
     * para cálculo do Z-Score de distância da média.
     */
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

    /**
     * Constrói a {@link KeltnerChannelStrategy} a partir do bloco {@code keltnerChannel}.
     * Opera rompimentos do canal formado por EMA central com bandas baseadas em ATR.
     */
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

    /**
     * Constrói a primeira instância de {@link DonchianBreakoutStrategy} a partir do
     * bloco {@code donchianSystem1}. Configuração de curto prazo: entrada em 20 períodos
     * e saída em 10 períodos, com expansão mínima de ATR de 5%.
     */
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

    /**
     * Constrói a segunda instância de {@link DonchianBreakoutStrategy} a partir do
     * bloco {@code donchianSystem2}. Configuração de longo prazo: entrada em 55 períodos
     * e saída em 20 períodos, com expansão mínima de ATR de 10%.
     */
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

    /**
     * Verifica se uma estratégia está habilitada no mapa de configuração.
     * Aceita tanto {@code Boolean} direto quanto representação textual {@code "true"}.
     * Retorna {@code false} quando o mapa for nulo ou o campo {@code enabled} estiver ausente.
     *
     * @param cfg mapa de parâmetros da estratégia
     * @return {@code true} se a estratégia estiver habilitada
     */
    private boolean isEnabled(Map<String, Object> cfg) {
        if (cfg == null) return false;
        Object enabled = cfg.get("enabled");
        if (enabled instanceof Boolean bool) return bool;
        return enabled != null && "true".equalsIgnoreCase(enabled.toString());
    }

    /**
     * Lê um valor inteiro do mapa de configuração pelo nome da chave.
     * Suporta valores do tipo {@link Number} e representação textual numérica.
     * Retorna o valor padrão quando o mapa for nulo, a chave estiver ausente
     * ou o valor não for conversível.
     *
     * @param cfg          mapa de parâmetros da estratégia
     * @param key          nome da chave a ser lida
     * @param defaultValue valor retornado em caso de ausência ou falha de conversão
     * @return valor inteiro lido ou o padrão informado
     */
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

    /**
     * Lê um valor decimal do mapa de configuração pelo nome da chave.
     * Suporta valores do tipo {@link Number} e representação textual numérica.
     * Retorna o valor padrão quando o mapa for nulo, a chave estiver ausente
     * ou o valor não for conversível.
     *
     * @param cfg          mapa de parâmetros da estratégia
     * @param key          nome da chave a ser lida
     * @param defaultValue valor retornado em caso de ausência ou falha de conversão
     * @return valor decimal lido ou o padrão informado
     */
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

    /**
     * Lê um valor booleano do mapa de configuração pelo nome da chave.
     * Aceita tanto {@code Boolean} direto quanto a representação textual {@code "true"}.
     * Retorna o valor padrão quando o mapa for nulo ou a chave estiver ausente.
     *
     * @param cfg          mapa de parâmetros da estratégia
     * @param key          nome da chave a ser lida
     * @param defaultValue valor retornado em caso de ausência
     * @return valor booleano lido ou o padrão informado
     */
    private boolean getBoolean(Map<String, Object> cfg, String key, boolean defaultValue) {
        if (cfg == null) return defaultValue;
        Object value = cfg.get(key);
        if (value instanceof Boolean bool) return bool;
        return value != null && "true".equalsIgnoreCase(value.toString());
    }
}