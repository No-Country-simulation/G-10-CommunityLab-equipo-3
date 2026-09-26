# 11. Riesgos y Deuda Técnica

> Estado: **vigente**. Riesgos con impacto/probabilidad/mitigación y dueño.

## Deuda técnica registrada (intencional, con plan de pago)

| # | Deuda | Origen | Plan de pago |
|---|---|---|---|
| TD-3 | Sin `Dockerfile` ni perfiles de despliegue (el deploy prevé contenedor en OCI Compute) | Diagrama de despliegue del equipo + §07 | Crear `Dockerfile` (+ `.dockerignore`) e imagen < 500 MB antes de la demo en OCI |

## Riesgos

| # | Riesgo | Impacto / Probabilidad | Mitigación (dueño) |
|---|---|---|---|
| R-llm-01 | LLM caído/lento durante la demo | Alto / Media | Timeout 15s + 1 reintento + fallback tipado, sin `500` (specs 002/003); pipeline 002→003 pendiente de cablear |
| R-pipe-01 | Pipeline incompleto: `append` guarda el crudo al ingerir en vez del procesado post-LLM | Alto / Alta (estado actual) | Mover el `append` al fork post-003 al implementar 002/003 (orden: 002 → 003 → 004) |
| R-sec-01 | Exposición de API pública sin auth | Medio / Baja | Aceptado Fase 1: CORS allowlist + validación + superficie solo-GET + `denyAll` (Q3); auth real en Fase >1 |
| R-ingest-01 | Ráfagas de ingesta sin throttling | Medio / Media en demo | Aceptado en Fase 1 por alcance del MVP y para no perder mensajes; mitigación: filtro anti-bot + texto 1..2000, p95<300ms, dedup por `messageId` |
| R-discord-01 | Token Discord filtrado / intents sin aprobar / caída Gateway | Alto / Baja-media | Token solo por env, custodia devs backend (R4); intents verificados pre-demo; reconexión JDA + degradado sin tumbar health |
| R-telegram-01 | Token Telegram filtrado / polling caído | Alto / Baja-media | Token/username solo por env, custodia devs backend (R4); long polling con reconexión + degradado (Telegram planificado, Discord primero) |
| R-truncate-01 | Corte por truncado a 2000 | Bajo / Media | Flag `truncated:true`; 002 no penaliza relevancia por corte |
| R-oci-01 | Costos/límites del tier Always Free de OCI | Medio / Baja | Objetos < 1MB, sin versionado histórico; bucket/región aún TBD (spec 004) |
| R-cors-01 | `cors.allowed.origins` sin default tumba el arranque sin env | Medio / Alta (estado actual) | Fijar default local `http://localhost:4200` antes de la demo |
