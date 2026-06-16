package com.github.dayviddouglas.TradingBoot.deriv.trade.monitor;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registro centralizado dos estados operacionais de trades, indexados por símbolo e por contrato.
 *
 * Mantém dois mapas complementares:
 * <ul>
 *   <li>{@code statesBySymbol}: garante que cada ativo tenha no máximo um {@link TradeState},
 *       criado sob demanda via {@link #getOrCreate}</li>
 *   <li>{@code statesByContractId}: mapeamento reverso que permite ao {@link TradeMonitor}
 *       localizar o estado de um ativo a partir do {@code contract_id} reportado
 *       pelo stream WebSocket ou pelo watchdog</li>
 * </ul>
 *
 * O mecanismo de processamento único de fechamento é garantido pelo método
 * {@link #claimClosedContract}, que remove o estado do mapa {@code statesByContractId}
 * de forma atômica via {@link ConcurrentHashMap#remove}. Apenas o primeiro chamador
 * (stream ou watchdog) recebe o estado; o segundo recebe {@code null} e descarta
 * o processamento, evitando duplicidade no relatório.
 *
 * Os métodos {@link #getStateByContractId} e {@link #isPendingClose} apenas consultam
 * o mapa sem remover o estado, sendo seguros para uso anterior à confirmação de fechamento.
 */
@Component
public class TradeStateRegistry {

    /**
     * Estado operacional indexado por símbolo.
     * Garante que cada ativo tenha no máximo uma operação em andamento.
     */
    private final Map<String, TradeState> statesBySymbol = new ConcurrentHashMap<>();

    /**
     * Mapeamento reverso: {@code contractId → TradeState}.
     * Necessário para localizar o estado quando stream ou watchdog
     * reportam o fechamento de um contrato pelo {@code contract_id}.
     */
    private final Map<Long, TradeState> statesByContractId = new ConcurrentHashMap<>();

    /**
     * Retorna o estado operacional do ativo, criando-o se ainda não existir.
     * O estado criado é reutilizado em todas as operações subsequentes do mesmo ativo.
     *
     * @param symbol símbolo do ativo
     * @return estado operacional existente ou recém-criado
     */
    public TradeState getOrCreate(String symbol) {
        return statesBySymbol.computeIfAbsent(symbol, TradeState::new);
    }

    /**
     * Registra a associação entre o contrato comprado e o estado do ativo.
     * Chamado pelo {@link com.github.dayviddouglas.TradingBoot.deriv.DerivTradeService}
     * imediatamente após buy bem-sucedido, habilitando a localização do estado
     * pelo {@code contractId} no stream e no watchdog.
     *
     * @param contractId ID do contrato retornado pela API após o buy
     * @param state      estado do ativo correspondente
     */
    public void registerContract(long contractId, TradeState state) {
        statesByContractId.put(contractId, state);
    }

    /**
     * Consulta o estado associado ao contrato sem removê-lo do registry.
     * Utilizado pelo {@link TradeMonitor} para capturar o {@code subscriptionId}
     * das mensagens de stream antes de confirmar {@code is_sold == 1},
     * sem interferir no mecanismo de claim atômico.
     *
     * @param contractId ID do contrato a ser consultado
     * @return estado do ativo ou {@code null} se não encontrado
     */
    public TradeState getStateByContractId(long contractId) {
        return statesByContractId.get(contractId);
    }

    /**
     * Remove e retorna atomicamente o estado associado ao contrato.
     * A remoção via {@link ConcurrentHashMap#remove} garante que apenas
     * um chamador — stream ou watchdog — processe o fechamento.
     * Quando retornar {@code null}, o fechamento já foi processado pelo outro.
     *
     * @param contractId ID do contrato fechado
     * @return estado do ativo reivindicado, ou {@code null} se já processado
     */
    public TradeState claimClosedContract(long contractId) {
        return statesByContractId.remove(contractId);
    }

    /**
     * Verifica se ainda existe estado registrado para o contrato,
     * sem removê-lo do registry. Utilizado pelo watchdog para verificar
     * se o stream já processou o fechamento antes de iniciar os polls.
     *
     * @param contractId ID do contrato a ser verificado
     * @return {@code true} se o contrato ainda aguarda processamento de fechamento
     */
    public boolean isPendingClose(long contractId) {
        return statesByContractId.containsKey(contractId);
    }
}