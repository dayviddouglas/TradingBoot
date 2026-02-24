package com.github.dayviddouglas.TradingBot.bot;
import com.github.dayviddouglas.TradingBot.config.DerivProperties;
import com.github.dayviddouglas.TradingBot.deriv.DerivMarketDataService;
import com.github.dayviddouglas.TradingBot.deriv.DerivWsClient;
import com.github.dayviddouglas.TradingBot.engine.StrategyEngine;
import com.github.dayviddouglas.TradingBot.market.TickCandleAggregator;
import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.strategy.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class MultiSymbolDerivBotRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(MultiSymbolDerivBotRunner.class);

    private final DerivProperties props;
    private final List<TradingStrategy> strategies;

    public MultiSymbolDerivBotRunner(DerivProperties props, List<TradingStrategy> strategies) {
        this.props = props;
        this.strategies = strategies;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("Loaded symbols from config: {}", props.getSymbols());

        String endpoint = "wss://ws.derivws.com/websockets/v3?app_id=" + props.getAppId();
        log.info("Starting MULTI-SYMBOL bot. endpoint={} symbols={} granularity={}s historyCount={}",
                endpoint, props.getSymbols(), props.getCandleGranularitySeconds(), props.getHistoryCount());

        DerivWsClient ws = new DerivWsClient(new URI(endpoint));
        DerivMarketDataService md = new DerivMarketDataService(ws);

        Map<String, StrategyEngine> engines = new HashMap<>();
        Map<String, TickCandleAggregator> aggregators = new HashMap<>();

        for (String symbol : props.getSymbols()) {
            StrategyEngine engine = new StrategyEngine(500, strategies);
            engines.put(symbol, engine);

            TickCandleAggregator agg = new TickCandleAggregator(
                    props.getCandleGranularitySeconds(),
                    engine::onBar
            );
            aggregators.put(symbol, agg);
        }

        md.onTick((symbol, epoch, quote) -> {
            TickCandleAggregator agg = aggregators.get(symbol);
            if (agg != null) agg.onTick(epoch, quote);
        });

        ws.connectBlocking(10, TimeUnit.SECONDS);

        md.authorize(props.getToken());

        for (String symbol : props.getSymbols()) {
            md.fetchCandleHistory(symbol, props.getCandleGranularitySeconds(), props.getHistoryCount());
        }

        for (String symbol : props.getSymbols()) {
            log.info("Subscribing ticks for symbol={}", symbol);
            md.subscribeTicks(symbol);
        }

        log.info("MULTI-SYMBOL bot is running. Press Ctrl+C to stop.");
        Thread.currentThread().join();
    }
}
