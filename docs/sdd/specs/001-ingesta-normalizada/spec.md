# Especificación 001: Ingesta normalizada vía bots Discord JDA + Telegram TelegramBots

> Respeta: `constitution.md` v1.1-bots (P1-P6, R1-R8, Q1-Q5, Fase 1). Sin JPA, sin auth. Solo QUÉ y POR QUÉ, sin decisiones técnicas de código. Sin endpoint REST de ingesta: el backend es ambos bots.

## 1. Problema y Objetivo

En comunidades tech (Discord, Telegram) se generan a diario testimonios, logros y dudas valiosas que se pierden en el historial del chat. El equipo de Community Management las cura a mano.

Objetivo 001: escuchar los mensajes de Discord (bot JDA) y Telegram (bot TelegramBots long polling) —el backend es ambos bots—, validar cada mensaje y normalizarlo a `Comentario` listo para análisis IA (spec 002). Cada mensaje se procesa individualmente según llega; no hay endpoint REST de ingesta ni se reciben lotes. Sin este paso no hay pipeline.

## 2. Requerimientos Funcionales (RF)

- **RF-01a — Ingesta vía bot JDA (Discord):** el sistema debe escuchar mensajes Discord con JDA (intents `MESSAGE_CONTENT`/`GUILD_MESSAGES`) y convertir cada mensaje en `Comentario`. Sin endpoint REST.
  - *Criterio de Aceptación:* **Dado** un mensaje válido en un canal Discord visible con contenido completo, **Cuando** el bot lo recibe, **Entonces** lo normaliza a `Comentario` con `{id, batchId}` en <300ms p95 local (sin LLM). **Dado** un mensaje de otro bot, **Cuando** llega, **Entonces** se ignora sin procesar.
- **RF-01b — Ingesta vía bot TelegramBots long polling (Telegram):** el sistema debe escuchar mensajes Telegram con TelegramBots long polling (sin URL pública; webhook en Fase >1) y convertir cada mensaje en `Comentario`. Sin endpoint REST.
  - *Criterio de Aceptación:* **Dado** un mensaje válido en un chat Telegram visible con contenido completo, **Cuando** el bot lo recibe, **Entonces** lo normaliza a `Comentario` con `{id, batchId}` en <300ms p95 local (sin LLM). **Dado** un mensaje de otro bot, **Cuando** llega, **Entonces** se ignora sin procesar.
  - *Por qué TelegramBots long polling:*
    1. *Por rapidez de desarrollo: parsing de `Update` + polling + reconexión ya resueltos (compensa la enmienda frente a RestClient).*
    2. *Por MVP temporal: long polling no exige URL HTTPS pública ni infra extra ($0, demo simple); webhook queda para Fase >1.*
- **RF-02 — Validación estricta:** autor, canal/chat (nombre, sin IDs ni allowlist), tipo fijo `OTRO` a la entrada, texto (1..2000 chars; >2000 se trunca con `truncated:true`) y timestamp del evento son obligatorios; `source` siempre implícito (`DISCORD` o `TELEGRAM` según el bot que lo recibe).
  - *Criterio:* **Dado** un mensaje sin `text` o vacío, **Cuando** llega, **Entonces** se descarta sin procesar y sin exponer PII en logs. **Dado** un texto >2000 chars, **Cuando** llega, **Entonces** se trunca a 2000 y se marca `truncated:true`. El `type` de salida de 001 es siempre `OTRO`; la IA lo clasifica en spec 002.
- **RF-03 — Límites anti-abuso MVP:** texto máximo 2000 chars por mensaje (nativo Discord; Telegram 4096 se trunca a 2000 uniforme con `truncated:true`). Sin rate-limit en backend (ver porqué abajo).
  - *Criterio:* **Dado** un mensaje vacío, **Cuando** llega, **Entonces** se descarta sin procesar. **Dado** un texto >2000 chars, **Cuando** llega, **Entonces** se trunca a 2000 con `truncated:true`.
  - *Por qué no hay rate-limit:*
    1. *Por alcance del MVP: el throttling por fuente queda fuera de Fase 1 para no ampliar superficie ni dependencias.*
    2. *Por complejidad alta y riesgo de pérdida: rechazar con `429` puede hacer que se pierdan mensajes en las conversaciones de Discord/Telegram, ya que los reintentos del lado app no están garantizados.*
- **RF-04 — Normalización:** trimea espacios, normaliza timestamp a UTC ISO-8601, genera `id` (`uuid`) y `batchId` (tracking/agrupación lógica).
  - *Criterio:* **Dado** `"  hola  "` y `2026-09-18T10:00:00-05:00`, **Cuando** se ingiere, **Entonces** el `Comentario` interno queda `text="hola"`, `timestamp=2026-09-18T15:00:00Z`.
- **RF-05 — Anti-abuso por tipo:** a la entrada 001 el `type` es siempre `OTRO` (Discord y Telegram); la IA lo clasifica en spec 002.
  - *Criterio:* **Dado** cualquier mensaje válido de Discord o Telegram, **Cuando** se ingiere, **Entonces** el `Comentario` interno queda `type=OTRO`.

## 3. Requerimientos No Funcionales (RNF)

- **RNF-01:** ingesta sin LLM `p95 < 300ms` local por mensaje individual.
- **RNF-02:** sin autenticación en Fase 1; los bots se autentican con tokens por env (`DISCORD_BOT_TOKEN`, `TELEGRAM_BOT_TOKEN`), nunca en repo. Custodia y rotación de tokens: por definir (dueño TBD, ver §11). Seguridad básica del resto de la API: CORS allowlist + validación + límite de tamaño (Q3).
- **RNF-03:** errores siempre JSON problema (`code, message, errors[]`), nunca stacktrace ni PII en logs.

## 4. Modelo de Dominio y Glosario

- **Comentario:** `{id, batchId, source: DISCORD|TELEGRAM (implícito según el bot), author, channel: nombre del canal/chat, type: OTRO a la entrada, text (truncado a 2000 si excede), truncated: bool, timestamp del evento}`.
- **BatchId:** identificador de agrupación lógica para tracking de mensajes relacionados; no es un contenedor de lotes.
- **Fuente:** origen del dato. `DISCORD` vía bot JDA y `TELEGRAM` vía bot TelegramBots long polling (R8).
- Ejemplo Discord (no es REST):
  ```text
  Discord MessageReceivedEvent → author="Cos_dev", channel="#logros", text="Conseguí mi primer empleo Java!", timestamp=2026-09-18T15:00:00Z → Comentario{source=DISCORD, type=OTRO, truncated=false}
  ```
- Ejemplo Telegram (no es REST):
  ```text
  Telegram Update → from="Cos_dev", chat="Logros", text="Conseguí mi primer empleo Java!", date=2026-09-18T15:00:00Z → Comentario{source=TELEGRAM, type=OTRO, truncated=false}
  ```

## 5. Fuera de Alcance (Out of Scope)

- Endpoints REST de ingesta (`POST /api/v1/ingest` genérico y `/ingest/discord`): eliminados, la entrada es vía bots JDA (Discord) y TelegramBots long polling (Telegram).
- Webhook Telegram, polling, webhooks REST entrantes, recepción CSV (Fase >1 o decisión de producto).
- Rate-limit por fuente + `429` (fuera de Fase 1: por alcance del MVP y por riesgo de pérdida de mensajes en las apps).
- Análisis IA, generación de copys, guardado OCI (specs 002-004).
- Auth, usuarios, roles, DB relacional (Fase >1, R8/§5 constitución).
- Soporte `SLACK`, XML, Excel.
