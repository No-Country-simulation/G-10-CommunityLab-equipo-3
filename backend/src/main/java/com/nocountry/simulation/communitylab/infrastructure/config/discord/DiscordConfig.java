package com.nocountry.simulation.communitylab.infrastructure.config.discord;

import com.nocountry.simulation.communitylab.infrastructure.exception.DiscordTokenMissingException;
import com.nocountry.simulation.communitylab.infrastructure.adapters.in.DiscordMessageListener;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumSet;

@Configuration
public class DiscordConfig {

    @Bean(destroyMethod = "shutdown")
    public JDA jdaConfiguration(@Value("${discord.token:}") String token, DiscordMessageListener discordMessageListener) throws InterruptedException  {
        if(token == null || token.isEmpty()){
            throw new DiscordTokenMissingException("Discord token is required");
        }

        return JDABuilder
                // Deactivate all cache users
                .createLight(token, EnumSet.of(
                        GatewayIntent.GUILD_MESSAGES,
                        GatewayIntent.MESSAGE_CONTENT
                ))
                .addEventListeners(discordMessageListener)
                .build()
                .awaitReady();
    }
}
