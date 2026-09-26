# Tasks 001: Ingesta Discord `#Listen` por ID vía bot JDA (Telegram deferrado)

- [ ] **TASK-001-01**: Crear `domain` `entity/Comment{messageId nativo, channelId informativo}` + VO `MessageContent` + `Source, MessageType` (reservado 002). Validación en factory `Comment.create` (1..2000 chars + flag, ids/`sentTime`/`source` requeridos). `id=messageId` sin uuid; sin `batchId` en dominio (es `uuid` lote abierto Redis asignado en application, ver 004) ni `type` en 001 (lo clasifica la IA en 002).
  - *Derivado de:* `spec.md RF-02,RF-04,RF-05 + plan.md §1`
  - *Verificación:* `./mvnw test -Dtest=CommentTest`
- [ ] **TASK-001-02**: Crear `application` `IngestDiscordCommand, ChannelMessage, ports/in/IngestUseCaseDiscord, services/IngestDiscordService` con `id=messageId + batchId=lote abierto vía BufferPort.getCurrentBatchId()` + retorno `<300ms` + disparo async a `002`.
  - *Derivado de:* `spec.md RF-01a,RF-04 + plan.md §1`
  - *Verificación:* `./mvnw test -Dtest=IngestDiscordServiceTest`
- [ ] **TASK-001-03**: Crear `infrastructure` `DiscordBotListener` (JDA, filtro `channelId == ${DISCORD_LISTEN_CHANNEL_ID}`, ignora bots/vacío/DMs/hilos/otros canales) + `DiscordMessageMapper` + config env `DISCORD_BOT_TOKEN,DISCORD_LISTEN_CHANNEL_ID` + fail-fast sin token (dependencia fundamental, plan §4) + acuse `✅/⚠️` sin PII. Sin rate-limiter.
  - *Derivado de:* `spec.md RF-01a,RF-03 + plan.md §1,§2,§4`
  - *Verificación:* `./mvnw test -Dtest=DiscordBotListenerTest,DiscordMessageMapperTest`
- [ ] **TASK-001-04**: Añadir dependencia JDA + documentar `Decisión/Alternativa/Razón` (ya en `plan.md §2`). Sin `IngestController` ni DTOs HTTP de ingesta.
  - *Derivado de:* `spec.md RF-01 + plan.md §2`
  - *Verificación:* `./mvnw test` compila con JDA; `GET /actuator/health` UP
- [ ] **TASK-001-05**: Configurar seguridad básica resto API (CORS allowlist, headers, CSRF off) + sin PII en logs.
  - *Derivado de:* `constitution Q3,R4-R5 + plan.md §4`
  - *Verificación:* `./mvnw test` + arranque verifica headers y ausencia de endpoints `/api/v1/ingest*`
