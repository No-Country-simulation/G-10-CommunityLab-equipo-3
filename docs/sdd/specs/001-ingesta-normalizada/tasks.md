# Tasks 001: Ingesta Discord `#Listen` por ID vía bot JDA (Telegram deferrado)

- [x] **TASK-001-01**: Crear `domain` `entity/Comment{messageId nativo, channelId informativo}` + VO `MessageContent` + `Source, MessageType` (reservado 002). Validación en factory `Comment.create` (1..2000 chars + flag, ids/`sentTime`/`source` requeridos). `id=messageId` sin uuid; sin `batchId` en dominio (es `uuid` lote abierto Redis asignado en application, ver 004) ni `type` en 001 (lo clasifica la IA en 002).
  - *Derivado de:* `spec.md RF-02,RF-04,RF-05 + plan.md §1`
  - *Verificación:* `./mvnw test -Dtest=CommentTest`
- [x] **TASK-001-02**: Crear `application` `IngestDiscordCommand, ChannelMessage, ports/in/IngestUseCaseDiscord, services/IngestDiscordService` con `id=messageId + batchId=lote abierto vía BufferPort.getCurrentBatchId()` + retorno `<300ms` + disparo async a `002`.
  - *Derivado de:* `spec.md RF-01a,RF-04 + plan.md §1`
  - *Verificación:* `./mvnw test -Dtest=IngestDiscordServiceTest`
- [ ] **TASK-001-03**: Crear `infrastructure` `DiscordBotListener` (JDA, filtro `channelId == ${DISCORD_LISTEN_CHANNEL_ID}`, ignora bots/vacío/DMs/hilos/otros canales) + `DiscordMessageMapper` + config env `DISCORD_BOT_TOKEN,DISCORD_LISTEN_CHANNEL_ID` + fail-fast sin token (dependencia fundamental, plan §4) + acuse `✅/⚠️` sin PII. Sin rate-limiter.
  - *Derivado de:* `spec.md RF-01a,RF-03 + plan.md §1,§2,§4`
  - *Verificación:* `./mvnw test -Dtest=DiscordBotListenerTest,DiscordMessageMapperTest`
- [x] **TASK-001-04**: Añadir dependencia JDA + documentar `Decisión/Alternativa/Razón` (ya en `plan.md §2`). Sin `IngestController` ni DTOs HTTP de ingesta.
  - *Derivado de:* `spec.md RF-01 + plan.md §2`
  - *Verificación:* `./mvnw test` compila con JDA; `GET /actuator/health` UP
- [x] **TASK-001-05**: Configurar seguridad básica resto API (CORS allowlist, headers, CSRF off) + sin PII en logs.
  - *Derivado de:* `constitution Q3,R4-R5 + plan.md §4`
  - *Verificación:* `./mvnw test` + arranque verifica headers y ausencia de endpoints `/api/v1/ingest*`
- [x] **TASK-001-06**: Implementar buffer Redis append-only con reemplazo total del in-memory (`spring-data-redis` solo-Java `ListOps/SetOps`, keys `buffer:current:id/list/ids/bytes`, env `REDIS_HOST/PORT + REDIS_BUFFER_MAX_BYTES=921600`, degradado `REDIS_NOT_CONFIGURED`): `BufferPort` extendido (`getCurrentBatchId, append`), `RedisBufferAdapter` (`SADD messageId` dedup → `RPUSH` JSON `ChannelMessage` → `INCRBY bytes` len UTF-8), cableado en `IngestDiscordService` (normaliza → `batchId` → publish → `append`; blanco/inválido sin publish ni append, `p95<300ms`), eliminar `InMemoryBufferAdapter`. Solo append/acumulación; flush/export OCI queda en 004.
  - *Derivado de:* `constitution §2 Buffer + R6 + spec 004 RF-01/RF-02/RNF-01 + plan 004 §1/§2`
  - *Verificación:* `./mvnw test -Dtest=RedisBufferAdapterTest,IngestDiscordServiceTest` (mock `RedisTemplate`, sin Docker) + `./mvnw test`
