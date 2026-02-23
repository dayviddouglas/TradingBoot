package com.github.dayviddouglas.TradingBot.strategy;
import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.model.Signal;
import java.util.List;

public interface TradingStrategy {

    String name();

    /**
     * @param bars Bars in ascending chronological order.
     *             bars.get(bars.size()-1) is the most recent bar.
     */
    Signal checkSignal(List<Bar> bars);
}
