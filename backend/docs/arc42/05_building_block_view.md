# 05. Vista de Bloques de Construcción

## Nivel 1 (previsto, según constitución R1)

```mermaid
flowchart TB
    subgraph APP [communityLab backend]
        INWEB[infrastructure/adapters/in/web<br/>Controllers, DTOs HTTP, ExceptionHandler]
        AP[application<br/>UseCases, Ports, Commands, DTOs]
        DM[domain<br/>POJOs: Comentario, Activo, PaqueteDeActivos]
        INOUT[infrastructure/adapters/out<br/>Spring AI, OCI SDK, Redis buffer, config, parsers]
    end
    INWEB --> AP --> DM
    INOUT -.implementa puertos de.-> AP
```

Adapters de entrada HTTP/JSON (vía `infrastructure/adapters/in/web`), puertos `IngestUseCaseDiscord` (in) y
`RequestToLLMProcess`, `BufferPort`, `EnrichedCommentFromAi`, `EventPublishPost` (out, vía `application`), SDKs externos (vía `infrastructure`).

## Vista de contenedores

![C3](docs/img/c3-diagram.png)

Notas de lectura: el "Frontend" es el dashboard público, único consumidor de la API en Fase 1;
"Admin Panel" es solo nombre, sin roles (CT-7). La flecha HTTPS con el proveedor de IA se lee
Backend→Proveedor (el backend invoca al LLM). La "suscripción persistente" Backend→Frontend es el
stream SSE `GET /api/v1/discord/messages` (ver §06, secuencia 5).

## Nivel 2 (implementado 001 + 002 + 003 fusionada + buffer + seguridad; 004 según plan)

001 ingesta: `DiscordMessageListener` (JDA, filtro `#Listen` por ID, anti-bot) → `DiscordConfig`
(token por env, fail-fast) → `IngestUseCaseDiscord` / `IngestDiscordService` → `Comment.create` +
`MessageContent` (trim, truncado 2000 + flag) → `BufferPort.getCurrentBatchId()` + `publish
IngestAcceptedEvent` (sin consumidor aún: 002 pendiente).
Buffer: `RedisBufferAdapter` (`SADD messageId` dedup → `RPUSH` JSON → `INCRBY` bytes UTF-8;
keys `buffer:current:id/list/ids/bytes`; `maxBatchSize` inyectado, flush en 004) + `RedisConfig`
(Lettuce + `RedisTemplate<String,String>`).
Seguridad: `SecurityConfig` (`health,info` públicos, resto `denyAll`, CSRF off) + `CorsConfig`
(allowlist por env, `GET,OPTIONS`, sin credenciales, expone `Last-Event-ID`).
002 (implementado): `EnrichmentListener` (orquesta por `IngestAcceptedEvent`), `RequestToLLMProcess` →
`AnalyzeMessageLlmAdapter` + `SystemPrompt` + `LlmOutputSanitizer`, `ConvertEnrichedCommentService`,
`RelevancePolicy`, `FailEnvModelConfiguration` (degradado `LLM_NOT_CONFIGURED`).
003 (implementado, fusionada en 002 single-LLM-call): post por canal en `ResponseModel`/`EnrichedComment`
(`channelPost/titlePost/outputContentProcessed/hashtags/cta`), reglas de canal en `SystemPrompt` (LINKEDIN/X/FAQ),
`HallucinationGuard`. SSE expone `ResponseClient(messageId/messageBatchId/channelPost/...)`, Redis guarda `EnrichedComment`.
004 (plan): `PackageRunUseCase`, `BufferService` (flush `≥900KB`), `OciObjectStorageAdapter`,
`GetMessagesProcessedDiscord` SSE + `SseEventPublisherAdapter`, `GlobalExceptionHandler`.
Nivel 3 solo si un componente lo justifica.
