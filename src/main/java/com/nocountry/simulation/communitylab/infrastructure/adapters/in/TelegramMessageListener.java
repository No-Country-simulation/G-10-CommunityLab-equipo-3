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
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.telegram.telegrambots.meta.api.objects.message.Message;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnExpression("!'${telegram.bot.token:}'.isEmpty() && !'${telegram.bot.token:}'.startsWith('REPLACE_')")
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

        // Get message and value if not null
        if(!update.hasMessage()){
            log.debug("ingest discarded: reason=no-message");
            return;
        }

        if(!message.getChat().getId().equals(properties.listenGroupId())){
            log.debug("ingest discarded: reason=chat-not-allowed messageId={}", message.getMessageId());
            return;
        }

        if(message.getFrom() == null)
            return;

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
