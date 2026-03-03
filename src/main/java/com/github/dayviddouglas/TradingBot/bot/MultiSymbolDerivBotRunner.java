package com.github.dayviddouglas.TradingBot.bot;
import com.github.dayviddouglas.TradingBot.config.DerivProperties;
import com.github.dayviddouglas.TradingBot.config.StrategiesConfigLoader;
import com.github.dayviddouglas.TradingBot.config.StrategiesProfile;
import com.github.dayviddouglas.TradingBot.deriv.DerivMarketDataService;
import com.github.dayviddouglas.TradingBot.deriv.DerivTradeService;
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
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class MultiSymbolDerivBotRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(MultiSymbolDerivBotRunner.class);

    private final DerivProperties derivProps;
    private final StrategiesConfigLoader strategiesLoader;
    private final DerivTradeService tradeService;

    // injected beans (Option A)
    private final DerivWsClient ws;
    private final DerivMarketDataService md;

    public MultiSymbolDerivBotRunner(DerivProperties derivProps,
                                     StrategiesConfigLoader strategiesLoader,
                                     DerivTradeService tradeService,
                                     DerivWsClient ws,
                                     DerivMarketDataService md) {
        this.derivProps = derivProps;
        this.strategiesLoader = strategiesLoader;
        this.tradeService = tradeService;
        this.ws = ws;
        this.md = md;
    }

    private record HistoryRoute(String symbol, int granularitySeconds, String engineKey) {}

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<StrategiesProfile> profiles = strategiesLoader.getProfiles();
        if (profiles.isEmpty()) throw new IllegalStateException("Nenhum profile encontrado em configs/strategies.json");

        log.info("Starting bot (profiles from strategies.json). appId={} profiles={}",
                derivProps.getAppId(), profiles.size());

        // Key by (symbol + granularity)
        Map<String, StrategyEngine> engines = new HashMap<>();
        Map<String, TickCandleAggregator> aggregators = new HashMap<>();

        // req_id -> routing context (symbol/gran/engineKey)
        Map<Long, HistoryRoute> historyReqRoutes = new ConcurrentHashMap<>();

        // Enable temporarily to capture raw proposal_open_contract stream JSON
        // IMPORTANT: set to false after diagnostics (it can be very noisy)
        md.setLogRawProposalOpenContract(true);

        // History callback routed by req_id
        md.onCandleHistory((reqId, bars) -> {
            HistoryRoute route = historyReqRoutes.remove(reqId);
            if (route == null) {
                log.debug("History received but no routing found for req_id={} (ignored). bars={}", reqId, bars.size());
                return;
            }

            StrategyEngine engine = engines.get(route.engineKey());
            if (engine == null) {
                log.warn("History routing found but engine missing. req_id={} symbol={} granularity={} engineKey={}",
                        reqId, route.symbol(), route.granularitySeconds(), route.engineKey());
                return;
            }

            engine.seedHistory(bars);

            log.info("History seeded | req_id={} | symbol={} | granularity={} | engineKey={} | bars={}",
                    reqId, route.symbol(), route.granularitySeconds(), route.engineKey(), bars.size());
        });

        // Build engines + aggregators
        for (StrategiesProfile p : profiles) {
            String symbol = p.getSymbol();
            int gran = p.getGranularitySeconds();
            String key = key(symbol, gran);

            int maxBars = getInt(p.getEngine(), "maxBars", 500);
            List<TradingStrategy> strategies = buildStrategiesFromProfile(p);

            StrategyEngine engine = new StrategyEngine(symbol, maxBars, strategies);

            // connect final signal -> trade service
            engine.onFinalSignal(sig -> tradeService.onFinalSignal(p, sig));

            engines.put(key, engine);

            TickCandleAggregator agg = new TickCandleAggregator(
                    gran,
                    engine::onBar
            );
            aggregators.put(key, agg);

            log.info("Profile loaded: symbol={} granularitySeconds={} maxBars={} strategies={} tradeEnabled={}",
                    symbol, gran, maxBars, strategies.size(), p.getTrade() != null && p.getTrade().isEnabled());
        }

        // Tick routing by symbol -> all aggregators for that symbol
        md.onTick((symbol, epoch, quote) -> {
            for (StrategiesProfile p : profiles) {
                if (!p.getSymbol().equals(symbol)) continue;

                String k = key(symbol, p.getGranularitySeconds());
                TickCandleAggregator agg = aggregators.get(k);
                if (agg != null) agg.onTick(epoch, quote);
            }
        });

        // Unique symbols
        Set<String> uniqueSymbols = new HashSet<>();
        for (StrategiesProfile p : profiles) uniqueSymbols.add(p.getSymbol());

        // prevent overlapping bootstraps (in case of reconnect storms)
        AtomicBoolean bootstrapping = new AtomicBoolean(false);

        Runnable bootstrap = () -> {
            if (!bootstrapping.compareAndSet(false, true)) return;

            try {
                // authorize (timeout increased to reduce false fail-fast)
                md.authorizeAndWaitFailFast(derivProps.getToken(), Duration.ofSeconds(30));

                // seed history best-effort (req_id -> engineKey)
                for (StrategiesProfile p : profiles) {
                    String symbol = p.getSymbol();
                    int gran = p.getGranularitySeconds();
                    String engineKey = key(symbol, gran);

                    long reqId = md.fetchCandleHistory(symbol, gran, derivProps.getHistoryCount());
                    historyReqRoutes.put(reqId, new HistoryRoute(symbol, gran, engineKey));

                    log.info("History requested | req_id={} | symbol={} | granularity={} | engineKey={}",
                            reqId, symbol, gran, engineKey);
                }

                // subscribe ticks (needed after reconnect too)
                for (String symbol : uniqueSymbols) {
                    log.info("Subscribing ticks for symbol={}", symbol);
                    md.subscribeTicks(symbol);
                }

                log.info("Bootstrap OK (authorize + history + subscriptions). symbols={} profiles={}",
                        uniqueSymbols.size(), profiles.size());

            } catch (Exception e) {
                log.warn("Bootstrap failed: {}", e.getMessage(), e);
            } finally {
                bootstrapping.set(false);
            }
        };

        // Hook lifecycle in WS client (bootstrap runs onOpen)
        ws.setOnConnected(bootstrap);
        ws.setOnDisconnected(evt -> log.warn("WS disconnected (event) code={} remote={} reason={}",
                evt.code(), evt.remote(), evt.reason()));

        // Connect first time
        ws.connectBlocking(10, TimeUnit.SECONDS);

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

        Map<String, Object> emaRsi = s.get("emaRsi");
        if (getBool(emaRsi, "enabled", false)) {
            int emaFast = getInt(emaRsi, "emaFast", 12);
            int emaSlow = getInt(emaRsi, "emaSlow", 26);
            int rsiPeriod = getInt(emaRsi, "rsiPeriod", 14);
            double rsiBuy = getDouble(emaRsi, "rsiBuyThreshold", 55.0);
            double rsiSell = getDouble(emaRsi, "rsiSellThreshold", 45.0);
            list.add(new EmaRsiStrategy(emaFast, emaSlow, rsiPeriod, rsiBuy, rsiSell));
        }

        Map<String, Object> sr = s.get("supportResistance");
        if (getBool(sr, "enabled", false)) {
            int lookback = getInt(sr, "lookback", 50);
            double tol = getDouble(sr, "tolerancePct", 0.001);
            list.add(new SupportResistanceStrategy(lookback, tol));
        }

        Map<String, Object> pin = s.get("pinBar");
        if (getBool(pin, "enabled", false)) {
            double wickToBody = getDouble(pin, "wickToBodyRatio", 2.5);
            double maxOpp = getDouble(pin, "maxOppositeWickToBody", 0.7);
            int srLookback = getInt(pin, "srLookback", 50);
            double tol = getDouble(pin, "tolerancePct", 0.001);
            list.add(new PinBarStrategy(wickToBody, maxOpp, srLookback, tol));
        }

        Map<String, Object> br = s.get("breakout");
        if (getBool(br, "enabled", false)) {
            int lookback = getInt(br, "lookback", 20);
            double buffer = getDouble(br, "bufferPct", 0.0005);
            list.add(new BreakoutStrategy(lookback, buffer));
        }

        if (list.isEmpty()) {
            throw new IllegalStateException("Nenhuma estratégia habilitada no profile: symbol=" + p.getSymbol());
        }

        return List.copyOf(list);
    }
}
