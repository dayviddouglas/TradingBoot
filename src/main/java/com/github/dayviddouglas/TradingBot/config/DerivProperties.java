package com.github.dayviddouglas.TradingBot.config;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

/**
 * application.yml:
 *
 * deriv:
 *   token: "..."
 *   app-id: 1089
 *   symbols: ["R_100", "frxEURUSD"]
 *   candle-granularity-seconds: 60
 *   history-count: 200
 */
@Validated
@ConfigurationProperties(prefix = "deriv")
public class DerivProperties {

    @NotBlank
    private String token;

    @Min(1)
    private int appId = 1089;

    @NotEmpty
    private List<String> symbols;

    @Min(60)
    @Max(86400)
    private int candleGranularitySeconds = 60;

    @Min(10)
    @Max(5000)
    private int historyCount = 200;

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public int getAppId() { return appId; }
    public void setAppId(int appId) { this.appId = appId; }

    public List<String> getSymbols() { return symbols; }
    public void setSymbols(List<String> symbols) { this.symbols = symbols; }

    public int getCandleGranularitySeconds() { return candleGranularitySeconds; }
    public void setCandleGranularitySeconds(int candleGranularitySeconds) {
        this.candleGranularitySeconds = candleGranularitySeconds;
    }

    public int getHistoryCount() { return historyCount; }
    public void setHistoryCount(int historyCount) { this.historyCount = historyCount; }
}