package com.github.dayviddouglas.TradingBot.tools.recovery;

/**
 * Representa as informações extraídas do shortcode de um contrato da Deriv.
 *
 * O shortcode é um código compacto gerado pela Deriv que contém os
 * parâmetros do contrato em formato condensado. Exemplo:
 *
 * "CALL_frxEURUSD_10_1714000000_1714000900_S0P_0"
 *
 * Estrutura do shortcode por posição (separado por "_"):
 * - Posição 0: tipo do contrato (CALL, PUT, DIGITDIFF, MULTUP, etc.)
 * - Posição 1: símbolo do ativo (frxEURUSD, frxXAUUSD, etc.)
 * - Posição 2: stake (valor apostado)
 * - Posição 3: purchase_time (epoch seconds da compra)
 * - Posição 4: sell_time (epoch seconds do vencimento)
 * - Posição 5+: parâmetros adicionais (barreira, etc.)
 *
 * Mapeamento de tipo para signalType interno do bot:
 * - CALL → BUY  (aposta na alta — Rise/Fall)
 * - PUT  → SELL (aposta na baixa — Rise/Fall)
 * - Outros tipos → retornados sem mapeamento
 *
 * ⚠️ Ponto de atenção: o bot opera apenas contratos CALL/PUT (Rise/Fall).
 * Outros tipos de contrato (DIGITDIFF, MULTUP, etc.) podem aparecer no
 * profit_table se a conta tiver sido usada para outros produtos.
 * Nesses casos, o signalType retornará o tipo original da Deriv.
 *
 * @param symbol     símbolo do ativo extraído do shortcode
 * @param signalType direção mapeada para o padrão interno: BUY ou SELL
 */
public record ShortcodeInfo(
        String symbol,
        String signalType
) {

    /**
     * Parseia um shortcode da Deriv e retorna as informações extraídas.
     *
     * Retorna um ShortcodeInfo com valores "UNKNOWN" quando o shortcode
     * é nulo, vazio ou não tem o formato esperado. Isso evita exceções
     * durante o processamento em lote do profit_table, onde alguns
     * contratos podem ter shortcodes em formato diferente.
     *
     * @param shortcode código compacto do contrato (ex: "CALL_frxEURUSD_...")
     * @return ShortcodeInfo com símbolo e signalType extraídos
     */
    public static ShortcodeInfo parse(String shortcode) {
        if (shortcode == null || shortcode.isBlank()) {
            return new ShortcodeInfo("UNKNOWN", "UNKNOWN");
        }

        String[] parts = shortcode.split("_");

        // Posição 0: tipo do contrato
        String contractType = parts.length > 0 ? parts[0] : "UNKNOWN";

        // Posição 1: símbolo do ativo
        String symbol = parts.length > 1 ? parts[1] : "UNKNOWN";

        // Mapeia o tipo da Deriv para o signalType interno do bot
        String signalType = switch (contractType) {
            case "CALL" -> "BUY";
            case "PUT"  -> "SELL";
            default     -> contractType;
        };

        return new ShortcodeInfo(symbol, signalType);
    }

    /**
     * Verifica se o contrato é do tipo suportado pelo bot (CALL/PUT).
     *
     * Útil para filtrar contratos de outros produtos que possam
     * aparecer no profit_table da conta.
     *
     * @return true se o signalType é BUY ou SELL
     */
    public boolean isSupportedType() {
        return "BUY".equals(signalType) || "SELL".equals(signalType);
    }
}