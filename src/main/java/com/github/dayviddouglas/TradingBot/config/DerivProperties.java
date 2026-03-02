package com.github.dayviddouglas.TradingBot.config;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

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

    @Min(10)
    @Max(5000)
    private int historyCount = 200;

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public int getAppId() { return appId; }
    public void setAppId(int appId) { this.appId = appId; }

    public int getHistoryCount() { return historyCount; }
    public void setHistoryCount(int historyCount) { this.historyCount = historyCount; }
}