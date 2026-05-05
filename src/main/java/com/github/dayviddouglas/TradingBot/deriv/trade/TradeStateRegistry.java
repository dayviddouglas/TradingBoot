package com.github.dayviddouglas.TradingBot.deriv.trade;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registro centralizado dos estados operacionais por ativo e por contrato.
 *
 * Responsabilidades:
 * - Criar e armazenar TradeState por símbolo (um por ativo)
 * - Manter mapeamento reverso contractId → TradeState
 * - Garantir que stream e watchdog não processem o mesmo fechamento duas vezes
 *
 * Correção v5.1:
 * Adicionado método getStateByCon
 *
 * tractId() que consulta o state pelo
 * contractId sem removê-lo do registry. Necessário para que o TradeMonitor
 * possa capturar o subscriptionId das mensagens de stream antes de
 * confirmar is_sold == 1, sem interferir no mecanismo de claim atômico
 * que garante processamento único do fechamento.
 *
 * Diferença entre os métodos de acesso por contractId:
 * - getStateByContractId(): apenas consulta, não remove (leitura)
 * - claimClosedContract(): remove atomicamente (escrita exclusiva)
 * - isPendingClose(): verifica existência sem remover (leitura)
 */
@Component
public class TradeStateRegistry {

    /**
     * Estado operacional indexado por símbolo.
     * Garante que cada ativo tenha no máximo uma operação em andamento.
     */
    private final Map<String, TradeState> statesBySymbol = new ConcurrentHashMap<>();

    /**
     * Mapeamento reverso: contractId → TradeState.
     * Necessário para localizar o estado quando stream ou watchdog
     * reportam o fechamento de um contrato pelo contract_id.
     */
    private final Map<Long, TradeState> statesByContractId = new ConcurrentHashMap<>();

    /**
     * Retorna o estado do ativo, criando-o se não existir.
     *
     * @param symbol símbolo do ativo
     * @return estado operacional do ativo
     */
    public TradeState getOrCreate(String symbol) {
        return statesBySymbol.computeIfAbsent(symbol, TradeState::new);
    }

    /**
     * Registra a associação entre um contrato e o estado do ativo.
     * Chamado após buy bem-sucedido.
     *
     * @param contractId ID do contrato comprado
     * @param state      estado do ativo correspondente
     */
    public void registerContract(long contractId, TradeState state) {
        statesByContractId.put(contractId, state);
    }

    /**
     * Consulta o estado associado ao contrato sem removê-lo do registry.
     *
     * Usado pelo TradeMonitor para capturar o subscriptionId das mensagens
     * de stream antes de confirmar is_sold == 1. Diferente de
     * claimClosedContract(), este método não interfere no mecanismo
     * de claim atômico — o contrato permanece no registry após a consulta.
     *
     * @param contractId ID do contrato
     * @return estado do ativo ou null se não encontrado
     */
    public TradeState getStateByContractId(long contractId) {
        return statesByContractId.get(contractId);
    }

    /**
     * Remove e retorna o estado associado ao contrato.
     *
     * A remoção atômica via remove() garante que apenas uma fonte
     * (stream OU watchdog) processe o fechamento do contrato.
     * Se retornar null, outra fonte já processou.
     *
     * @param contractId ID do contrato fechado
     * @return estado do ativo ou null se já processado
     */
    public TradeState claimClosedContract(long contractId) {
        return statesByContractId.remove(contractId);
    }

    /**
     * Verifica se existe estado registrado para o contrato.
     * Usado pelo watchdog para verificar se o stream já resolveu.
     *
     * @param contractId ID do contrato
     * @return true se ainda aguarda processamento
     */
    public boolean isPendingClose(long contractId) {
        return statesByContractId.containsKey(contractId);
    }
}