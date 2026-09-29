# 08. Conceptos Transversales

> Estado: **vigente**. Solo lo decidido en constitución/specs + implementado.

## Decidido e implementado (Fase 1)

- **Seguridad básica, sin auth**: `CorsConfig` (allowlist `cors.allowed.origins` por env, solo `GET,OPTIONS`, sin credenciales, expone `Last-Event-ID`, `maxAge 3600`, falla si `*`) + `SecurityConfig` (`health,info` públicos, resto `denyAll`, CSRF off por API stateless sin cookies, headers `nosniff`/`DENY` por defecto Spring Security). Límite 10 MB reservado a 004. Sin rate-limit en Fase 1: por alcance del MVP y por riesgo de pérdida de mensajes ante un `429`.
- **Buffer Redis** (ADR-010): `SADD messageId` dedup → `RPUSH` JSON → `INCRBY` bytes UTF-8; keys `buffer:current:id/list/ids/bytes`; `maxBatchSize` por env (`REDIS_BUFFER_MAX_BYTES=921600`); degradado `REDIS_NOT_CONFIGURED` sin tumbar ingesta ni SSE.
- **Persistencia**: inexistente salvo OCI Object Storage vía puerto (plan 004); objetos `<1MB`, `application/json`.
- **Resiliencia IA**: timeout 15 s + 1 reintento + fallback tipado, nunca `500` opaco (specs 002/003).
- **Observabilidad mínima**: Actuator `health,info` públicos; resto cerrado (spec 004). Logs en texto plano sin PII; prod solo `messageId/batchId/source` (sin `authorId`); local/test permite `authorId` a `DEBUG` para depurar filtros/mapper, nunca `authorName/contenido/tokens`. OCI sí conserva usuario para trazabilidad (anonimato solo ante el LLM). Logging JSON estructurado propuesto para Fase >1 (requiere dependencia nueva → enmienda).
- **Bots JDA + TelegramBots long polling**: tokens por env (custodia: devs backend), Discord intents `MESSAGE_CONTENT`/`GUILD_MESSAGES`, Telegram sin URL pública (webhook en Fase >1), reconexión automática, filtro anti-bot (ignorar), `>2000` truncado con flag, `type=OTRO` fijo, degradado `*_NOT_CONFIGURED` sin tumbar health. Telegram planificado, Discord primero.
- **Eventos SSE**: `GET /api/v1/discord/messages` (`text/event-stream`) con tipos `asset.created` / `package.completed`, reconexión por `Last-Event-ID` y mismo CORS allowlist que el resto (spec 004 RF-07).
- **Versionado API**: `v1` solo para endpoints GET. POSTs diferidos (requieren enmienda).
- **Configuración**: env `API_KEY_LLM_MISTRAL_DEV (+MODEL_MISTRAL, BASE_URL_MODEL_AI` requerido), `OCI_BUCKET`, `OCI_REGION`, `CORS_ALLOWED_ORIGINS`, `REDIS_HOST/PORT`, `REDIS_BUFFER_MAX_BYTES`, `DISCORD_BOT_TOKEN`, `DISCORD_LISTEN_CHANNEL_ID`, `TELEGRAM_BOT_TOKEN (+TELEGRAM_BOT_USERNAME)`; perfiles `application(-dev/-prod).yaml`, base sin credenciales. Contrato API OpenAI vendor-agnóstico (ej. actual Mistral vía `base-url`).

## Taxonomía de errores (propuesta desde specs, centralizar en `GlobalExceptionHandler`)

| `code` | HTTP | Cuándo |
|---|---|---|
| `VALIDATION_FAILED` | 400 problema | Input inválido resto API |
| `PAYLOAD_TOO_LARGE` | 413 | >10MB resto API (004) |
| `LLM_NOT_CONFIGURED` | 503 degradado | Sin `API_KEY_LLM_MISTRAL_DEV` o sin `BASE_URL_MODEL_AI` |
| `LLM_FALLBACK` | 207 parcial | 002 falla tras 15s + 1 reintento |
| `LLM_FALLBACK` | 207 parcial | análisis/003 falla, traza bufferizada, sin `500` |
| `HALLUCINATION_BLOCKED` | 207 parcial | Guard bloquea dato inventado |
| `REDIS_NOT_CONFIGURED` | degradado | Sin Redis (SSE vivo intacto) |
| `OCI_NOT_CONFIGURED` | degradado | Sin credenciales OCI |
| `DISCORD_TOKEN_MISSING` | fail-fast arranque | Sin `DISCORD_BOT_TOKEN` |
