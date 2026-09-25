# Constitución del Proyecto: communityLab backend

> **Versión:** 1.0-final · **Estado:** ratificada · **Fecha:** 2026-09-18
> **Alcance:** rama `backend` (sync a `main/backend/` vía `automation-update-backend.yml`).
> **Stack verificado:** Java 21 + Spring Boot 4.1.1 + Maven (ver `pom.xml`, `README.md`).
> **Negocio (ratificado):** motor inteligente de transformación y distribución de contenido para comunidades tech (Discord, Telegram). Convierte actividad orgánica (testimonios, logros, dudas) en activos listos para LinkedIn, X/Twitter, Newsletter y FAQ. MVP Hackathon Oracle Next Education ONE - Grupo 10.
> Esta es la ley suprema del repo. Ningún `spec.md` que la viole puede aprobarse.

## 1. Principios Fundamentales

- **P1 — Hexagonal estricto, núcleo puro:** la lógica de negocio vive en `domain` + `application`. `interfaces` (web) solo mapea HTTP <-> DTO. Verificación: `domain/` sin imports `org.springframework` ni `jakarta.persistence`; ningún `*Controller` contiene lógica condicional de negocio.
- **P2 — Dependencias hacia adentro:** `interfaces -> application -> domain`. `infrastructure` implementa puertos de `application`, nunca al revés. Verificación en review de PR.
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
| IA | Spring AI                            | OpenAI como único proveedor, modelo configurable por env. Solo en `infrastructure/` tras `AnalyzePort` / `GeneratePort` |
| Bot Discord | JDA (Java Discord API) | Ingesta Discord: el backend es el bot (Gateway, intents `MESSAGE_CONTENT`/`GUILD_MESSAGES`). Solo en `infrastructure/` tras `IngestUseCaseDiscord`. Token solo por env `DISCORD_BOT_TOKEN` |
| Bot Telegram | TelegramBots long polling | Ingesta Telegram: el backend es el bot (long polling, sin URL pública; webhook en Fase >1). Solo en `infrastructure/` tras `IngestUseCaseTelegram`. Token/username solo por env `TELEGRAM_BOT_TOKEN` / `TELEGRAM_BOT_USERNAME` |
| Storage | OCI Object Storage SDK (Always Free) | Único almacenamiento persistente Fase 1, solo vía `ArtifactStorePort` en `infrastructure/` |
| Buffer | spring-data-redis + Redis (Docker local / OCI Cache prod) | Buffer volátil Fase 1, solo vía `BufferPort` en `infrastructure/`. Solo Java (`RedisTemplate/ListOps/SetOps`), sin Lua. Flush por bytes `REDIS_BUFFER_MAX_BYTES=921600 (900KB)` |
| Docs API | springdoc-openapi                    | Swagger UI obligatorio |
| Observabilidad mínima | Actuator `health,info`               | `GET /actuator/health -> {"status":"UP"}` público; resto de endpoints cerrados |
| Secretos | Env local / GitHub Secrets           | Nunca commiteados (R4) |

**Prohibido en Fase 1:** `spring-data-jpa`, `Flyway/Liquibase`, ` spring-security con JWT/sesiones`, `H2/PostgreSQL/Mongo`, `@Entity`, `JpaRepository`, `DataSource` relacional. Cualquier uso requiere enmienda a Fase 2.

Regla de adición: lo fuera de esta tabla requiere enmienda + justificación `Decisión / Alternativa / Razón` en el `plan.md`.

## 3. Restricciones Inviolables

- **R1 — Paquetes fijos:** base `com.nocountry.simulation.communitylab`:
  ```text
  domain/         # POJOs/records puros: Comment, Asset, AssetPackage, Relevance. Sin Spring/JPA.
  application/    # Use cases + ports + commands + dtos. Sin @Controller, sin @Entity, sin SDKs.
  infrastructure/ # Adapters: Spring AI, OCI SDK, Redis buffer (spring-data-redis), JDA (bot Discord), TelegramBots long polling (bot Telegram), CsvParser, RestClient, config. Implementa ports.
  interfaces/     # Controllers, mappers HTTP<->DTO, GlobalExceptionHandler.
  ```
- **R2 — Prohibido en `interfaces/`:** lógica de negocio, acceso a storage, llamadas a SDKs. Solo delega a `application`.
- **R3 — Prohibido en `domain/`:** anotaciones Spring, JPA, Lombok con lógica. Solo POJOs + validación pura.
- **R4 — Secretos nunca en git:** prohibido commitear `.env`, `*.env`, `application-local.yaml`, `*.pem`, `*.key`, `token*.json`. Lectura vía `${VAR}` en `application.yaml`. Cubierto en `.gitignore`.
- **R5 — Config por perfiles:** `application.yaml` base sin credenciales (hoy solo `spring.application.name`, mantener). `application-local.yaml` dev, `application-prod.yaml` prod con env: `OPENAI_API_KEY` (+ `OPENAI_MODEL` opcional), `OCI_BUCKET`, `OCI_REGION`, `CORS_ALLOWED_ORIGINS`, `DISCORD_BOT_TOKEN`, `TELEGRAM_BOT_TOKEN` (+ `TELEGRAM_BOT_USERNAME` opcional).
- **R6 — Presupuesto Fase 1:** ingesta (sin LLM) `p95 < 300ms` local; pipeline con LLM documenta latencia externa aparte; arranque local `< 15s`; objeto OCI `< 1MB` por paquete; buffer Redis flush por `REDIS_BUFFER_MAX_BYTES=921600 (900KB)` para dejar margen de envelope bajo el 1MB.
- **R7 — Ramas y sync:** trabajo en `backend`. Sync a `main` solo vía workflow `sync backend to main` (`subtree` a `backend/`). Prohibido push directo a `main`.
- **R8 — Fuentes y canales Fase 1:** fuentes aceptadas `DISCORD`, `TELEGRAM` únicamente. Canales de salida `LINKEDIN`, `X`, `NEWSLETTER`, `FAQ` únicamente. Otra fuente/canal requiere enmienda.

## 4. Estándares de Calidad y Seguridad (Fase 1 = básica, sin auth)

- **Q1 — Tests bloqueantes:** `./mvnw test` verde obligatorio. Por use case + `webmvc-test` por controller. Meta ≥80% en `domain`+`application` (JaCoCo cuando se añada).
- **Q2 — Contratos:** todo `spec.md` con `Dado/Cuando/Entonces`; todo `plan.md` con estrategia de pruebas; todo `tasks.md` con verificación por tarea (comando exacto).
- **Q3 — Seguridad básica:** CORS allowlist por env (no `*` en prod), headers (`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`), CSRF deshabilitado por API stateless sin cookies (documentado en plan 004), validación Bean Validation en todo input, límite `10MB` por request ingesta, sin PII (tokens, emails completos) en logs.
- **Q4 — Revisión:** PR <400 líneas, descripción con `Derivado de: spec.md RF-X + plan.md §Y`, CI verde, un aprobador.
- **Q5 — Estilo:** Java 21 idiomático (records), inglés en código/commits, español en docs SDD. Se comenta el porqué, no el qué.

## 5. Fases de Producto (ratificado)

- **Fase 1 (MVP Hackathon, actual):** ingesta Discord vía bot JDA + ingesta Telegram vía bot TelegramBots long polling (el backend es ambos bots) + análisis IA + generación multicanal + buffer Redis por tamaño + guardado OCI batch + Actuator + Swagger + CORS. **Toda la integración AI entra aquí.** Webhook Telegram en Fase >1.
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
