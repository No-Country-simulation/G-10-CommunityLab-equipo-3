# 12. Glosario

> Fuente: specs 001–004 + constitución. Estado: **vigente**. Lenguaje ubicuo: no redefinir estos
> términos en otro sentido en código, specs ni ADRs.

| Término | Definición |
|---|---|
| Comentario | Mensaje individual normalizado: `{id, batchId, source, author, channel: nombre del canal/chat, type, text (truncado a 2000 si excede), truncated, timestamp}`. Entrada siempre `type=OTRO` (lo clasifica la IA). Unidad mínima del pipeline |
| BatchId | `uuid` del lote abierto en Redis (`buffer:current:id`). Contenedor de N mensajes: el lote se cierra por tamaño (`REDIS_BUFFER_MAX_BYTES=921600`, 900KB) y va a OCI como 1 objeto. La ingesta sigue siendo mensaje-por-mensaje |
| Fuente (Source) | Origen del dato. Valores Fase 1: `DISCORD`, `TELEGRAM` |
| Tipo de comentario | `TESTIMONIO` \| `LOGRO` \| `DUDA` \| `OTRO`. Entrada 001 siempre `OTRO`; 002 lo clasifica |
| JDA | Java Discord API: cliente Gateway del bot Discord en `infrastructure/`. Token por env, intents `MESSAGE_CONTENT`/`GUILD_MESSAGES`; se ignoran `author.isBot` |
| TelegramBots | Librería del bot Telegram en `infrastructure/` (long polling temporal MVP; webhook en Fase >1). Token/username por env; se ignoran `from.isBot`; `Update` → `Comentario` |
| ComentarioEnriquecido | `Comentario` + `{messageBatchId, messageAuthor, sentiment, topics[], relevance, language, flag?, channelPost/titlePost/outputContentProcessed/hashtags/cta}` interno+Redis (spec 002/003); el SSE expone `ResponseClient` |
| Relevancia | Puntuación 0–100 informativa del LLM, renormalizada por `RelevancePolicy`. Sin uso de filtrado (enmienda RF-06/003) |
| Sentimiento | `POSITIVO` \| `NEUTRAL` \| `NEGATIVO` (spec 002) |
| Activo | Borrador listo para publicar: `{id, sourceCommentIds[], channel, outputContentProcessed, hashtags?, cta?, promptVersion}` (spec 003) |
| messageAuthor | Texto del autor con curaduría mínima (conserva palabras, no publicable). Antes `contentProcessed/messageProcess` |
| outputContentProcessed | Redacción nueva lista para publicar, adaptada a canal. Antes `copy` |
| ResponseClient | DTO SSE (`messageId/messageBatchId/channelPost/messageAuthor/outputContentProcessed/...`). Redis guarda `EnrichedComment` |
| Canal (Channel) | Destino del activo. Valores Fase 1: `LINKEDIN` \| `X` \| `NEWSLETTER` \| `FAQ` |
| PaqueteDeActivos | `{batchId, generatedAt, source, assets[], stats, promptVersion, packageUrl?}` persistido como `paquete-{batchId}.json` en OCI (spec 004) |
| Salida estructurada (Structured Output) | JSON con schema fijo exigido al LLM; si falla tras 1 reintento se aplica fallback sin `500` |
| Puerto / Adaptador | Contrato en `application/` e implementación en `infrastructure/` (p. ej. `RequestToLLMProcess`, `OciObjectStorageAdapter`) |
| OCI Object Storage | Almacenamiento de objetos de Oracle Cloud (tier Always Free). Único storage persistente de Fase 1 |
| Spring AI | Abstracción (`ChatClient`) para invocar el LLM (OpenAI, modelo configurable por entorno) |
| Actuator | Módulo Spring Boot de observabilidad mínima; en Fase 1 solo `health,info` públicos |
| CSV | Formato **descartado** para ingesta (spec 001). Solo JSON |
| SDD | Spec-Driven Development: proceso del repo (`constitution → spec → plan → tasks`). Rige el proceso, no sustituye esta documentación |
