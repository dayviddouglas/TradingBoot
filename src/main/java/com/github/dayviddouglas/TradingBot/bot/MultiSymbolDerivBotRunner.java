package com.github.dayviddouglas.TradingBot.bot;
import com.github.dayviddouglas.TradingBot.config.DerivProperties;
import com.github.dayviddouglas.TradingBot.config.StrategiesConfigLoader;
import com.github.dayviddouglas.TradingBot.config.StrategiesProfile;
import com.github.dayviddouglas.TradingBot.deriv.DerivMarketDataService;
import com.github.dayviddouglas.TradingBot.deriv.DerivWsClient;
import com.github.dayviddouglas.TradingBot.engine.StrategyEngine;
import com.github.dayviddouglas.TradingBot.market.TickCandleAggregator;
import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.strategy.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class MultiSymbolDerivBotRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(MultiSymbolDerivBotRunner.class);

    private final DerivProperties derivProps;
    private final StrategiesConfigLoader strategiesLoader;

    public MultiSymbolDerivBotRunner(DerivProperties derivProps, StrategiesConfigLoader strategiesLoader) {
        this.derivProps = derivProps;
        this.strategiesLoader = strategiesLoader;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<StrategiesProfile> profiles = strategiesLoader.getProfiles();
        if (profiles.isEmpty()) throw new IllegalStateException("Nenhum profile encontrado em configs/strategies.json");

        String endpoint = "wss://ws.derivws.com/websockets/v3?app_id=" + derivProps.getAppId();
        log.info("Starting bot (profiles from strategies.json). endpoint={} profiles={}", endpoint, profiles.size());

        DerivWsClient ws = new DerivWsClient(new URI(endpoint));
        DerivMarketDataService md = new DerivMarketDataService(ws);

        // Chave única por (symbol + granularity)
        Map<String, StrategyEngine> engines = new HashMap<>();
        Map<String, TickCandleAggregator> aggregators = new HashMap<>();

        // 1) Monta engines e aggregators para todos profiles
        for (StrategiesProfile p : profiles) {
            String symbol = p.getSymbol();
            int gran = p.getGranularitySeconds();
            String key = key(symbol, gran);

            int maxBars = getInt(p.getEngine(), "maxBars", 500);

            List<TradingStrategy> strategies = buildStrategiesFromProfile(p);

            StrategyEngine engine = new StrategyEngine(symbol, maxBars, strategies);
            engines.put(key, engine);

            TickCandleAggregator agg = new TickCandleAggregator(
                    gran,
                    engine::onBar
            );
            aggregators.put(key, agg);

            log.info("Profile loaded: symbol={} granularitySeconds={} maxBars={} strategies={}",
                    symbol, gran, maxBars, strategies.size());
        }

        // 2) Roteia ticks por symbol -> alimenta todos aggregators daquele symbol (pode ter vários granularities)
        md.onTick((symbol, epoch, quote) -> {
            for (StrategiesProfile p : profiles) {
                if (!p.getSymbol().equals(symbol)) continue;

                String k = key(symbol, p.getGranularitySeconds());
                TickCandleAggregator agg = aggregators.get(k);
                if (agg != null) agg.onTick(epoch, quote);
            }
        });

        // 3) Conecta
        ws.connectBlocking(10, TimeUnit.SECONDS);

        // 4) Autoriza (necessário se quiser evoluir para execução de trades)
        md.authorize(derivProps.getToken());

        // 5) Seed best-effort (opcional): tenta baixar histórico para cada profile
        for (StrategiesProfile p : profiles) {
            md.fetchCandleHistory(p.getSymbol(), p.getGranularitySeconds(), derivProps.getHistoryCount());
        }

        // 6) Subscribe ticks: apenas 1x por symbol
        Set<String> uniqueSymbols = new HashSet<>();
        for (StrategiesProfile p : profiles) uniqueSymbols.add(p.getSymbol());

        for (String symbol : uniqueSymbols) {
            log.info("Subscribing ticks for symbol={}", symbol);
            md.subscribeTicks(symbol);
        }

        log.info("Bot is running with {} symbols and {} profiles. Press Ctrl+C to stop.",
                uniqueSymbols.size(), profiles.size());

        Thread.currentThread().join();
    }

    private static String key(String symbol, int granularitySeconds) {
        return symbol + "_" + granularitySeconds;
    }

    private static int getInt(Map<String, Object> map, String key, int defaultValue) {
        if (map == null) return defaultValue;
        Object v = map.get(key);
        if (v instanceof Integer i) return i;
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (Exception ignored) {}
        }
        return defaultValue;
    }

    private static double getDouble(Map<String, Object> map, String key, double defaultValue) {
        if (map == null) return defaultValue;
        Object v = map.get(key);
        if (v instanceof Double d) return d;
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s.trim()); } catch (Exception ignored) {}
        }
        return defaultValue;
    }

    private static boolean getBool(Map<String, Object> map, String key, boolean defaultValue) {
        if (map == null) return defaultValue;
        Object v = map.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s.trim());
        return defaultValue;
    }

    private List<TradingStrategy> buildStrategiesFromProfile(StrategiesProfile p) {
        Map<String, Map<String, Object>> s = p.getStrategies();
        if (s == null) throw new IllegalStateException("Profile sem strategies: symbol=" + p.getSymbol());

        List<TradingStrategy> list = new ArrayList<>();

        // EMA+RSI
        Map<String, Object> emaRsi = s.get("emaRsi");
        if (getBool(emaRsi, "enabled", false)) {
            int emaFast = getInt(emaRsi, "emaFast", 12);
            int emaSlow = getInt(emaRsi, "emaSlow", 26);
            int rsiPeriod = getInt(emaRsi, "rsiPeriod", 14);
            double rsiBuy = getDouble(emaRsi, "rsiBuyThreshold", 55.0);
            double rsiSell = getDouble(emaRsi, "rsiSellThreshold", 45.0);
            list.add(new EmaRsiStrategy(emaFast, emaSlow, rsiPeriod, rsiBuy, rsiSell));
        }

        // Support/Resistance
        Map<String, Object> sr = s.get("supportResistance");
        if (getBool(sr, "enabled", false)) {
            int lookback = getInt(sr, "lookback", 50);
            double tol = getDouble(sr, "tolerancePct", 0.001);
            list.add(new SupportResistanceStrategy(lookback, tol));
        }

        // PinBar
        Map<String, Object> pin = s.get("pinBar");
        if (getBool(pin, "enabled", false)) {
            double wickToBody = getDouble(pin, "wickToBodyRatio", 2.5);
            double maxOpp = getDouble(pin, "maxOppositeWickToBody", 0.7);
            int srLookback = getInt(pin, "srLookback", 50);
            double tol = getDouble(pin, "tolerancePct", 0.001);
            list.add(new PinBarStrategy(wickToBody, maxOpp, srLookback, tol));
        }

        // Breakout
        Map<String, Object> br = s.get("breakout");
        if (getBool(br, "enabled", false)) {
            int lookback = getInt(br, "lookback", 20);
            double buffer = getDouble(br, "bufferPct", 0.0005);
            list.add(new BreakoutStrategy(lookback, buffer));
        }

        // Requisito: se não tiver nenhuma estratégia habilitada, falha
        if (list.isEmpty()) {
            throw new IllegalStateException("Nenhuma estratégia habilitada no profile: symbol=" + p.getSymbol());
        }

        return List.copyOf(list);
    }
}