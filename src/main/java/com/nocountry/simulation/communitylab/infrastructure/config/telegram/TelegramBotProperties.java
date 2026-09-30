package com.nocountry.simulation.communitylab.infrastructure.config.telegram;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "telegram.bot")
public record TelegramBotProperties(String token) {

}
