package com.nocountry.simulation.communitylab.infrastructure.config.telegram;

import com.nocountry.simulation.communitylab.infrastructure.exception.TelegramTokenMissingException;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "telegram.bot")
public record TelegramBotProperties(String token, long listenGroupId) {

    public TelegramBotProperties{
        if(token == null || token.isBlank())
            throw new TelegramTokenMissingException("Telegram bot token is missing");
    }
}
