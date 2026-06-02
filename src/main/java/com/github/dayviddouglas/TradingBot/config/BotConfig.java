package com.github.dayviddouglas.TradingBot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dayviddouglas.TradingBot.bot.MultiSymbolDerivBotRunner;
import com.github.dayviddouglas.TradingBot.deriv.DerivMarketDataService;
import com.github.dayviddouglas.TradingBot.deriv.DerivOtpService;
import com.github.dayviddouglas.TradingBot.deriv.DerivWsClient;
import com.github.dayviddouglas.TradingBot.deriv.ws.DerivHistoryPaginator;
import com.github.dayviddouglas.TradingBot.deriv.ws.DerivRequestSender;
import com.github.dayviddouglas.TradingBot.deriv.ws.TickHeartbeat;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Classe de configuração central do bot.
 *
 * Correção v5.3:
 * A URL do WebSocket agora é dinâmica — obtida via REST antes de cada
 * conexão através do DerivOtpService. O BotConfig orquestra essa cadeia:
 *
 * DerivOtpService → uriSupplier → DerivWsClient → DerivMarketDataService
 *
 * O DerivWsClient recebe um Supplier<URI> em vez de uma URI fixa,
 * garantindo que cada conexão (inicial e reconexões) use um OTP fresco.
 */
@Configuration
@EnableConfigurationProperties(DerivProperties.class)
public class BotConfig {

    /**
     * Cria o monitor de chegada de ticks como bean singleton.
     */
    @Bean
    public TickHeartbeat tickHeartbeat() {
        return new TickHeartbeat();
    }

    /**
     * Cria o ObjectMapper compartilhado.
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    /**
     * Cria o serviço de OTP responsável por obter a URL autenticada
     * do WebSocket via REST antes de cada conexão.
     *
     * @param props        propriedades da Deriv
     * @param objectMapper mapper compartilhado
     * @return instância de DerivOtpService
     */
    @Bean
    public DerivOtpService derivOtpService(
            DerivProperties props,
            ObjectMapper objectMapper
    ) {
        return new DerivOtpService(props, objectMapper);
    }

    /**
     * Cria o cliente WebSocket com URI dinâmica via OTP.
     *
     * Correção v5.3: em vez de URI fixa, recebe um Supplier<URI>
     * que chama DerivOtpService.fetchWsUri() antes de cada conexão.
     * Isso garante que reconexões automáticas sempre usem um OTP fresco.
     *
     * @param otpService    serviço que obtém OTP e retorna WS URI
     * @param tickHeartbeat monitor de ticks compartilhado
     * @return instância de DerivWsClient configurada
     */
    @Bean
    @Primary
    public DerivWsClient derivWsClient(
            DerivOtpService otpService,
            TickHeartbeat tickHeartbeat

    ) {
        return new DerivWsClient(otpService::fetchWsUri, tickHeartbeat);
    }

    @Bean
    public DerivWsClient derivWsClientSecond(MultiSymbolDerivBotRunner multiSymbolDerivBotRunner){
      return new DerivWsClient(multiSymbolDerivBotRunner);
    }

    /**
     * Cria o sender responsável por enviar requests e correlacionar
     * respostas via CompletableFuture indexadas por req_id.
     */
    @Bean
    public DerivRequestSender derivRequestSender(
            DerivWsClient wsClient,
            ObjectMapper objectMapper
    ) {
        return new DerivRequestSender(wsClient, objectMapper);
    }

    /**
     * Cria o paginador para histórico de candles.
     */
    @Bean
    public DerivHistoryPaginator derivHistoryPaginator(
            DerivRequestSender requestSender
    ) {
        return new DerivHistoryPaginator(requestSender);
    }

    /**
     * Cria o serviço de dados de mercado com todas as dependências.
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