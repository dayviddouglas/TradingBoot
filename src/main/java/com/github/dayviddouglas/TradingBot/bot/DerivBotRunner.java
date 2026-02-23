package com.github.dayviddouglas.TradingBot.bot;
import com.github.dayviddouglas.TradingBot.config.DerivProperties;
import com.github.dayviddouglas.TradingBot.deriv.DerivMarketDataService;
import com.github.dayviddouglas.TradingBot.deriv.DerivWsClient;
import com.github.dayviddouglas.TradingBot.engine.StrategyEngine;
import com.github.dayviddouglas.TradingBot.market.TickCandleAggregator;
import com.github.dayviddouglas.TradingBot.model.Bar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Service
public class DerivBotRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DerivBotRunner.class);

    private final DerivProperties props;
    private final StrategyEngine engine;

    public DerivBotRunner(DerivProperties props, StrategyEngine engine) {
        this.props = props;
        this.engine = engine;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String endpoint = "wss://ws.derivws.com/websockets/v3?app_id=" + props.getAppId();
        log.info("Starting bot (ticks->candles). endpoint={} symbol={} granularity={}s historyCount={}",
                endpoint, props.getSymbol(), props.getCandleGranularitySeconds(), props.getHistoryCount());

        DerivWsClient ws = new DerivWsClient(new URI(endpoint));
        DerivMarketDataService md = new DerivMarketDataService(ws);

        CountDownLatch historyLatch = new CountDownLatch(1);

        md.onCandleHistory((List<Bar> bars) -> {
            log.info("Received candle history bars={}", bars.size());
            engine.seedHistory(bars);
            historyLatch.countDown();
        });

        // Aggregator: emits CLOSED candles based on ticks
        TickCandleAggregator aggregator = new TickCandleAggregator(
                props.getCandleGranularitySeconds(),
                engine::onBar
        );

        // Handle ticks: feed aggregator
        md.onTick((epoch, quote) -> aggregator.onTick(epoch, quote));

        ws.connectBlocking(10, TimeUnit.SECONDS);

        md.authorize(props.getToken());

        // Best-effort seed history (not required for live candles from ticks)
        md.fetchCandleHistory(props.getSymbol(), props.getCandleGranularitySeconds(), props.getHistoryCount());
        if (!historyLatch.await(15, TimeUnit.SECONDS)) {
            log.warn("Timeout waiting candle history. Continuing without seed.");
        }

        // Subscribe ONLY ticks (avoid 'candles' request that was failing)
        md.subscribeTicks(props.getSymbol());

        log.info("Bot is running (ticks subscription). Press Ctrl+C to stop.");
        Thread.currentThread().join();
    }
}