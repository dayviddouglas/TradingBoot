package com.github.dayviddouglas.TradingBot.config.core;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Classe de mapeamento de propriedades externas da Deriv.
 *
 * Correção v5.3:
 * A arquitetura de autenticação da Deriv mudou de token direto via
 * WebSocket para OAuth 2.0 + OTP via REST. Os campos foram atualizados
 * para refletir o novo modelo:
 *
 * - token     → removido (substituído por accessToken)
 * - appId     → mantido como String (alfanumérico)
 * - accessToken → novo: token de acesso usado no header Authorization
 * - accountId   → novo: ID da conta usado no path do endpoint OTP
 * - historyCount → mantido
 *
 * Novo fluxo de conexão:
 * 1. REST POST /trading/v1/options/accounts/{accountId}/otp
 *    Header: Authorization: Bearer accessToken
 *    Header: Deriv-App-ID: appId
 * 2. Resposta retorna URL do WebSocket com OTP embutido
 * 3. Bot conecta na URL retornada — sem authorize adicional
 */
@Validated
@ConfigurationProperties(prefix = "deriv")
public class DerivProperties {

    /**
     * Identificador da aplicação registrada na plataforma Deriv.
     * Formato alfanumérico — enviado no header Deriv-App-ID nas
     * chamadas REST.
     */
    @NotBlank
    private String appId;

    /**
     * Token de acesso OAuth da conta Deriv.
     * Enviado no header Authorization: Bearer {accessToken}
     * nas chamadas REST para obter o OTP.
     *
     * ⚠️ Nunca versione este valor diretamente no código.
     * Use variáveis de ambiente: access-token: ${DERIV_ACCESS_TOKEN}
     */
    @NotBlank
    private String accessToken;

    /**
     * ID da conta Deriv usada pelo bot.
     * Enviado no path do endpoint OTP:
     * POST /trading/v1/options/accounts/{accountId}/otp
     *
     * Exemplo: DOT91022841 (conta demo)
     */
    @NotBlank
    private String accountId;

    /**
     * Quantidade de candles históricos a serem carregados por símbolo
     * durante a inicialização do sistema.
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