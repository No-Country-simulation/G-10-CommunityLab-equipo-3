# Tasks 005: Ingesta Telegram long polling + dual SSE (reuso pipeline)

> Convención: rutas de *Implementación* relativas a `src/main/java/com/nocountry/simulation/communitylab/`.
> Trazabilidad estricta: sin `Derivado de:` se rechaza. Orden: `constitution > spec > plan > tasks`.

- [x] **TASK-005-01**: Crear `application` `IngestTelegramCommand, ports/in/IngestUseCaseTelegram, services/comment/telegram/IngestTelegramService` con `id=messageId nativo + batchId=lote abierto vía BufferPort.getCurrentBatchId()` + `ChannelMessage{source=TELEGRAM}` + publish `IngestAcceptedEvent` + retorno `<300ms` (espejo Discord, sin SDKs).
  - *Implementación:* `application/command/IngestTelegramCommand.java`, `application/port/in/IngestUseCaseTelegram.java`, `application/services/comment/telegram/IngestTelegramService.java`
  - *Derivado de:* `spec.md RF-01,RF-04 + plan.md §1`
  - *Verificación:* `./mvnw -Dtest=IngestTelegramServiceTest test`
- [x] **TASK-005-02**: Crear `infrastructure bot` `TelegramMessageListener` (long polling, filtros `from.isBot→descarta`, `chatId != ${TELEGRAM_LISTEN_GROUP_ID}→ignora`, `vacío→descarta`, `>2000→trunca+flag`) + `TelegramMessageMapper Update→Command` + `TelegramBotProperties` env `TELEGRAM_BOT_TOKEN,TELEGRAM_LISTEN_GROUP_ID` + fail-fast sin token/chatId (binding `long`) + sin PII en logs. *(Nombres reales: `TelegramMessageListener` en `adapters/in/`; fail-fast validado con tests.)*
  - *Implementación:* `infrastructure/adapters/in/TelegramMessageListener.java`, `infrastructure/adapters/in/mapper/TelegramMessageMapper.java`, `infrastructure/config/telegram/TelegramConfig.java`, `infrastructure/config/telegram/TelegramBotProperties.java`
  - *Derivado de:* `spec.md RF-01,RF-02,RF-03,RNF-02 + plan.md §1,§2,§5`
  - *Verificación:* `./mvnw -Dtest=TelegramMessageListenerTest,TelegramMessageMapperTest test`
- [x] **TASK-005-03**: Añadir dependencia `TelegramBots` + documentar `Decisión/Alternativa/Razón` long polling vs webhook (ya en `plan.md §2`). Sin `IngestController` ni DTOs HTTP de ingesta. *(Implementado con `telegrambots-springboot-longpolling-starter` + `telegrambots-client` 10.3.0.)*
  - *Implementación:* `pom.xml` (`org.telegram:telegrambots-springboot-longpolling-starter` + `telegrambots-client`, v10.3.0 — enmienda tabla cerrada cubierta por plan 005 §2)
  - *Derivado de:* `spec.md RF-01 + plan.md §2`
  - *Verificación:* `./mvnw test` compila con TelegramBots; `GET /actuator/health` UP
- [x] **TASK-005-04**: Crear `infrastructure web` `GetMessageProcessedTelegram` (`GET /api/v1/telegram/messages` SSE `asset.created/package.completed`, `Last-Event-ID` replay, Swagger ejemplo `ResponseClient{source=TELEGRAM}`) + cableado `SseEventPublisherAdapter` por fuente (routing por `post.source()`, sin mezclar Discord). Enmienda `v1.7-dual-get` ratificada en `constitution.md`. *(Nombre real: `GetMessageProcessedTelegram`; `package.completed` pendiente de pipeline OCI 004.)*
  - *Implementación:* `infrastructure/adapters/in/web/telegramMessage/GetMessageProcessedTelegram.java`
  - *Derivado de:* `spec.md RF-06,RF-07 + plan.md §1,§3`
  - *Verificación:* `./mvnw -Dtest=GetMessagesProcessedTelegramTest test`
- [x] **TASK-005-05**: Cablear fork existente para `source=TELEGRAM` (`EnrichmentListener → ResponseClient → SSE telegram + Redis append`) + verificar `LLM_FALLBACK→buffer sin SSE`, `inválido→solo LOG`. Sin cambios en `RedisBufferAdapter`/OCI (lote volátil → persistente). *(Reuso verificado: `EnrichmentListener` enruta por `source` vía `ResponseClient`; suite verde.)*
  - *Implementación:* wiring `application/services/ai/EnrichmentListener.java`, `infrastructure/adapters/out/buffer/RedisBufferAdapter.java` (reuso, sin cambio salvo tópico si aplica)
  - *Derivado de:* `spec.md RF-05,RF-06 + plan.md §1 + spec 004 RF-01/RF-06`
  - *Verificación:* `./mvnw -Dtest=EnrichmentListenerTest,RedisBufferAdapterTest test` + `./mvnw test`
- [ ] **TASK-005-06**: Verificación E2E local + seguridad básica (CORS allowlist, headers, sin PII logs, fail-fast sin token).
  - *Derivado de:* `constitution Q1-Q3,R4-R6 + plan.md §5,§6`
  - *Verificación:* `./mvnw test` verde + `./mvnw spring-boot:run` con token+chat prueba → Telegram normalizado `<300ms` + `asset.created` en `GET /api/v1/telegram/messages` <5s + `RPUSH` Redis; otro chat → ignorado
