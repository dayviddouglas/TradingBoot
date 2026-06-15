package com.github.dayviddouglas.TradingBot.config.core;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Mapeamento das propriedades externas do bloco {@code deriv} definidas no {@code application.yml}.
 *
 * Centraliza as credenciais e parâmetros necessários para autenticação e
 * operação do bot com a API da Deriv. As propriedades são validadas no
 * startup da aplicação, garantindo falha rápida em caso de configuração incompleta.
 *
 * Fluxo de autenticação suportado por estas propriedades:
 * <ol>
 *   <li>REST POST {@code /trading/v1/options/accounts/{accountId}/otp}
 *       com {@code Authorization: Bearer {accessToken}} e {@code Deriv-App-ID: {appId}}.</li>
 *   <li>A resposta retorna a URL do WebSocket com OTP embutido.</li>
 *   <li>O bot conecta na URL retornada sem etapa adicional de autorização.</li>
 * </ol>
 */
@Validated
@ConfigurationProperties(prefix = "deriv")
public class DerivProperties {

    /**
     * Identificador da aplicação registrada na plataforma Deriv.
     * Enviado no header {@code Deriv-App-ID} nas chamadas REST para obtenção do OTP.
     * Formato alfanumérico.
     */
    @NotBlank
    private String appId;

    /**
     * Token de acesso pessoal (PAT) da conta Deriv.
     * Enviado no header {@code Authorization: Bearer {accessToken}}
     * nas chamadas REST para obtenção do OTP.
     */
    @NotBlank
    private String accessToken;

    /**
     * Identificador da conta Deriv operada pelo bot.
     * Compõe o path do endpoint REST de obtenção do OTP:
     * {@code /trading/v1/options/accounts/{accountId}/otp}.
     */
    @NotBlank
    private String accountId;

    /**
     * Quantidade de candles históricos carregados por símbolo
     * durante a inicialização do bot. Limitado entre 10 e 5000.
     */
    @Min(10)
    @Max(5000)
    private int historyCount = 200;

    // ═══════════════════════════════════════════════════════════════
    // Getters e Setters
    // ═══════════════════════════════════════════════════════════════

    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public int getHistoryCount() { return historyCount; }
    public void setHistoryCount(int historyCount) {
        this.historyCount = historyCount;
    }
}