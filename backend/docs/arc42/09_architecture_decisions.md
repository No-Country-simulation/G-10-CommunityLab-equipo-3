# 09. Decisiones de Arquitectura

> Estado: **vigente**. Formato por ADR: Título, Estado (Propuesto/Aceptado/Obsoleto),
> Contexto, Decisión, Consecuencias (pros/contras). El razonamiento vive aquí, no en §04.

| ADR | Título | Estado | Origen |
|---|---|---|---|
| ADR-001 | Arquitectura hexagonal con núcleo puro | Aceptado | Constitución P1/P2/R1–R3 |
| ADR-002 | Sin persistencia relacional en Fase 1 | Aceptado | Constitución §2 (prohibición JPA) |
| ADR-003 | OCI Object Storage como único storage | Aceptado | Constitución §2, spec 004 |
| ADR-004 | Seguridad básica sin JWT en Fase 1 | Aceptado | Constitución Q3, spec 004 |
| ADR-005 | Actuator `health,info` públicos, resto cerrado | Aceptado | Constitución §2, spec 004 |
| ADR-006 | Ingesta vía bot Discord JDA, sin endpoint REST (CSV y `POST /api/v1/ingest` descartados) | Aceptado | Spec 001 (bot como backend, formatos por app) |
| ADR-007 | Integración única contra el contrato API de OpenAI, modelos intercambiables por env | Aceptado | Constitución §2, specs 002–003 |
| ADR-008 | JDA solo en `infrastructure/` tras `IngestUseCaseDiscord`; token por env, anti-bot, `OTRO` clasificado por IA | Aceptado | Spec 001, constitución §2 |
| ADR-009 | TelegramBots long polling temporal MVP (webhook en Fase >1); solo en `infrastructure/` tras `IngestUseCaseTelegram`; token por env, anti-bot, truncado 2000 + flag | Aceptado | Spec 001, constitución §2. Razón: rapidez (Update+polling resueltos), sin URL pública, $0 |
| ADR-010 | Buffer Redis append-only por tamaño en lugar de in-memory | Aceptado | Specs 001/004, constitución v1.2 |
| ADR-011 | CORS allowlist solo-GET sin credenciales, resto `denyAll` | Aceptado | Constitución Q3, spec 004 RF-05 |

## ADR-010 — Buffer Redis append-only por tamaño

- **Contexto:** el lote (`batchId`) necesita almacenamiento real compartido; el in-memory se pierde al reiniciar y no cuenta bytes para el flush OCI.
- **Decisión:** `RedisBufferAdapter` con `spring-data-redis` solo-Java (`ListOps/SetOps`, sin Lua): `SADD messageId` dedup → `RPUSH` JSON → `INCRBY` bytes UTF-8; keys `buffer:current:id/list/ids/bytes`; cierre por `REDIS_BUFFER_MAX_BYTES=921600`; degradado `REDIS_NOT_CONFIGURED` sin tumbar ingesta. Local en Docker, prod en contenedor hermano en la VM.
- **Consecuencias:** pro: lote persistente entre reinicios + conteo exacto en bytes para el `<1MB` OCI; contra: Redis es un componente más que operar; el `append` hoy guarda el crudo al ingerir y deberá moverse al fork post-LLM (pendiente 002/003).

## ADR-011 — CORS allowlist solo-GET sin credenciales

- **Contexto:** API pública sin auth en Fase 1; el único consumidor navegador es el dashboard externo.
- **Decisión:** allowlist `cors.allowed.origins` por env (falla si `*`), métodos `GET,OPTIONS`, headers mínimos + `Last-Event-ID` expuesto, `allowCredentials=false`; `SecurityConfig` con `health,info` públicos y resto `denyAll`; CSRF off por API stateless.
- **Consecuencias:** pro: superficie mínima verificable por el navegador; contra: CORS no frena a `curl`/scripts (aislamiento real exigiría auth, Fase >1).
