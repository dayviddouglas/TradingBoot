package com.github.dayviddouglas.TradingBot.config;

import com.github.dayviddouglas.TradingBot.engine.StrategyEngine;
import com.github.dayviddouglas.TradingBot.strategy.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class BotConfig {

    @Bean
    public List<TradingStrategy> tradingStrategies() {
        return List.of(
                new EmaRsiStrategy(12, 26, 14, 55.0, 45.0),
                new SupportResistanceStrategy(50, 0.001),     // 0.1% tolerance
                new PinBarStrategy(2.5, 0.7, 50, 0.001),      // pinbar only near S/R
                new BreakoutStrategy(20, 0.0005)              // breakout w/ strong candle filter
        );
    }

}