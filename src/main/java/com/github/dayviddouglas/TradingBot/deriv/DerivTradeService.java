package com.github.dayviddouglas.TradingBot.deriv;

import com.github.dayviddouglas.TradingBot.config.StrategiesProfile;
import com.github.dayviddouglas.TradingBot.config.TradeConfig;
import com.github.dayviddouglas.TradingBot.exceptions.DerivErrorException;
import com.github.dayviddouglas.TradingBot.model.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class DerivTradeService {
    private static final Logger log = LoggerFactory.getLogger(DerivTradeService.class);

    private final DerivMarketDataService md;

    private enum State { IDLE, PENDING, OPEN }

    private static final class TradeState {
        final String symbol;
        volatile State state = State.IDLE;
        volatile Long contractId;
        volatile String subscriptionId; // from proposal_open_contract stream (if any)
        TradeState(String symbol) { this.symbol = symbol; }
    }

    private final Map<String, TradeState> statesBySymbol = new ConcurrentHashMap<>();
    private final Map<Long, TradeState> statesByContractId = new ConcurrentHashMap<>();

    private final Duration proposalTimeout = Duration.ofSeconds(12);
    private final Duration buyTimeout = Duration.ofSeconds(12);

    // NEW: watchdog tuning
    private final Duration closeGrace = Duration.ofSeconds(75); // after expected expiry
    private final int pollAttempts = 6;                         // attempts after grace
    private final Duration pollInterval = Duration.ofSeconds(10);

    public DerivTradeService(DerivMarketDataService md) {
        this.md = md;

        // keep this connected; if stream works, we close immediately on is_sold=1
        this.md.onOpenContract(this::handleOpenContractUpdate);
    }

    public void onFinalSignal(StrategiesProfile profile, Signal finalSignal) {
        if (profile == null || finalSignal == null) return;
        if (finalSignal.getType() == Signal.Type.NONE) return;

        TradeConfig trade = profile.getTrade();
        if (trade == null || !trade.isEnabled()) return;

        String symbol = profile.getSymbol();
        TradeState st = statesBySymbol.computeIfAbsent(symbol, TradeState::new);

        synchronized (st) {
            if (st.state != State.IDLE) {
                log.info("TRADE IGNORE (already {}) | symbol={} | signal={}", st.state, symbol, finalSignal.getType());
                return;
            }
            st.state = State.PENDING;
            st.contractId = null;
            st.subscriptionId = null;
        }

        String contractType = mapToContractType(finalSignal.getType());
        double amount = trade.getAmount();
        String currency = trade.getCurrency();
        int duration = trade.getDuration();
        String durationUnit = trade.getDurationUnit();

        log.info("TRADE START | symbol={} | signal={} -> contract_type={} | amount={} {} | duration={}{}",
                symbol, finalSignal.getType(), contractType, amount, currency, duration, durationUnit);

        Thread.startVirtualThread(() -> {
            try {
                executeTradeFlow(st, symbol, contractType, amount, currency, duration, durationUnit);
            } catch (Exception e) {
                log.warn("TRADE FAILED | symbol={} | reason={}", symbol, e.getMessage(), e);
                resetToIdle(st);
            }
        });
    }

    private void executeTradeFlow(TradeState st,
                                  String symbol,
                                  String contractType,
                                  double amount,
                                  String currency,
                                  int duration,
                                  String durationUnit) {

        // 1) proposal
        JsonNode proposalMsg = md.requestProposal(symbol, contractType, amount, currency, duration, durationUnit)
                .orTimeout(proposalTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .join();

        JsonNode proposal = proposalMsg.get("proposal");
        if (proposal == null || !proposal.isObject()) {
            throw new IllegalStateException("Resposta proposal inválida: " + proposalMsg);
        }

        String proposalId = proposal.path("id").asText("");
        double askPrice = proposal.path("ask_price").asDouble(Double.NaN);
        if (proposalId.isBlank() || !Double.isFinite(askPrice)) {
            throw new IllegalStateException("proposal sem id/ask_price: " + proposal);
        }

        log.info("TRADE PROPOSAL OK | symbol={} | contract_type={} | proposalId={} | askPrice={}",
                symbol, contractType, proposalId, askPrice);

        // 2) buy
        JsonNode buyMsg = md.buy(proposalId, amount)
                .orTimeout(buyTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .join();

        JsonNode buy = buyMsg.get("buy");
        if (buy == null || !buy.isObject()) {
            throw new IllegalStateException("Resposta buy inválida: " + buyMsg);
        }

        long contractId = buy.path("contract_id").asLong(-1);
        double buyPrice = buy.path("buy_price").asDouble(Double.NaN);
        if (contractId <= 0) {
            throw new IllegalStateException("buy sem contract_id: " + buy);
        }

        synchronized (st) {
            st.contractId = contractId;
            st.state = State.OPEN;
        }
        statesByContractId.put(contractId, st);

        log.info("TRADE BUY OK | symbol={} | contract_id={} | buy_price={}",
                symbol, contractId, Double.isFinite(buyPrice) ? buyPrice : null);

        // 3) subscribe open contract (may or may not stream reliably)
        JsonNode ack = md.subscribeOpenContract(contractId).join();
        log.info("TRADE MONITOR SUBSCRIBED | symbol={} | contract_id={} | ack_msg_type={}",
                symbol, contractId, ack.path("msg_type").asText(""));

        // 4) WATCHDOG fallback: guarantee closure logging even if stream fails
        startWatchdogPoll(st, contractId, duration, durationUnit);
    }

    private void startWatchdogPoll(TradeState st, long contractId, int duration, String durationUnit) {
        Duration expected = toDuration(duration, durationUnit);
        if (expected == null) {
            // if unsupported unit (e.g., ticks), we can still do a generic fallback after some time
            expected = Duration.ofMinutes(20);
        }

        Duration wait = expected.plus(closeGrace);

        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(wait.toMillis());
            } catch (InterruptedException ignored) {
                return;
            }

            // if already closed by stream, exit
            TradeState current = statesByContractId.get(contractId);
            if (current == null) return;

            // poll attempts
            for (int i = 1; i <= pollAttempts; i++) {
                // if closed by stream between polls
                if (statesByContractId.get(contractId) == null) return;

                try {
                    JsonNode msg = md.getOpenContractOnce(contractId)
                            .orTimeout(8, TimeUnit.SECONDS)
                            .join();

                    JsonNode poc = msg.get("proposal_open_contract");
                    if (poc != null && poc.isObject()) {
                        int isSold = poc.path("is_sold").asInt(0);
                        if (isSold == 1) {
                            // finalize using same handler logic
                            handleClosedContractFromPoc(poc, msg);
                            return;
                        }
                    }
                } catch (Exception e) {
                    // swallow and retry
                    log.debug("WATCHDOG poll failed | contract_id={} attempt={} err={}",
                            contractId, i, e.getMessage());
                }

                try {
                    Thread.sleep(pollInterval.toMillis());
                } catch (InterruptedException ignored) {
                    return;
                }
            }

            // give up (rare)
            log.warn("WATCHDOG: contract did not close after polls | contract_id={} symbol={}",
                    contractId, st.symbol);
        });
    }

    private void handleOpenContractUpdate(JsonNode msg) {
        try {
            JsonNode poc = msg.get("proposal_open_contract");
            if (poc == null || !poc.isObject()) return;

            long contractId = poc.path("contract_id").asLong(-1);
            if (contractId <= 0) return;

            TradeState st = statesByContractId.get(contractId);
            if (st == null) return; // not ours

            // capture subscription id (if any)
            String subscriptionId = msg.path("subscription").path("id").asText("");
            if (!subscriptionId.isBlank()) st.subscriptionId = subscriptionId;

            int isSold = poc.path("is_sold").asInt(0);
            if (isSold != 1) return;

            handleClosedContractFromPoc(poc, msg);

        } catch (DerivErrorException e) {
            log.warn("TRADE STREAM ERROR | message={}", e.getMessage(), e);
        } catch (Exception e) {
            log.warn("Failed to handle open contract update: {}", e.getMessage(), e);
        }
    }

    private void handleClosedContractFromPoc(JsonNode poc, JsonNode fullMsg) {
        long contractId = poc.path("contract_id").asLong(-1);
        if (contractId <= 0) return;

        TradeState st = statesByContractId.get(contractId);
        if (st == null) return;

        double profit = poc.path("profit").asDouble(Double.NaN);
        String result = (Double.isFinite(profit) && profit > 0) ? "WIN" : "LOSS";

        log.info("TRADE CLOSED | symbol={} | contract_id={} | result={} | profit={}",
                st.symbol, contractId, result, Double.isFinite(profit) ? profit : null);

        // optional: stop subscription if we have it
        String subscriptionId = st.subscriptionId;
        if (subscriptionId == null || subscriptionId.isBlank()) {
            subscriptionId = fullMsg.path("subscription").path("id").asText("");
        }
        if (subscriptionId != null && !subscriptionId.isBlank()) {
            try {
                md.forget(subscriptionId);
            } catch (Exception ignored) {}
        }

        // cleanup
        statesByContractId.remove(contractId);
        resetToIdle(st);
    }

    private void resetToIdle(TradeState st) {
        synchronized (st) {
            st.state = State.IDLE;
            st.contractId = null;
            st.subscriptionId = null;
        }
    }

    private static String mapToContractType(Signal.Type t) {
        return switch (t) {
            case BUY -> "CALL";
            case SELL -> "PUT";
            default -> throw new IllegalArgumentException("Cannot map signal type " + t);
        };
    }

    /**
     * Convert Deriv duration to Java Duration for watchdog.
     * Supports: s,m,h,d. (Ticks "t" not handled here.)
     */
    private static Duration toDuration(int duration, String unit) {
        if (unit == null) return null;
        return switch (unit.trim()) {
            case "s" -> Duration.ofSeconds(duration);
            case "m" -> Duration.ofMinutes(duration);
            case "h" -> Duration.ofHours(duration);
            case "d" -> Duration.ofDays(duration);
            default -> null;
        };
    }
}