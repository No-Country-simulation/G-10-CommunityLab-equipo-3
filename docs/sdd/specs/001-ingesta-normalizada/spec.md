# Especificación 001: Ingesta normalizada vía bot Discord JDA (+ Telegram deferrado)

> Respeta: `constitution.md` v1.2-redis-buffer (P1-P6, R1-R8, Q1-Q5, Fase 1). Sin JPA, sin auth. Solo QUÉ y POR QUÉ, sin decisiones técnicas de código. Sin endpoint REST de ingesta: el backend es el bot.
> Alcance semanal: solo `DISCORD` en canal `#Listen` filtrado por ID (`DISCORD_LISTEN_CHANNEL_ID`). `TELEGRAM` (RF-01b) queda deferrado esta semana, no se implementa.

## 1. Problema y Objetivo

En comunidades tech (Discord, Telegram) se generan a diario testimonios, logros y dudas valiosas que se pierden en el historial del chat. El equipo de Community Management las cura a mano.

Objetivo 001: escuchar los mensajes de Discord `#Listen` (bot JDA por ID) —el backend es el bot—, validar cada mensaje y normalizarlo a `Comment` listo para análisis IA (spec 002). Secuencia impuesta: `Bot → LLM por mensaje (002) → etiquetas canal (003) → fork: SSE inmediato al frontend + buffer Redis por tamaño → OCI batch + frontend (004)`. Cada mensaje se ingiere individualmente según llega (`id=messageId` nativo Discord, `batchId=lote abierto en Redis`); el paquete OCI se cierra por tamaño (`N mensajes = 1 paquete`). No hay endpoint REST de ingesta ni se reciben lotes. Sin este paso no hay pipeline.

## 2. Requerimientos Funcionales (RF)

- **RF-01a — Ingesta vía bot JDA (Discord `#Listen` por ID):** el sistema debe escuchar solo mensajes Discord del canal cuyo ID sea `${DISCORD_LISTEN_CHANNEL_ID}` (display `#Listen`) con JDA (intents `MESSAGE_CONTENT`/`GUILD_MESSAGES`) y convertir cada mensaje en `Comment`. Sin endpoint REST. Mensajes fuera de `#Listen`, DMs e hilos se ignoran en silencio.
  - *Criterio de Aceptación:* **Dado** un mensaje válido en `#Listen` con contenido completo, **Cuando** el bot lo recibe, **Entonces** lo normaliza a `Comment` con `{id=messageId nativo, batchId=lote abierto Redis}` en <300ms p95 local (sin LLM) y dispara `002` en background. **Dado** un mensaje de otro bot o fuera de `#Listen`, **Cuando** llega, **Entonces** se ignora sin procesar ni logs PII.
- **RF-01b — Ingesta vía bot TelegramBots long polling (Telegram, deferrado esta semana):** no se implementa esta semana. Spec reservado para Fase>1-semana.
  - *Criterio de Aceptación:* **Dado** un mensaje válido en un chat Telegram visible con contenido completo, **Cuando** el bot lo recibe, **Entonces** lo normaliza a `Comment` con `{id=messageId nativo, batchId=lote abierto}` en <300ms p95 local (sin LLM). **Dado** un mensaje de otro bot, **Cuando** llega, **Entonces** se ignora sin procesar.
  - *Por qué TelegramBots long polling:*
    1. *Por rapidez de desarrollo: parsing de `Update` + polling + reconexión ya resueltos (compensa la enmienda frente a RestClient).*
    2. *Por MVP temporal: long polling no exige URL HTTPS pública ni infra extra ($0, demo simple); webhook queda para Fase >1.*
- **RF-02 — Validación estricta:** autor, canal (nombre informativo + filtro duro por `channelId == ${DISCORD_LISTEN_CHANNEL_ID}`), tipo fijo `OTRO` a la entrada, texto (1..2000 chars; >2000 se trunca con `truncated:true`) y timestamp del evento son obligatorios; `source` siempre `DISCORD` esta semana.
  - *Criterio:* **Dado** un mensaje sin `text` o vacío, **Cuando** llega, **Entonces** se descarta sin procesar y sin exponer PII en logs. **Dado** un texto >2000 chars, **Cuando** llega, **Entonces** se trunca a 2000 y se marca `truncated:true`. El `type` de salida de 001 es siempre `OTRO`; la IA lo clasifica en spec 002.
- **RF-03 — Límites anti-abuso MVP:** texto máximo 2000 chars por mensaje (nativo Discord, `>2000` truncado uniforme con `truncated:true`). Sin rate-limit en backend (ver porqué abajo).
  - *Criterio:* **Dado** un mensaje vacío, **Cuando** llega, **Entonces** se descarta sin procesar. **Dado** un texto >2000 chars, **Cuando** llega, **Entonces** se trunca a 2000 con `truncated:true`.
  - *Por qué no hay rate-limit:*
    1. *Por alcance del MVP: el throttling por fuente queda fuera de Fase 1 para no ampliar superficie ni dependencias.*
    2. *Por complejidad alta y riesgo de pérdida: rechazar con `429` puede hacer que se pierdan mensajes en las conversaciones de Discord/Telegram, ya que los reintentos del lado app no están garantizados.*
- **RF-04 — Normalización:** trimea espacios, normaliza timestamp a UTC ISO-8601, usa `id=messageId` nativo Discord (snowflake `String`, único, sin generar uuid por mensaje) y `batchId=uuid` del lote abierto en Redis (`GET buffer:current:id`).
  - *Criterio:* **Dado** `"  hola  "` y `2026-09-18T10:00:00-05:00`, **Cuando** se ingiere, **Entonces** el `Comment` interno queda `text="hola"`, `timestamp=2026-09-18T15:00:00Z`.
- **RF-05 — Anti-abuso por tipo:** a la entrada 001 el `type` es siempre `OTRO` (Discord); la IA lo clasifica en spec 002.
  - *Criterio:* **Dado** cualquier mensaje válido de `#Listen`, **Cuando** se ingiere, **Entonces** el `Comment` interno queda `type=OTRO`.

## 3. Requerimientos No Funcionales (RNF)

- **RNF-01:** ingesta sin LLM `p95 < 300ms` local por mensaje individual.
- **RNF-02:** sin autenticación en Fase 1; el bot se autentica con token por env (`DISCORD_BOT_TOKEN` + filtro `DISCORD_LISTEN_CHANNEL_ID`), nunca en repo. Custodia y rotación de tokens: por definir (dueño TBD, ver §11). Seguridad básica del resto de la API: CORS allowlist + validación + límite de tamaño (Q3).
- **RNF-03:** errores siempre JSON problema (`code, message, errors[]`), nunca stacktrace ni PII en logs.

## 4. Modelo de Dominio y Glosario

- **Comment:** `{id=messageId nativo Discord, batchId=lote abierto Redis, source: DISCORD, author, channel: nombre informativo + channelId filtrado, type: OTRO a la entrada, text (truncado a 2000 si excede), truncated: bool, timestamp del evento}`.
- **BatchId:** `uuid` del contenedor/lote abierto en Redis (`buffer:current:id`) que agrupa N mensajes por tamaño y luego va a OCI `paquetes/paquete-{batchId}.json` (004 RF-01). No es 1:1 por mensaje.
- **Fuente:** `DISCORD` vía bot JDA en `#Listen` por ID (R8, alcance semanal). `TELEGRAM` deferrado.
- Ejemplo Discord (no es REST):
  ```text
  Discord MessageReceivedEvent → author="Cos_dev", channel="#Listen" (id==env), text="Conseguí mi primer empleo Java!", timestamp=2026-09-18T15:00:00Z → Comment{source=DISCORD, type=OTRO, truncated=false}
  ```

## 5. Fuera de Alcance (Out of Scope)

- Telegram (`IngestUseCaseTelegram`, long polling, webhook): deferrado esta semana, solo Discord `#Listen`.
- Endpoints REST de ingesta (`POST /api/v1/ingest` genérico y `/ingest/discord`): eliminados, la entrada es vía bot JDA.
- Webhook Telegram, polling, webhooks REST entrantes, recepción CSV (Fase >1 o decisión de producto).
- Rate-limit por fuente + `429` (fuera de Fase 1: por alcance del MVP y por riesgo de pérdida de mensajes en las apps).
- Análisis IA, generación de copys, guardado OCI (specs 002-004).
- Auth, usuarios, roles, DB relacional (Fase >1, R8/§5 constitución).
- Soporte `SLACK`, XML, Excel.
