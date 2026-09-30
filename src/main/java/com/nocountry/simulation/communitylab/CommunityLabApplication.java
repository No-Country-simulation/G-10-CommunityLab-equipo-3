package com.nocountry.simulation.communitylab;

import com.nocountry.simulation.communitylab.infrastructure.config.telegram.TelegramBotProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@EnableConfigurationProperties(TelegramBotProperties.class)
public class CommunityLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(CommunityLabApplication.class, args);
    }

}
