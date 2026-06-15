package com.github.dayviddouglas.TradingBot.deriv.trade.context;

import com.github.dayviddouglas.TradingBot.config.strategy.StrategiesProfile;
import com.github.dayviddouglas.TradingBot.config.strategy.TradeConfig;
import com.github.dayviddouglas.TradingBot.model.Signal;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Responsável por construir o {@link TradeContext} a partir do {@link StrategiesProfile},
 * do {@link Signal} final e do regime de mercado confirmado.
 *
 * Concentra as seguintes responsabilidades de preparação para execução:
 * - Mapear {@link Signal.Type} para o tipo de contrato da API Deriv ({@code BUY→CALL}, {@code SELL→PUT})
 * - Extrair {@code decisionStrategies} do metadata do {@link Signal}
 * - Normalizar o stake para 2 casas decimais, respeitando a restrição da API Deriv
 * - Receber o regime confirmado do {@code RegimeRegistry} via {@link com.github.dayviddouglas.TradingBot.deriv.DerivTradeService}
 * - Carregar {@code minRoiPercent} do {@link TradeConfig} para o {@link TradeContext}
 * - Montar o {@link TradeContext} imutável pronto para o {@code TradeExecutor}
 *
 * Disponibiliza dois métodos {@code create()}: o principal recebe o regime como parâmetro
 * externo (regime confirmado pelo filtro de persistência, disponível em qualquer
 * {@code DecisionMode}); a sobrecarga retrocompatível extrai o regime do
 * {@code Signal.metadata} e é mantida para uso em contextos sem {@code RegimeRegistry}.
 *
 * O campo {@code minRoiPercent} é lido do {@link TradeConfig} e encaminhado ao
 * {@link TradeContext}, permitindo que o {@code TradeExecutor} aplique o limiar
 * configurado por ativo em vez de um valor fixo global.
 */
@Component
public class TradeContextFactory {

    /**
     * Constrói o {@link TradeContext} a partir do profile, signal e regime confirmado externamente.
     * Este é o método utilizado pelo runtime, onde o regime é consultado do {@code RegimeRegistry}
     * antes da criação do contexto.
     *
     * @param profile          configuração do ativo lida do strategies.json
     * @param signal           sinal final emitido pelo {@code StrategyEngine}
     * @param adjustedAmount   stake calculado pelo {@code AtrRiskManager}
     * @param confirmedRegime  regime confirmado consultado do {@code RegimeRegistry}
     * @return {@link TradeContext} imutável pronto para execução
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

        // Usa o regime confirmado externamente; string vazia como fallback se ausente
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
                trade.getMinRoiPercent()
        );
    }

    /**
     * Sobrecarga retrocompatível sem regime externo.
     * Extrai o regime diretamente do {@code Signal.metadata}, comportamento equivalente
     * ao fluxo anterior à integração com o {@code RegimeRegistry}.
     *
     * Utilizada em contextos onde o {@code RegimeRegistry} não está disponível,
     * como backtest e testes unitários. No runtime, o método principal com
     * {@code confirmedRegime} deve ser sempre utilizado.
     *
     * @param profile        configuração do ativo lida do strategies.json
     * @param signal         sinal final emitido pelo {@code StrategyEngine}
     * @param adjustedAmount stake calculado pelo {@code AtrRiskManager}
     * @return {@link TradeContext} imutável pronto para execução
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
     * Converte o tipo de sinal para o tipo de contrato aceito pela API Deriv.
     * {@code BUY} representa aposta na alta do ativo ({@code CALL}).
     * {@code SELL} representa aposta na baixa do ativo ({@code PUT}).
     *
     * @param signalType tipo do sinal gerado pelo engine
     * @return tipo de contrato correspondente para envio à API
     * @throws IllegalArgumentException se o tipo de sinal for {@code NONE}
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
     * Normaliza o stake para 2 casas decimais, respeitando a restrição da API Deriv
     * que rejeita stakes com mais de 2 casas decimais.
     * Garante stake mínimo positivo de {@code 0.01} para valores arredondados
     * que resultem em zero por truncamento.
     * Retorna {@code 0.0} para valores inválidos, infinitos ou negativos.
     *
     * @param amount valor original do stake
     * @return stake normalizado com até 2 casas decimais
     */
    private double normalizeStake(double amount) {
        if (!Double.isFinite(amount) || amount <= 0.0) {
            return 0.0;
        }

        double rounded = Math.round(amount * 100.0) / 100.0;

        // Evita stake zero por arredondamento quando o valor original era positivo
        if (rounded > 0.0 && rounded < 0.01) {
            return 0.01;
        }

        return rounded;
    }

    // ═══════════════════════════════════════════════════════════════
    // Extração de metadata
    // ═══════════════════════════════════════════════════════════════

    /**
     * Extrai a lista de estratégias que participaram da decisão do metadata do {@link Signal}.
     * Converte cada elemento para {@code String}, filtrando valores nulos.
     * Retorna lista vazia quando o metadata for nulo ou o campo estiver ausente.
     *
     * @param signal sinal com metadata populado pelo {@code SignalEmitter}
     * @return lista de nomes das estratégias decisoras ou lista vazia
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
     * Extrai um valor {@code String} do metadata do {@link Signal} pela chave informada.
     * Retorna string vazia quando o metadata for nulo ou a chave estiver ausente.
     *
     * @param signal sinal com metadata populado pelo {@code SignalEmitter}
     * @param key    nome do campo a ser extraído do metadata
     * @return valor como {@code String} ou string vazia se ausente
     */
    private String extractMetadataString(Signal signal, String key) {
        if (signal.getMetadata() == null) return "";
        Object value = signal.getMetadata().get(key);
        return value != null ? value.toString() : "";
    }
}