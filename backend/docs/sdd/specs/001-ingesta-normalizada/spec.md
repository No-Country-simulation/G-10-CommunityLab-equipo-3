# Especificación 001: Ingesta normalizada Discord + Telegram

> Respeta: `constitution.md` v1.0-final (P1-P6, R1-R8, Q1-Q5, Fase 1). Sin JPA, sin auth. Solo QUÉ y POR QUÉ, sin decisiones técnicas de código. Solo JSON.

## 1. Problema y Objetivo

En comunidades tech (Discord, Telegram) se generan a diario testimonios, logros y dudas valiosas que se pierden en el historial del chat. El equipo de Community Management las cura a mano.

Objetivo 001: exponer un endpoint REST que reciba **un solo mensaje de texto** (JSON), lo valide y lo normalice a `Comentario` listo para análisis IA (spec 002). Cada mensaje se envía individualmente según la actividad de cada app en Discord/Telegram; no se reciben lotes. Sin este paso no hay pipeline.

## 2. Requerimientos Funcionales (RF)

- **RF-01 — Ingesta JSON:** el sistema debe aceptar `POST /api/v1/ingest` con `Content-Type: application/json` y cuerpo `{source, Comentario}`.
  - *Criterio de Aceptación:* **Dado** un payload JSON válido con `source=DISCORD` y un `Comentario` completo, **Cuando** se hace POST, **Entonces** responde `202 Accepted` con `{id, batchId}` en <300ms p95 local (sin LLM).
- **RF-02 — Validación estricta:** autor, canal, tipo, texto (1..2000 chars) y timestamp ISO-8601 son obligatorios; `source` solo `DISCORD|TELEGRAM`; `type` solo `TESTIMONIO|LOGRO|DUDA|OTRO`.
  - *Criterio:* **Dado** un `Comentario` sin `text` o con `source=SLACK`, **Cuando** se hace POST, **Entonces** responde `400` con `{code, message, errors:[{field, reason}]}` y no lo procesa.
- **RF-03 — Límites anti-abuso MVP:** max 10MB por body, texto máximo 2000 chars por mensaje, rate-limit por fuente (max N mensajes/minuto).
  - *Criterio:* **Dado** un body >10MB, **Cuando** se hace POST, **Entonces** responde `413` con mensaje explícito. **Dado** N+1 mensajes en 1 minuto desde la misma fuente, **Cuando** se hace POST, **Entonces** responde `429 Too Many Requests`.
- **RF-04 — Normalización:** trimea espacios, normaliza timestamp a UTC ISO-8601, genera `id` (`uuid`) y `batchId` (tracking/agrupación lógica).
  - *Criterio:* **Dado** `"  hola  "` y `2026-09-18T10:00:00-05:00`, **Cuando** se ingiere, **Entonces** el `Comentario` interno queda `text="hola"`, `timestamp=2026-09-18T15:00:00Z`.
- **RF-05 — Anti-abuso por tipo:** no se aceptan campos vacíos ni tipos no registrados.
  - *Criterio:* **Dado** un `Comentario` con `type=INVALIDO`, **Cuando** se hace POST, **Entonces** `400`.

## 3. Requerimientos No Funcionales (RNF)

- **RNF-01:** ingesta sin LLM `p95 < 300ms` local por mensaje individual.
- **RNF-02:** sin autenticación en Fase 1, pero con CORS allowlist + validación + límite de tamaño (Q3).
- **RNF-03:** errores siempre JSON problema (`code, message, errors[]`), nunca stacktrace ni PII en logs.

## 4. Modelo de Dominio y Glosario

- **Comentario:** `{id, batchId, source: DISCORD|TELEGRAM, author, channel, type: TESTIMONIO|LOGRO|DUDA|OTRO, text, timestamp}`.
- **BatchId:** identificador de agrupación lógica para tracking de mensajes relacionados; no es un contenedor de lotes.
- **Fuente:** origen del dato. Solo `DISCORD`, `TELEGRAM` (R8).
- Ejemplo JSON:
  ```json
  {"source":"DISCORD","Comentario":{"author":"Cos_dev","channel":"#logros","type":"LOGRO","text":"Conseguí mi primer empleo Java!","timestamp":"2026-09-18T10:00:00-05:00"}}
  ```

## 5. Fuera de Alcance (Out of Scope)

- Bots directos a Discord/Telegram, polling, webhooks entrantes, recepción CSV (Fase >1 o decisión de producto).
- Análisis IA, generación de copys, guardado OCI (specs 002-004).
- Auth, usuarios, roles, DB relacional (Fase >1, R8/§5 constitución).
- Soporte `SLACK`, XML, Excel.
