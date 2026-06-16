package com.github.dayviddouglas.TradingBoot.config.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dayviddouglas.TradingBoot.bot.MultiSymbolDerivBotRunner;
import com.github.dayviddouglas.TradingBoot.deriv.DerivMarketDataService;
import com.github.dayviddouglas.TradingBoot.deriv.DerivOtpService;
import com.github.dayviddouglas.TradingBoot.deriv.DerivWsClient;
import com.github.dayviddouglas.TradingBoot.deriv.ws.DerivHistoryPaginator;
import com.github.dayviddouglas.TradingBoot.deriv.ws.DerivRequestSender;
import com.github.dayviddouglas.TradingBoot.deriv.ws.TickHeartbeat;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Configuração central da infraestrutura do bot.
 *
 * Orquestra a cadeia de dependências necessária para estabelecer
 * a conexão WebSocket autenticada com a API da Deriv:
 *
 * <pre>
 * DerivOtpService → uriSupplier → DerivWsClient → DerivMarketDataService
 * </pre>
 *
 * O {@link DerivWsClient} primário recebe um {@code Supplier<URI>} que invoca
 * {@link DerivOtpService#fetchWsUri()} antes de cada conexão, garantindo que
 * tanto a conexão inicial quanto as reconexões automáticas utilizem sempre
 * um OTP válido e atualizado.
 *
 * Um segundo bean de {@link DerivWsClient} é registrado para viabilizar a
 * injeção do {@link MultiSymbolDerivBotRunner} no cliente WebSocket, permitindo
 * que reconexões bem-sucedidas acionem o fluxo de retomada do bot.
 */
@Configuration
@EnableConfigurationProperties(DerivProperties.class)
public class BotConfig {

    /**
     * Monitor de chegada de ticks compartilhado entre {@link DerivWsClient}
     * e {@link DerivMarketDataService}.
     *
     * @return instância singleton de {@link TickHeartbeat}
     */
    @Bean
    public TickHeartbeat tickHeartbeat() {
        return new TickHeartbeat();
    }

    /**
     * {@link ObjectMapper} compartilhado entre os componentes que
     * realizam serialização e desserialização de mensagens JSON.
     *
     * @return instância singleton de {@link ObjectMapper}
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    /**
     * Serviço responsável por obter o OTP via REST e retornar
     * a URI autenticada do WebSocket antes de cada conexão.
     *
     * @param props        propriedades da Deriv lidas do application.yml
     * @param objectMapper mapper compartilhado para desserialização da resposta REST
     * @return instância de {@link DerivOtpService}
     */
    @Bean
    public DerivOtpService derivOtpService(
            DerivProperties props,
            ObjectMapper objectMapper
    ) {
        return new DerivOtpService(props, objectMapper);
    }

    /**
     * Cliente WebSocket primário, configurado com URI dinâmica via OTP.
     *
     * O {@code Supplier<URI>} é resolvido apenas no momento da conexão,
     * nunca durante o startup do Spring, evitando chamadas REST prematuras.
     * Marcado como {@code @Primary} para ser injetado por padrão onde
     * {@link DerivWsClient} for requerido.
     *
     * @param otpService    serviço que fornece a URI autenticada do WebSocket
     * @param tickHeartbeat monitor de ticks compartilhado
     * @return instância principal de {@link DerivWsClient}
     */
    @Bean
    @Primary
    public DerivWsClient derivWsClient(
            DerivOtpService otpService,
            TickHeartbeat tickHeartbeat
    ) {
        return new DerivWsClient(otpService::fetchWsUri, tickHeartbeat);
    }

    /**
     * Cliente WebSocket secundário que recebe o {@link MultiSymbolDerivBotRunner}.
     *
     * Viabiliza a injeção do runner no {@link DerivWsClient}, permitindo que
     * reconexões bem-sucedidas acionem {@link MultiSymbolDerivBotRunner#reconnectionBot()}
     * para retomar o fluxo operacional do bot.
     *
     * @param multiSymbolDerivBotRunner orquestrador principal do bot
     * @return instância secundária de {@link DerivWsClient}
     */
    @Bean
    public DerivWsClient derivWsClientWithMultiSymbolDerivBotRunner(
            MultiSymbolDerivBotRunner multiSymbolDerivBotRunner
    ) {
        return new DerivWsClient(multiSymbolDerivBotRunner);
    }

    /**
     * Sender responsável por enviar requisições ao WebSocket e correlacionar
     * as respostas assíncronas via {@code CompletableFuture} indexadas por {@code req_id}.
     *
     * @param wsClient     cliente WebSocket primário
     * @param objectMapper mapper compartilhado para serialização de requisições
     * @return instância de {@link DerivRequestSender}
     */
    @Bean
    public DerivRequestSender derivRequestSender(
            DerivWsClient wsClient,
            ObjectMapper objectMapper
    ) {
        return new DerivRequestSender(wsClient, objectMapper);
    }

    /**
     * Paginador responsável por gerenciar o download do histórico de candles
     * com suporte a múltiplas páginas de requisição.
     *
     * @param requestSender sender utilizado para enviar as requisições paginadas
     * @return instância de {@link DerivHistoryPaginator}
     */
    @Bean
    public DerivHistoryPaginator derivHistoryPaginator(
            DerivRequestSender requestSender
    ) {
        return new DerivHistoryPaginator(requestSender);
    }

    /**
     * Serviço de dados de mercado que centraliza o recebimento de ticks,
     * o histórico de candles e o gerenciamento de subscrições por símbolo.
     *
     * @param wsClient        cliente WebSocket primário
     * @param requestSender   sender de requisições
     * @param historyPaginator paginador de histórico de candles
     * @param tickHeartbeat   monitor de chegada de ticks
     * @param objectMapper    mapper compartilhado para desserialização de mensagens
     * @return instância de {@link DerivMarketDataService}
     */
    @Bean
    public DerivMarketDataService derivMarketDataService(
            DerivWsClient wsClient,
            DerivRequestSender requestSender,
            DerivHistoryPaginator historyPaginator,
            TickHeartbeat tickHeartbeat,
            ObjectMapper objectMapper
    ) {
        return new DerivMarketDataService(
                wsClient,
                requestSender,
                historyPaginator,
                tickHeartbeat,
                objectMapper
        );
    }
}