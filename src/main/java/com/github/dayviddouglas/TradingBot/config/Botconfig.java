package com.github.dayviddouglas.TradingBot.config;
import com.tradingbot.engine.StrategyEngine;
import com.github.dayviddouglas.TradingBot.strategy.BreakoutStrategy;
import com.github.dayviddouglas.TradingBot.strategy.TradingStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class BotConfig {

    @Bean
    public List<TradingStrategy> tradingStrategies() {
        // MVP: only Breakout. Add more strategies here.
        return List.of(
                new BreakoutStrategy(20, 0.0005) // lookback=20, buffer=0.05%
        );
    }

    @Bean
    public StrategyEngine strategyEngine(List<TradingStrategy> strategies) {
        return new StrategyEngine(500, strategies);
    }
}
