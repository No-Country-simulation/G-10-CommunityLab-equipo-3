package com.nocountry.simulation.communitylab.infrastructure.adapters.in;

import com.nocountry.simulation.communitylab.application.command.IngestTelegramCommand;
import com.nocountry.simulation.communitylab.application.port.in.IngestUseCaseTelegram;
import com.nocountry.simulation.communitylab.infrastructure.adapters.in.mapper.TelegramMessageMapper;
import com.nocountry.simulation.communitylab.infrastructure.config.telegram.TelegramBotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;

@Service
@Slf4j
@RequiredArgsConstructor
public class TelegramMessageListener implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private final TelegramBotProperties properties;
    private final TelegramMessageMapper mapper;
    private final IngestUseCaseTelegram ingestUseCaseTelegram;

    @Override
    public String getBotToken() {
        return properties.token();
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return this;
    }

    @Override
    public void consume(Update update) {
        Message message = update.getMessage();

        if(!update.hasMessage()){
            log.debug("ingest discarded: reason=no-message");
            return;
        }

        User user = message.getFrom();

        if(user.getIsBot()){
            return;
        }

        if(message.getText() == null || message.getText().trim().isEmpty()){
            // Why ids only (Q3): no author/content in gateway logs.
            log.debug("ingest discarded: reason=non-text messageId={}", message.getMessageId());
            return;
        }

        IngestTelegramCommand command = mapper.toIngestTelegramCommand(message);

        // Why ids only (Q3): no author/content in gateway logs.
        log.debug("ingest received: messageId={} source=TELEGRAM", command.messageId());

        if (ingestUseCaseTelegram.ingest(command).isEmpty()) {
            log.debug("ingest rejected downstream: messageId={}", command.messageId());
        }
    }
}
