package com.nocountry.simulation.communitylab;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import net.dv8tion.jda.api.JDA;
import org.telegram.telegrambots.longpolling.starter.TelegramBotInitializer;

@SpringBootTest
class CommunityLabApplicationTests {

    // Why: fail-fast creates the real JDA bean with the test token; mock it so
    // contextLoads never hits the Discord gateway.
    @MockitoBean
    private JDA jda;

    // Why: the Telegram long-polling starter registers the bot against the real
    // API on context startup; mock the initializer so tests stay offline.
    @MockitoBean
    private TelegramBotInitializer telegramBotInitializer;

    @Test
    void contextLoads() {
    }

}
