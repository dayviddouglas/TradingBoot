package com.github.dayviddouglas.TradingBot.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Memória compartilhada de regimes confirmados por símbolo.
 *
 * Responsabilidade única: armazenar e fornecer acesso O(1) ao regime
 * atual confirmado de cada ativo, para consulta durante a execução de trades.
 *
 * O "confirmado" é a palavra-chave: este registry nunca armazena um
 * regime candidato ou provisório. Apenas regimes que passaram pelo
 * filtro de persistência do RegimeStateTracker (3 avaliações consecutivas)
 * são registrados aqui.
 *
 * Contexto arquitetural:
 * O registry existe para desacoplar o pipeline de classificação de regime
 * (MarketRegimeMonitor → RegimeStateTracker) do pipeline de execução
 * (DerivTradeService). O DerivTradeService não precisa conhecer o histórico
 * de classificações; ele apenas consulta o estado atual confirmado.
 *
 * Fundamentação científica:
 * A separação entre "regime detectado" e "regime confirmado" implementa
 * o conceito de smoothing de Hamilton [1989]: apenas transições persistentes
 * são tratadas como mudanças estruturais reais. Isso reduz a frequência
 * com que o sistema muda de comportamento em resposta a flutuações de curto prazo.
 *
 * Thread-safety:
 * ConcurrentHashMap garante operações atômicas sem sincronização explícita.
 * O padrão de acesso é: escritas pelo RegimeStateTracker (em virtual thread
 * do StrategyEngine) e leituras pelo DerivTradeService (em virtual thread
 * de execução). ConcurrentHashMap é suficiente para este padrão.
 *
 * A anotação @Component registra esta classe como bean singleton no
 * container IoC do Spring, garantindo que todos os componentes que
 * dependem dele recebam a mesma instância (estado compartilhado).
 */
@Component
public class RegimeRegistry {

    private static final Logger log = LoggerFactory.getLogger(RegimeRegistry.class);

    /**
     * Regime padrão retornado quando nenhuma classificação foi confirmada ainda.
     *
     * CHOPPY é o valor padrão conservador: na ausência de informação sobre
     * o regime, o sistema assume o pior caso (mercado sem estrutura definida),
     * o que evita que o DerivTradeService tome decisões com base em regime
     * desconhecido.
     */
    private static final MarketRegime DEFAULT_REGIME = MarketRegime.CHOPPY;

    /**
     * Mapa de regime confirmado por símbolo.
     *
     * Chave: símbolo do ativo (ex: "frxEURUSD")
     * Valor: último regime confirmado pelo RegimeStateTracker
     *
     * ConcurrentHashMap é usado em vez de HashMap + synchronized porque:
     * - As operações get() e put() são atômicas individualmente
     * - Não há operações compostas (check-then-act) que precisem de lock
     * - Oferece melhor throughput em cenário multi-ativo simultâneo
     */
    private final Map<String, MarketRegime> confirmedRegimeBySymbol =
            new ConcurrentHashMap<>();

    /**
     * Atualiza o regime confirmado de um símbolo.
     *
     * Chamado pelo RegimeStateTracker quando o filtro de persistência
     * confirma uma transição de regime. Não deve ser chamado com regimes
     * candidatos (não confirmados).
     *
     * @param symbol símbolo do ativo
     * @param regime novo regime confirmado
     */
    public void updateRegime(String symbol, MarketRegime regime) {
        MarketRegime previous = confirmedRegimeBySymbol.put(symbol, regime);

        // Loga apenas quando há mudança real de regime (não na primeira inicialização)
        if (previous != null && previous != regime) {
            log.info("REGIME REGISTRY UPDATED | symbol={} | {} → {}",
                    symbol, previous, regime);
        }
    }

    /**
     * Retorna o regime confirmado atual de um símbolo.
     *
     * Acesso O(1) via ConcurrentHashMap, adequado para uso no caminho
     * crítico do DerivTradeService durante a execução de trades.
     *
     * @param symbol símbolo do ativo
     * @return regime confirmado atual ou CHOPPY se nenhum ainda confirmado
     */
    public MarketRegime getRegime(String symbol) {
        return confirmedRegimeBySymbol.getOrDefault(symbol, DEFAULT_REGIME);
    }

    /**
     * Verifica se existe regime confirmado para o símbolo.
     *
     * Útil para distinguir entre "regime CHOPPY confirmado" e
     * "nenhuma classificação confirmada ainda" durante o período
     * de warm-up do sistema (primeiros candles após inicialização).
     *
     * @param symbol símbolo do ativo
     * @return true se pelo menos uma classificação foi confirmada
     */
    public boolean hasConfirmedRegime(String symbol) {
        return confirmedRegimeBySymbol.containsKey(symbol);
    }

    /**
     * Remove o regime de um símbolo (usado em testes ou reset de estado).
     *
     * @param symbol símbolo a remover
     */
    public void clearRegime(String symbol) {
        confirmedRegimeBySymbol.remove(symbol);
    }
}