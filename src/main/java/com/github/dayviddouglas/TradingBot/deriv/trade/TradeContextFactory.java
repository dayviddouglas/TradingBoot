package com.github.dayviddouglas.TradingBot.deriv.trade;

import com.github.dayviddouglas.TradingBot.config.strategy.StrategiesProfile;
import com.github.dayviddouglas.TradingBot.config.strategy.TradeConfig;
import com.github.dayviddouglas.TradingBot.model.Signal;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Factory responsável por construir o TradeContext a partir do
 * StrategiesProfile, do Signal final e do regime confirmado.
 *
 * Responsabilidades:
 * - Mapear Signal.Type → contractType da API Deriv (BUY→CALL, SELL→PUT)
 * - Extrair decisionStrategies do metadata do Signal
 * - Normalizar stake para 2 casas decimais
 * - Receber regime confirmado do RegimeRegistry via DerivTradeService (v5)
 * - Carregar minRoiPercent do TradeConfig para o TradeContext (v5.4)
 * - Montar TradeContext imutável pronto para execução
 *
 * Mudança v4 → v5:
 * O método create() recebe o regime como parâmetro String em vez de
 * extraí-lo do Signal.metadata, garantindo regime confirmado pelo
 * filtro de persistência de Hamilton [1989] em qualquer DecisionMode.
 *
 * Atualização v5.4:
 * O campo minRoiPercent é lido do TradeConfig e passado para o
 * TradeContext, implementando o SUG-02 — ROI mínimo configurável
 * por ativo via strategies.json em vez de hardcoded no TradeExecutor.
 *
 * ⚠️ SUG-01 pendente: extractDecisionStrategies() está duplicado no
 * DerivTradeService. Consolidar em SignalMetadataExtractor futuramente.
 */
@Component
public class TradeContextFactory {

    /**
     * Constrói o TradeContext a partir do profile, signal e regime confirmado.
     *
     * Atualização v5.4:
     * minRoiPercent é lido do TradeConfig e incluído no TradeContext,
     * permitindo que o TradeExecutor use o valor configurado por ativo
     * em vez de uma constante global.
     *
     * @param profile          configuração do ativo
     * @param signal           sinal final emitido pelo StrategyEngine
     * @param adjustedAmount   stake ajustado pelo AtrRiskManager
     * @param confirmedRegime  regime confirmado consultado do RegimeRegistry
     * @return TradeContext imutável pronto para execução
     */
    public TradeContext create(
            StrategiesProfile profile,
            Signal signal,
            double adjustedAmount,
            String confirmedRegime
    ) {
        TradeConfig trade = profile.getTrade();

        String       contractType        = mapToContractType(signal.getType());
        double       normalizedStake     = normalizeStake(adjustedAmount);
        List<String> decisionStrategies  = extractDecisionStrategies(signal);
        String       decisionMode        = extractMetadataString(signal, "decisionMode");

        String regime = (confirmedRegime != null && !confirmedRegime.isBlank())
                ? confirmedRegime
                : "";

        return new TradeContext(
                profile.getSymbol(),
                contractType,
                normalizedStake,
                trade.getCurrency(),
                trade.getDuration(),
                trade.getDurationUnit(),
                decisionMode,
                signal.getStrategy(),
                decisionStrategies,
                regime,
                signal.getType(),
                trade.getMinRoiPercent()   // ← v5.4: lido do TradeConfig
        );
    }

    /**
     * Sobrecarga retrocompatível sem regime externo.
     *
     * Mantida para uso no backtest e testes unitários onde o
     * RegimeRegistry não está disponível. Extrai o regime do
     * Signal.metadata como antes.
     *
     * ⚠️ No runtime, usar sempre o método create() com confirmedRegime.
     *
     * @param profile        configuração do ativo
     * @param signal         sinal final emitido pelo StrategyEngine
     * @param adjustedAmount stake ajustado pelo AtrRiskManager
     * @return TradeContext imutável pronto para execução
     */
    public TradeContext create(
            StrategiesProfile profile,
            Signal signal,
            double adjustedAmount
    ) {
        String regimeFromMetadata =
                extractMetadataString(signal, "regime");
        return create(profile, signal, adjustedAmount, regimeFromMetadata);
    }

    // ═══════════════════════════════════════════════════════════════
    // Mapeamento de tipos
    // ═══════════════════════════════════════════════════════════════

    /**
     * Converte tipo de sinal para tipo de contrato da API Deriv.
     *
     * BUY  → CALL (aposta na alta)
     * SELL → PUT  (aposta na baixa)
     *
     * @param signalType tipo do sinal
     * @return tipo de contrato correspondente
     * @throws IllegalArgumentException se o sinal for NONE
     */
    private String mapToContractType(Signal.Type signalType) {
        return switch (signalType) {
            case BUY  -> "CALL";
            case SELL -> "PUT";
            default   -> throw new IllegalArgumentException(
                    "Cannot map signal type to contract: " + signalType);
        };
    }

    // ═══════════════════════════════════════════════════════════════
    // Normalização de stake
    // ═══════════════════════════════════════════════════════════════

    /**
     * Normaliza o stake para 2 casas decimais.
     *
     * A API Deriv rejeita stakes com mais de 2 casas decimais.
     * Garante também stake mínimo positivo de 0.01.
     *
     * @param amount valor original do stake
     * @return stake normalizado
     */
    private double normalizeStake(double amount) {
        if (!Double.isFinite(amount) || amount <= 0.0) {
            return 0.0;
        }

        double rounded = Math.round(amount * 100.0) / 100.0;

        if (rounded > 0.0 && rounded < 0.01) {
            return 0.01;
        }

        return rounded;
    }

    // ═══════════════════════════════════════════════════════════════
    // Extração de metadata
    // ═══════════════════════════════════════════════════════════════

    /**
     * Extrai a lista de estratégias decisoras do metadata do Signal.
     *
     * ⚠️ SUG-01 pendente: duplicado no DerivTradeService.
     * Consolidar em SignalMetadataExtractor em evolução futura.
     *
     * @param signal sinal com metadata
     * @return lista de nomes de estratégias ou lista vazia
     */
    @SuppressWarnings("unchecked")
    private List<String> extractDecisionStrategies(Signal signal) {
        if (signal.getMetadata() == null) return List.of();

        Object raw = signal.getMetadata().get("decisionStrategies");
        if (raw instanceof List<?> list) {
            return list.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .toList();
        }

        return List.of();
    }

    /**
     * Extrai um valor String do metadata do Signal com fallback
     * para string vazia.
     *
     * @param signal signal com metadata
     * @param key    chave do campo desejado
     * @return valor como String ou string vazia se ausente
     */
    private String extractMetadataString(Signal signal, String key) {
        if (signal.getMetadata() == null) return "";
        Object value = signal.getMetadata().get(key);
        return value != null ? value.toString() : "";
    }
}