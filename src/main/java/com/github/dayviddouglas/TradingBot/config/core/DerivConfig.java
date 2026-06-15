package com.github.dayviddouglas.TradingBot.config.core;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuração auxiliar que garante o registro de {@link DerivProperties}
 * como propriedades mapeadas pelo Spring Boot.
 *
 * O registro principal de {@link DerivProperties} já é realizado em {@code BotConfig}.
 * Esta classe mantém o mapeamento ativo de forma independente, permitindo que
 * {@link DerivProperties} seja injetado em contextos onde {@code BotConfig}
 * não esteja presente.
 */
@Configuration
@EnableConfigurationProperties(DerivProperties.class)
public class DerivConfig {
}