package com.github.dayviddouglas.TradingBoot.bot;

import com.github.dayviddouglas.TradingBoot.config.strategy.StrategiesProfile;
import com.github.dayviddouglas.TradingBoot.engine.core.StrategyEngine;
import com.github.dayviddouglas.TradingBoot.market.TickCandleAggregator;

/**
 * Representa o pipeline completo de processamento de um ativo em tempo real.
 *
 * Agrupa os três componentes necessários para processar
 * ticks de um ativo e gerar sinais operacionais:
 *
 * <ul>
 *   <li>{@code profile} — configuração do ativo, incluindo decisionMode,
 *       parâmetros de trade e estratégias habilitadas.</li>
 *   <li>{@code engine} — motor de decisão que avalia as estratégias
 *       configuradas a cada candle fechado.</li>
 *   <li>{@code aggregator} — converte ticks recebidos em tempo real
 *       em candles OHLC completos.</li>
 * </ul>
 *
 * Instâncias são criadas pelo {@code PipelineRegistry} durante o bootstrap
 * do bot e permanecem imutáveis durante toda a execução.
 *
 * @param profile    configuração do ativo lida do strategies.json
 * @param engine     motor de decisão configurado para o ativo
 * @param aggregator conversor de ticks em candles OHLC
 */
public record BotPipeline(
        StrategiesProfile profile,
        StrategyEngine engine,
        TickCandleAggregator aggregator
) {
}