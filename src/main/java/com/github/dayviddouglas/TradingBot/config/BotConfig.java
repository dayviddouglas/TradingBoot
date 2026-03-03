package com.github.dayviddouglas.TradingBot.config;

import com.github.dayviddouglas.TradingBot.deriv.DerivMarketDataService;
import com.github.dayviddouglas.TradingBot.deriv.DerivWsClient;
import com.github.dayviddouglas.TradingBot.engine.StrategyEngine;
import com.github.dayviddouglas.TradingBot.strategy.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.util.List;

@Configuration
public class BotConfig {


    @Bean
    public DerivWsClient derivWsClient(DerivProperties props) throws Exception {
        String endpoint = "wss://ws.derivws.com/websockets/v3?app_id=" + props.getAppId();
        return new DerivWsClient(new URI(endpoint));
    }

    @Bean
    public DerivMarketDataService derivMarketDataService(DerivWsClient ws) {
        return new DerivMarketDataService(ws);
    }

}