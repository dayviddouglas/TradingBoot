package com.github.dayviddouglas.TradingBot.backtest.runner;

import com.github.dayviddouglas.TradingBot.backtest.result.BacktestMetricsCalculator;
import com.github.dayviddouglas.TradingBot.backtest.result.BacktestReport;
import com.github.dayviddouglas.TradingBot.backtest.result.TradeResult;
import com.github.dayviddouglas.TradingBot.config.strategy.StrategiesProfile;
import com.github.dayviddouglas.TradingBot.engine.decision.DecisionMode;
import com.github.dayviddouglas.TradingBot.engine.core.StrategyEngine;
import com.github.dayviddouglas.TradingBot.engine.filter.VolatilityFilter;
import com.github.dayviddouglas.TradingBot.model.Bar;
import com.github.dayviddouglas.TradingBot.model.Signal;
import com.github.dayviddouglas.TradingBot.strategy.TradingStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Simula operações sobre histórico real de candles para análise estatística de backtest.
 *
 * Modela cada trade como entrada no fechamento do candle de sinal e saída no fechamento
 * do candle {@code tradeDurationBars} posições à frente:
 * <ul>
 *   <li>{@code WIN}: o preço se moveu na direção do sinal</li>
 *   <li>{@code PnL}: {@code +profitPayout} para WIN, {@code -1.0} para LOSS</li>
 * </ul>
 *
 * Modos de execução controlados pelo {@link DecisionMode}:
 * <ul>
 *   <li>{@link DecisionMode#SINGLE_STRATEGY}: avalia a estratégia diretamente sobre janelas
 *       deslizantes sem {@link StrategyEngine}, isolando o edge individual</li>
 *   <li>{@link DecisionMode#VOTING}: usa {@link StrategyEngine} com unanimidade conservadora</li>
 *   <li>{@link DecisionMode#CONFLUENCE}: usa {@link StrategyEngine} com ponderação por regime</li>
 * </ul>
 *
 * Quando um {@link StrategiesProfile} é fornecido, o {@link VolatilityFilter} é configurado
 * com os mesmos parâmetros do strategies.json, garantindo consistência entre runtime e backtest.
 * O {@link StrategyEngine} construído não recebe {@code MarketRegimeMonitor},
 * isolando o backtest de toda a infraestrutura de runtime.
 *
 * O uso do {@link StrategyEngine} é feito via callback: o sinal emitido é capturado por um
 * {@link AtomicReference} registrado em {@code onFinalSignal}, permitindo verificação
 * após cada chamada a {@code onBar}.
 *
 * Limitação: utiliza payout fixo ({@code profitPayout}), sem refletir o payout real
 * e dinâmico retornado pela API Deriv em tempo de execução.
 */
public class SimpleBacktester {

    private final String               symbol;
    private final List<TradingStrategy> strategies;
    private final int                  maxBars;
    private final double               profitPayout;
    private final int                  tradeDurationBars;
    private final DecisionMode         decisionMode;
    private final StrategiesProfile    profile;

    /**
     * Construtor retrocompatível sem profile.
     * O {@link VolatilityFilter} é criado com valores padrão.
     *
     * @param symbol            símbolo do ativo ou identificador do backtest
     * @param strategies        estratégias habilitadas para avaliação
     * @param maxBars           tamanho da janela de histórico para seed do engine
     * @param profitPayout      multiplicador de PnL para trades vencedores
     * @param tradeDurationBars número de candles à frente para avaliação do resultado
     * @param decisionMode      modo de decisão: SINGLE_STRATEGY, VOTING ou CONFLUENCE
     */
    public SimpleBacktester(
            String symbol,
            List<TradingStrategy> strategies,
            int maxBars,
            double profitPayout,
            int tradeDurationBars,
            DecisionMode decisionMode
    ) {
        this(symbol, strategies, maxBars, profitPayout,
                tradeDurationBars, decisionMode, null);
    }

    /**
     * Construtor completo com profile para configuração do {@link VolatilityFilter}.
     * Quando o profile é fornecido, o engine usa os parâmetros de volatilidade
     * do strategies.json, garantindo consistência entre runtime e backtest.
     *
     * @param symbol            símbolo do ativo ou identificador do backtest
     * @param strategies        estratégias habilitadas para avaliação
     * @param maxBars           tamanho da janela de histórico para seed do engine
     * @param profitPayout      multiplicador de PnL para trades vencedores
     * @param tradeDurationBars número de candles à frente para avaliação do resultado
     * @param decisionMode      modo de decisão: SINGLE_STRATEGY, VOTING ou CONFLUENCE
     * @param profile           profile com parâmetros do {@link VolatilityFilter}; {@code null} usa padrão
     */
    public SimpleBacktester(
            String symbol,
            List<TradingStrategy> strategies,
            int maxBars,
            double profitPayout,
            int tradeDurationBars,
            DecisionMode decisionMode,
            StrategiesProfile profile
    ) {
        this.symbol            = symbol;
        this.strategies        = strategies;
        this.maxBars           = maxBars;
        this.profitPayout      = profitPayout;
        this.tradeDurationBars = tradeDurationBars;
        this.decisionMode      = decisionMode;
        this.profile           = profile;
    }

    /**
     * Executa o backtest sobre o histórico completo de candles.
     * Retorna {@link BacktestReport#empty(String)} quando o histórico for insuficiente
     * para cobrir o período de seed mais pelo menos um trade.
     *
     * @param allBars histórico completo de candles em ordem cronológica ascendente
     * @return relatório com todas as métricas calculadas pelo {@link BacktestMetricsCalculator}
     */
    public BacktestReport run(List<Bar> allBars) {
        if (!hasSufficientBars(allBars)) {
            return BacktestReport.empty(symbol);
        }

        List<TradeResult> results = collectResults(allBars);

        return BacktestMetricsCalculator.calculate(symbol, results);
    }

    // ═══════════════════════════════════════════════════════════════
    // Coleta de resultados
    // ═══════════════════════════════════════════════════════════════

    /**
     * Direciona a coleta de resultados para o método correspondente ao {@link DecisionMode}.
     *
     * @param allBars histórico completo de candles
     * @return lista de resultados individuais dos trades simulados
     */
    private List<TradeResult> collectResults(List<Bar> allBars) {
        return switch (decisionMode) {
            case SINGLE_STRATEGY -> runSingleStrategy(allBars);
            case VOTING          -> runEngineMode(allBars, DecisionMode.VOTING);
            case CONFLUENCE      -> runEngineMode(allBars, DecisionMode.CONFLUENCE);
        };
    }

    /**
     * Simula trades usando o {@link StrategyEngine} nos modos VOTING ou CONFLUENCE.
     * Os primeiros {@code maxBars} candles são usados para seed do engine.
     * A partir do {@code maxBars}-ésimo candle, cada {@code onBar} pode emitir um sinal
     * capturado via {@link AtomicReference}. Quando um sinal válido é detectado,
     * o resultado é avaliado contra o candle {@code tradeDurationBars} à frente.
     *
     * @param allBars    histórico completo de candles
     * @param engineMode modo de decisão a ser usado no engine
     * @return lista de resultados dos trades simulados
     */
    private List<TradeResult> runEngineMode(
            List<Bar> allBars,
            DecisionMode engineMode
    ) {
        StrategyEngine            engine       = buildEngine(engineMode);
        List<TradeResult>         results      = new ArrayList<>();
        AtomicReference<Signal>   latestSignal = new AtomicReference<>();

        engine.onFinalSignal(latestSignal::set);

        seedEngine(engine, allBars);

        for (int i = maxBars; i < allBars.size() - tradeDurationBars; i++) {
            latestSignal.set(null);
            engine.onBar(allBars.get(i));

            Signal signal = latestSignal.get();
            if (isValidSignal(signal)) {
                Bar exitBar = allBars.get(i + tradeDurationBars);
                results.add(evaluate(signal, allBars.get(i), exitBar));
            }
        }

        return results;
    }

    /**
     * Simula trades avaliando a estratégia diretamente sobre janelas deslizantes de candles,
     * sem {@link StrategyEngine}. Permite medir o edge individual de uma única estratégia.
     * A janela de cada avaliação é {@code allBars[i - maxBars .. i]}.
     *
     * @param allBars histórico completo de candles
     * @return lista de resultados dos trades simulados
     */
    private List<TradeResult> runSingleStrategy(List<Bar> allBars) {
        if (strategies == null || strategies.isEmpty()) return List.of();

        TradingStrategy   strategy = strategies.get(0);
        List<TradeResult> results  = new ArrayList<>();

        for (int i = maxBars; i < allBars.size() - tradeDurationBars; i++) {
            List<Bar> window = allBars.subList(i - maxBars, i + 1);
            Signal    signal = strategy.checkSignal(window);

            if (isValidSignal(signal)) {
                Bar exitBar = allBars.get(i + tradeDurationBars);
                results.add(evaluate(signal, allBars.get(i), exitBar));
            }
        }

        return results;
    }

    // ═══════════════════════════════════════════════════════════════
    // Avaliação de sinal
    // ═══════════════════════════════════════════════════════════════

    /**
     * Avalia o resultado de um trade simulado comparando os preços de entrada e saída.
     * WIN para BUY quando o preço subiu; WIN para SELL quando o preço caiu.
     * O PnL é {@code +profitPayout} para WIN e {@code -1.0} para LOSS.
     *
     * @param signal   sinal que originou a entrada
     * @param entryBar candle no momento do sinal (preço de entrada = close)
     * @param exitBar  candle {@code tradeDurationBars} à frente (preço de saída = close)
     * @return resultado imutável do trade simulado
     */
    private TradeResult evaluate(Signal signal, Bar entryBar, Bar exitBar) {
        double entryPrice = entryBar.close();
        double exitPrice  = exitBar.close();

        boolean won = signal.getType() == Signal.Type.BUY
                ? exitPrice > entryPrice
                : exitPrice < entryPrice;

        double pnl = won ? profitPayout : -1.0;

        return new TradeResult(
                entryBar.timestamp().toString(),
                signal.getStrategy(),
                signal.getType().name(),
                entryPrice,
                exitPrice,
                won,
                pnl
        );
    }

    // ═══════════════════════════════════════════════════════════════
    // Engine
    // ═══════════════════════════════════════════════════════════════

    /**
     * Constrói o {@link StrategyEngine} sem {@code MarketRegimeMonitor},
     * isolando o backtest de toda a infraestrutura de runtime.
     * Quando o profile estiver disponível, configura o {@link VolatilityFilter}
     * com os parâmetros do strategies.json para consistência com o runtime.
     *
     * @param engineMode modo de decisão a ser usado no engine
     * @return engine configurado para simulação
     */
    private StrategyEngine buildEngine(DecisionMode engineMode) {
        if (profile != null) {
            VolatilityFilter filter = new VolatilityFilter(
                    profile.getRangeLookback(),
                    profile.getRangeMultiplier()
            );
            return new StrategyEngine(
                    symbol, maxBars, strategies, engineMode, filter);
        }

        return new StrategyEngine(symbol, maxBars, strategies, engineMode);
    }

    /**
     * Alimenta o engine com os primeiros {@code maxBars} candles do histórico
     * para inicializar o estado interno antes do início da simulação.
     *
     * @param engine  engine a ser inicializado
     * @param allBars histórico completo de candles
     */
    private void seedEngine(StrategyEngine engine, List<Bar> allBars) {
        for (int i = 0; i < maxBars; i++) {
            engine.onBar(allBars.get(i));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Utilitários
    // ═══════════════════════════════════════════════════════════════

    /**
     * Verifica se o histórico possui candles suficientes para pelo menos uma simulação.
     * Exige {@code maxBars + tradeDurationBars} candles mínimos.
     *
     * @param allBars histórico de candles a ser verificado
     * @return {@code true} se há candles suficientes para iniciar a simulação
     */
    private boolean hasSufficientBars(List<Bar> allBars) {
        return allBars != null
                && allBars.size() >= maxBars + tradeDurationBars;
    }

    /**
     * Verifica se o sinal é operável para simulação.
     *
     * @param signal sinal avaliado
     * @return {@code true} se o sinal for não nulo e diferente de {@link Signal.Type#NONE}
     */
    private boolean isValidSignal(Signal signal) {
        return signal != null && signal.getType() != Signal.Type.NONE;
    }
}