package com.github.dayviddouglas.TradingBot.bot;

import com.github.dayviddouglas.TradingBot.config.strategy.StrategiesProfile;
import com.github.dayviddouglas.TradingBot.engine.core.StrategyEngine;
import com.github.dayviddouglas.TradingBot.market.TickCandleAggregator;

/**
 * Representa o pipeline completo de um ativo em tempo real.
 *
 * Agrupa os três componentes necessários para processar
 * ticks de um ativo e gerar sinais operacionais:
 *
 * - profile: configuração do ativo (decisionMode, trade, etc.)
 * - engine: motor de decisão que avalia estratégias por candle
 * - aggregator: converte ticks em candles OHLC fechados
 *
 * Criado pelo PipelineRegistry durante o bootstrap do bot.
 * Imutável após criação (record).
 *
 * @param profile    configuração do ativo no strategies.json
 * @param engine     motor de decisão configurado para o ativo
 * @param aggregator conversor de ticks em candles OHLC
 */
public record BotPipeline(
        StrategiesProfile profile,
        StrategyEngine engine,
        TickCandleAggregator aggregator
) {
}
