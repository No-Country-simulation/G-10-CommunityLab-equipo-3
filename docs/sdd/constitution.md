# Constitución del Proyecto: communityLab backend

> **Versión:** 1.8-buffer-por-fuente · **Estado:** ratificada · **Fecha:** 2026-10-06
> **Alcance:** rama `backend` (sync a `main/backend/` vía `automation-update-backend.yml`).
> **Stack verificado:** Java 21 + Spring Boot 4.1.1 + Maven (ver `pom.xml`, `README.md`).
> **Negocio (ratificado):** motor inteligente de transformación y distribución de contenido para comunidades tech (Discord, Telegram). Convierte actividad orgánica (testimonios, logros, dudas) en activos listos para LinkedIn, X/Twitter, Newsletter y FAQ. MVP Hackathon Oracle Next Education ONE - Grupo 10.
> Esta es la ley suprema del repo. Ningún `spec.md` que la viole puede aprobarse.

## 1. Principios Fundamentales

- **P1 — Hexagonal estricto, núcleo puro:** la lógica de negocio vive en `domain` + `application`. `infrastructure/adapters/in/web` solo mapea HTTP <-> DTO. Verificación: `domain/` sin imports `org.springframework` ni `jakarta.persistence`; ningún `*Controller` contiene lógica condicional de negocio.
- **P2 — Dependencias hacia adentro:** `infrastructure/adapters/in/web -> application -> domain`. `infrastructure/adapters/out` implementa puertos de `application`, nunca al revés. Verificación en review de PR.
- **P3 — API-first verificable:** todo endpoint nuevo expone OpenAPI/Swagger actualizado y test `webmvc-test`. Sin Swagger, sin merge.
- **P4 — Todo vago es métrica:** prohibido "rápido / limpio / seguro" sin número. Ej: `p95 < 1500ms` en pipeline IA local (sin contar latencia LLM externa), `cobertura >= 80% en domain+application`.
- **P5 — SDD antes que código:** sin `spec.md` aprobado no hay `plan.md`; sin `plan.md` no hay `tasks.md`; sin `tasks.md` no hay código. Los planes viven en `docs/sdd/specs/NNN-*/plan.md`. Prohibido `docs/sdd/plan.md` global.
- **P6 — Commits convencionales:** `type(scope): subject` en inglés, un cambio lógico por commit (ej: `feat(ingest): add csv parser`). Verificación en review; futuro commitlint en CI.

## 2. Stack Tecnológico Autorizado (lista cerrada — Fase 1)

| Capa | Permitido                            | Versión / nota |
|---|--------------------------------------|---|
| Lenguaje | Java LTS                             | **21**, sin APIs preview |
| Framework | Spring Boot                          | **4.1.1**, módulos: `webmvc`, `restclient`, `validation`, `actuator` (a añadir), `springdoc-openapi` (a añadir) |
| Build | Maven Wrapper                        | 3.9.x — `./mvnw test`, `./mvnw spring-boot:run` |
| IA | Spring AI                            | Contrato API OpenAI, vendor-agnóstico (vale cualquier modelo compatible, ej. Mistral vía `base-url`). Modelo y endpoint configurables por env. Solo en `infrastructure/` tras `RequestToLLMProcess` |
| Bot Discord | JDA (Java Discord API) | Ingesta Discord: el backend es el bot (Gateway, intents `MESSAGE_CONTENT`/`GUILD_MESSAGES`). Solo en `infrastructure/` tras `IngestUseCaseDiscord`. Token solo por env `DISCORD_BOT_TOKEN` |
| Bot Telegram | TelegramBots long polling | Ingesta Telegram: el backend es el bot (long polling, sin URL pública; webhook en Fase >1). Solo en `infrastructure/` tras `IngestUseCaseTelegram`. Token/username solo por env `TELEGRAM_BOT_TOKEN` / `TELEGRAM_BOT_USERNAME` |
| Storage | OCI Object Storage SDK (Always Free) | Único almacenamiento persistente Fase 1, solo vía el puerto `ArtifactStore` (implementado en `infrastructure/`) |
| Buffer | spring-data-redis + Redis (Docker local / OCI Cache prod) | Buffer volátil Fase 1, solo vía `BufferPort` en `infrastructure/`. Solo Java (`RedisTemplate/ListOps/SetOps`, `MULTI/EXEC` para sellar), sin Lua. **Un lote por fuente** (`buffer:{SOURCE}:*`). Flush por bytes `REDIS_BUFFER_MAX_BYTES=921600 (900KB)` **o** cron horario `BUFFER_FLUSH_CRON` si `count >= BUFFER_FLUSH_MIN_MESSAGES=5` |
| Docs API | springdoc-openapi                    | Swagger UI obligatorio |
| Observabilidad mínima | Actuator `health,info`               | `GET /actuator/health -> {"status":"UP"}` público; resto de endpoints cerrados |
| Secretos | Env local / GitHub Secrets           | Nunca commiteados (R4) |

**Prohibido en Fase 1:** `spring-data-jpa`, `Flyway/Liquibase`, ` spring-security con JWT/sesiones`, `H2/PostgreSQL/Mongo`, `@Entity`, `JpaRepository`, `DataSource` relacional. Cualquier uso requiere enmienda a Fase 2.

Regla de adición: lo fuera de esta tabla requiere enmienda + justificación `Decisión / Alternativa / Razón` en el `plan.md`.

## 3. Restricciones Inviolables

- **R1 — Paquetes fijos:** base `com.nocountry.simulation.communitylab`:
  ```text
  domain/         # POJOs/records puros: Comment, EnrichedComment, ResponseModel; políticas RelevancePolicy/HallucinationGuard (AssetPackage llega con 004). Sin Spring/JPA.
  application/    # Use cases + ports + commands + dtos. Sin @Controller, sin @Entity, sin SDKs.
  infrastructure/ # Adapters in (web controllers, mappers HTTP<->DTO, GlobalExceptionHandler, bots JDA/Telegram) + adapters out (Spring AI, OCI SDK, Redis buffer, RestClient) + config. Los adapters out implementan ports.
  ```
- **R2 — Prohibido en `infrastructure/adapters/in/web`:** lógica de negocio, acceso a storage, llamadas a SDKs. Solo delega a `application`.
- **R3 — Prohibido en `domain/`:** anotaciones Spring, JPA, Lombok con lógica. Solo POJOs + validación pura.
- **R4 — Secretos nunca en git:** prohibido commitear `.env`, `*.env`, `application-local.yaml`, `*.pem`, `*.key`, `token*.json`. Lectura vía `${VAR}` en `application.yaml`. Cubierto en `.gitignore`.
- **R5 — Config por perfiles:** `application.yaml` base sin credenciales (hoy solo `spring.application.name`, mantener). `application-local.yaml` dev, `application-prod.yaml` prod con env: `API_KEY_LLM_MISTRAL_DEV` (+ `MODEL_MISTRAL` y `BASE_URL_MODEL_AI` requerido para dirigir la request según proveedor), `OCI_BUCKET`, `OCI_REGION`, `CORS_ALLOWED_ORIGINS`, `DISCORD_BOT_TOKEN`, `TELEGRAM_BOT_TOKEN` (+ `TELEGRAM_BOT_USERNAME` opcional).
- **R6 — Presupuesto Fase 1:** ingesta (sin LLM) `p95 < 300ms` local; pipeline con LLM documenta latencia externa aparte; arranque local `< 15s`; objeto OCI `< 1MB` por paquete; buffer Redis por fuente con flush por `REDIS_BUFFER_MAX_BYTES=921600 (900KB)` (margen de envelope bajo el 1MB) o por reloj horario con `>= 5` mensajes.
- **R7 — Ramas y sync:** trabajo en `backend`. Sync a `main` solo vía workflow `sync backend to main` (`subtree` a `backend/`). Prohibido push directo a `main`.
- **R8 — Fuentes y canales Fase 1:** fuentes aceptadas `DISCORD`, `TELEGRAM` únicamente. Canales de salida `LINKEDIN`, `X`, `NEWSLETTER`, `FAQ` únicamente. Otra fuente/canal requiere enmienda.

## 4. Estándares de Calidad y Seguridad (Fase 1 = básica, sin auth)

- **Q1 — Tests bloqueantes:** `./mvnw test` verde obligatorio. Por use case + `webmvc-test` por controller. Meta ≥80% en `domain`+`application` (JaCoCo cuando se añada).
- **Q2 — Contratos:** todo `spec.md` con `Dado/Cuando/Entonces`; todo `plan.md` con estrategia de pruebas; todo `tasks.md` con verificación por tarea (comando exacto).
- **Q3 — Seguridad básica:** CORS allowlist por env (no `*` en prod), headers (`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`), CSRF deshabilitado por API stateless sin cookies (documentado en plan 004), validación Bean Validation en todo input, límite `10MB` por request ingesta, sin PII (tokens, emails completos) en logs. Anonimato solo ante el LLM; OCI conserva usuario para trazabilidad. Logs prod solo `messageId/batchId/source` (sin `authorId`); en local/test se permite `authorId` a `DEBUG`, nunca `authorName/contenido/tokens`.
- **Q4 — Revisión:** PR <400 líneas, descripción con `Derivado de: spec.md RF-X + plan.md §Y`, CI verde, un aprobador.
- **Q5 — Estilo:** Java 21 idiomático (records), inglés en código/commits, español en docs SDD. Se comenta el porqué, no el qué.

## 5. Fases de Producto (ratificado)

- **Fase 1 (MVP Hackathon, actual):** ingesta Discord vía bot JDA + ingesta Telegram vía bot TelegramBots long polling (el backend es ambos bots) + análisis IA + generación multicanal + buffer Redis por fuente (tamaño u hora) + guardado OCI batch + Actuator + Swagger + CORS. **Toda la integración AI entra aquí.** Webhook Telegram en Fase >1.
- **Fase >1 (explícitamente fuera):** autenticación (login/JWT/sesiones/roles), persistencia de usuarios, dashboard, webhook Telegram, facturación, multi-tenant. Ningún `spec` Fase 1 puede incluirlo; irá en `specs/00X-*` futuros con enmienda constitucional.

## 6. Gobierno SDD y Trazabilidad

1. Jerarquía: `constitution.md` > `specs/NNN-*/spec.md` > `plan.md` > `tasks.md`. Tarea sin `Derivado de:` se rechaza.
2. Ubicación canónica:
   ```text
   docs/sdd/constitution.md
   docs/sdd/specs/001-ingesta-normalizada/{spec,plan,tasks}.md
   docs/sdd/specs/002-analisis-ia/{spec,plan,tasks}.md
   docs/sdd/specs/003-generacion-multicanal/{spec,plan,tasks}.md
   docs/sdd/specs/004-paquete-oci-health/{spec,plan,tasks}.md
   ```
3. Cambios a esta constitución: PR `docs(sdd): amend constitution` con razón + impacto.
4. `sdd-audit` obligatorio antes de codificar cada spec.

---

### Historial

- `v1.0-propuesta`: P/R/Q verificables, stack versionado contra `pom.xml`, paquetes fijos, gobierno SDD.
- `v1.0-final (esta)`: ratifica D1 sin JPA, D2 seguridad básica, D3 Fase 1 = toda AI / Fase >1 = auth+usuarios, D4 Actuator sí, D5 negocio communityLab + R8 fuentes/canales.
- `v1.1-bots`: JDA (Discord) + TelegramBots long polling (Telegram) en alcance Fase 1; REST de ingesta eliminado; `type=OTRO` lo clasifica la IA; truncado a 2000 + flag; sin rate-limit; webhook Telegram en Fase >1.
- `v1.2-redis-buffer`: `id=messageId` nativo Discord (sin uuid por mensaje) + `batchId=uuid` lote abierto en Redis; buffer `LIST+SET+bytes` solo-Java (sin Lua) con flush por `900KB`; fork post-LLM `SSE inmediato (asset.created, persisted:false) + buffer para OCI batch (package.completed)`; LLM por mensaje, OCI en batch.
- `v1.3-openai-contract`: IA bajo contrato API OpenAI vendor-agnóstico (ej. Mistral vía `base-url`); envs canónicos `API_KEY_LLM_MISTRAL_DEV + MODEL_MISTRAL + BASE_URL_MODEL_AI` (base-url requerido para dirigir la request según proveedor).
- `v1.4-trazabilidad`: anonimato solo ante el LLM; OCI/buffer guardan `authorId/authorName/channelId` para trazabilidad; logs prod sin `authorId` (permitido a `DEBUG` en local/test).
- `v1.5-hexagonal-puro`: se elimina la capa fantasma `interfaces/` (propia de Clean Architecture, no hexagonal). Los controllers web son adaptadores de entrada en `infrastructure/adapters/in/web/`; bots en `adapters/in/bot|*`. Flujo `adapters/in/web -> application -> domain`, `adapters/out -.implementa.-> application`. Alinea código real (`src/.../infrastructure/adapters/in/web/`) con docs.
- `v1.6-single-get`: la API de negocio expone un único `GET /api/v1/discord/messages` (SSE). Análisis/generación/flush/newsletter son internos (evento + jobs); sin `POSTs` ni `GETs packages`. Buffer/OCI solo internos; el `GET` solo emite al frontend.
- `v1.7-dual-get`: la API de negocio expone un `GET` SSE por fuente: `GET /api/v1/discord/messages` y `GET /api/v1/telegram/messages`, aislados por `source` (emisores y replay por cola de fuente, sin mezclar eventos). *Decisión:* dual-get. *Alternativa descartada:* único GET multiplexado con filtro `?source=`. *Razón:* aislamiento por fuente para demo y evolución independiente Discord/Telegram, sin romper el fork Discord existente. Resto de reglas v1.6 intactas: sin `POSTs` de ingesta ni `GETs packages`.
- `v1.8-buffer-por-fuente`: el buffer Redis mantiene **un lote por fuente** (`buffer:DISCORD:*` / `buffer:TELEGRAM:*`), cada uno con su `batchId`, dedup, contador de bytes y flush independientes. El lote cierra por `bytes >= 921600` **o** por cron horario (`0 0 * * * *`) si tiene `>= 5` mensajes (configurable). Cierre = sellado atómico `MULTI/EXEC + RENAME` a `sealed:{batchId}` + marca en `pending`; solo se borra tras confirmación del store, si no se reintenta cada tick. *Decisión:* lote por fuente + doble disparador. *Alternativa descartada:* lote único mixto solo por tamaño. *Razón:* `AssetPackage.source` y `package.completed` son por fuente (dual-get), cada fuente con su ritmo; sin flush horario un lote de poco tráfico no se persistiría nunca.
