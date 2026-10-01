# Especificación 005: Ingesta Telegram vía long polling + reuso pipeline

> Respeta: `constitution.md` v1.7-dual-get (P1-P6, R1-R8, Q1-Q5, Fase 1). Activa `001 RF-01b` deferrado. Solo QUÉ y POR QUÉ, sin decisiones de código. Sin endpoint REST de ingesta: el backend es el bot Telegram (long polling).

## 1. Problema y Objetivo

En comunidades tech (Discord, Telegram) se generan testimonios, logros y dudas que se pierden en el historial del chat. El equipo de Community Management las cura a mano.

Objetivo 005: escuchar los mensajes de Telegram —el backend es el bot—, validar cada mensaje y normalizarlo a `Comment{source=TELEGRAM}` listo para el mismo pipeline existente (`002 análisis IA → 003 generación → fork SSE inmediato + buffer Redis por tamaño → OCI batch + frontend 004`). Cada mensaje se ingiere individualmente según llega (`id=messageId` nativo Telegram, `batchId=lote abierto en Redis`); el paquete OCI se cierra por tamaño (`N mensajes = 1 paquete`). Redis se comporta como almacenamiento de lote (buffer volátil actual: `LIST+SET+bytes`); Object Storage sigue siendo la única fuente de almacenamiento persistente de esos lotes. Sin este paso no hay paridad Discord→Telegram. La salida al cliente usa el mismo formato `ResponseClient` actual.

## 2. Requerimientos Funcionales (RF)

- **RF-01 — Ingesta vía bot Telegram long polling:** el sistema debe escuchar mensajes Telegram visibles con long polling y convertir cada mensaje en `Comment`. Sin endpoint REST de ingesta. Mensajes de otros bots o fuera del `chatId` permitido se ignoran en silencio.
  - *Criterio de Aceptación:* **Dado** un mensaje válido con contenido completo, **Cuando** el bot lo recibe, **Entonces** lo normaliza a `Comment` con `{id=messageId nativo, batchId=lote abierto Redis}` en <300ms p95 local (sin LLM) y dispara `002` en background. **Dado** un mensaje de otro bot o fuera del chat permitido, **Cuando** llega, **Entonces** se ignora sin procesar ni logs PII.
- **RF-02 — Validación estricta:** autor, chat (`channelId=chatId` + filtro duro por allowlist `${TELEGRAM_LISTEN_CHAT_ID}`), texto (1..2000 chars; >2000 se trunca con `truncated:true` uniforme con Discord) y timestamp del `Update` son obligatorios; `source` siempre `TELEGRAM`.
  - *Criterio:* **Dado** un mensaje sin texto o vacío, **Cuando** llega, **Entonces** se descarta sin procesar y sin exponer PII en logs. **Dado** un texto >2000 chars, **Cuando** llega, **Entonces** se trunca a 2000 y se marca `truncated:true`. El `type` de salida de 005 es siempre `OTRO`; la IA lo clasifica en spec 002.
- **RF-03 — Límites anti-abuso MVP:** texto máximo 2000 chars por mensaje (truncado uniforme con `truncated:true`). Sin rate-limit en backend.
  - *Criterio:* **Dado** un mensaje vacío, **Cuando** llega, **Entonces** se descarta sin procesar. **Dado** un texto >2000 chars, **Cuando** llega, **Entonces** se trunca a 2000 con `truncated:true`.
  - *Por qué no hay rate-limit:* misma razón que `001 RF-03`: alcance MVP + riesgo de pérdida por `429` sin reintentos garantizados del lado app.
- **RF-04 — Normalización:** trimea espacios, normaliza timestamp a UTC ISO-8601, usa `id=messageId` nativo Telegram (`String`, único, sin uuid por mensaje) y `batchId=uuid` del lote abierto en Redis (`GET buffer:current:id`).
  - *Criterio:* **Dado** `"  hola  "` y `2026-09-18T10:00:00-05:00`, **Cuando** se ingiere, **Entonces** el `Comment` interno queda `text="hola"`, `timestamp=2026-09-18T15:00:00Z`.
- **RF-05 — Reuso pipeline 002+003 con mismo `ResponseClient`:** cada `Comment{source=TELEGRAM}` pasa por el mismo análisis IA y generación multicanal existente, con idéntico formato de salida `ResponseClient` actual (mismos campos `messageId/messageBatchId/channelPost/...`).
  - *Criterio:* **Dado** un logro Telegram "primer empleo Java", **Cuando** termina `003`, **Entonces** existe `ResponseClient{source=TELEGRAM, channelPost=LINKEDIN, outputContentProcessed 80..600, hashtags[2..5]}` válido contra el mismo schema que Discord.
- **RF-06 — Fork post-LLM + buffer lote Redis:** tras `003` hay fork en paralelo: `A) SSE inmediato asset.created` con `ResponseClient` + `B) RPUSH buffer Redis` con `EnrichedComment` interno para OCI batch (flush automático por tamaño, sin disparo manual). Redis actúa como almacenamiento de lote volátil; OCI es el único persistente. Si `002` da `LLM_FALLBACK` → se guarda en buffer con `flag=LLM_FALLBACK` y `relevance=0` (auditoría) sin SSE; si el post es inválido → descarte sin SSE ni buffer/OCI, solo `LOG`.
  - *Criterio:* **Dado** un mensaje válido procesado, **Cuando** termina `003`, **Entonces** hay `asset.created {persisted:false}` en <5s + `RPUSH` a Redis; **Cuando** el buffer alcanza `REDIS_BUFFER_MAX_BYTES`, **Entonces** hay guardado OCI batch + `package.completed`.
- **RF-07 — Retorno al cliente por SSE Telegram:** `GET /api/v1/telegram/messages` (`text/event-stream`) emite `asset.created` inmediato con `ResponseClient` plano (`id:` header = `messageId`) y `package.completed` con lo guardado en OCI, con reconexión por `Last-Event-ID`. Enmienda ratificada: `v1.7-dual-get` (ver plan 005 §2).
  - *Criterio:* **Dado** un dashboard suscrito, **Cuando** se procesa un mensaje Telegram, **Entonces** recibe `asset.created` en <5s; **Cuando** se guarda un paquete batch, **Entonces** recibe `package.completed`; **Dado** reconexión con `Last-Event-ID`, **Entonces** no pierde eventos ya emitidos (ventana replay en memoria).

## 3. Requerimientos No Funcionales (RNF)

- **RNF-01:** ingesta sin LLM `p95 < 300ms` local por mensaje individual; arranque local `<15s` (handshake Telegram excluido, igual que JDA/LLM).
- **RNF-02:** sin autenticación en Fase 1; el bot se autentica con token por env (`TELEGRAM_BOT_TOKEN` + opcional `TELEGRAM_BOT_USERNAME` + filtro `TELEGRAM_LISTEN_CHAT_ID`), nunca en repo. Seguridad básica resto API: CORS allowlist + validación + límite tamaño (Q3).
- **RNF-03:** errores siempre JSON problema (`code, message, errors[]`), nunca stacktrace ni PII en logs. Logs prod solo `messageId/batchId/source` (sin `authorId`); en local/test se permite `authorId` a `DEBUG`, nunca `authorName/contenido/tokens`.
- **RNF-04:** objeto OCI `<1MB`, buffer Redis cierra por `REDIS_BUFFER_MAX_BYTES=921600 (900KB)`.

## 4. Modelo de Dominio y Glosario

- **Comment:** `{id=messageId nativo Telegram, batchId=lote abierto Redis (asignado en application, no en dominio), source: TELEGRAM, author, channel: chatId filtrado, type: OTRO a la entrada, text (truncado a 2000 si excede), truncated: bool, timestamp del Update}`. `Source.TELEGRAM` ya existe en dominio.
- **BatchId:** `uuid` del lote abierto en Redis (`buffer:current:id`) que agrupa N mensajes por tamaño y luego va a OCI `paquetes/paquete-{batchId}.json` (004 RF-01). No es 1:1 por mensaje. Redis es almacenamiento de lote volátil; OCI es persistencia.
- **ResponseClient:** contrato SSE público idéntico a Discord (`authorName, messageAuthor, messageId, messageBatchId, sentiment, language, messageType, topics, relevance, flag, sentTime, source=TELEGRAM, channelPost, titlePost, outputContentProcessed, hashtags, cta`).
- Ejemplo Telegram (no es REST):
  ```text
  Telegram Update → chatId==env, from="Ana_dev", text="Conseguí mi primer empleo Java!" → Comment{source=TELEGRAM, type=OTRO, truncated=false} → 002+003 → ResponseClient{source=TELEGRAM, channelPost=LINKEDIN} → SSE + Redis
  ```

## 5. Fuera de Alcance (Out of Scope)

- Webhook Telegram, endpoints REST de ingesta (`POST /api/v1/ingest*`): eliminados, la entrada es vía bot long polling.
- `GET /packages*` (sin recuperación persistida; frontend solo recibe el vivo), polling/CSV entrantes.
- Rate-limit por fuente + `429` (fuera de Fase 1).
- Auth, usuarios, roles, DB relacional (Fase >1).
- Publicación automática a LinkedIn/X, responder/diagnosticar dudas (003 RF-04 prohibido responder).
- Soporte `SLACK`, XML, Excel.
