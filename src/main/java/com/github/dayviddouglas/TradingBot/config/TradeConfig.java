package com.github.dayviddouglas.TradingBot.config;

public class TradeConfig {

    private boolean enabled = false;

    private double amount = 0.0;
    private String currency = "USD";

    private int duration = 1;
    /**
     * Deriv format: "m" (minutes), "s" (seconds), "h" (hours), "d" (days)
     */
    private String durationUnit = "m";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public int getDuration() { return duration; }
    public void setDuration(int duration) { this.duration = duration; }

    public String getDurationUnit() { return durationUnit; }
    public void setDurationUnit(String durationUnit) { this.durationUnit = durationUnit; }
}