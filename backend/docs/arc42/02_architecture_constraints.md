# 02. Restricciones de la Arquitectura

> Fuente: `docs/sdd/constitution.md` v1.0-final + `pom.xml` + `src/`. Estado: **vigente**.

## 2.1 Restricciones técnicas

| # | Restricción                                                                                                                                                        | Origen |
|---|--------------------------------------------------------------------------------------------------------------------------------------------------------------------|---|
| CT-1 | Java 21 LTS, sin APIs preview                                                                                                                                      | `pom.xml: java.version` |
| CT-2 | Spring Boot 4.1.1; módulos Fase 1: `webmvc`, `restclient`, `validation`, `actuator`, `springdoc-openapi`                                                           | Constitución §2 |
| CT-3 | Build Maven Wrapper 3.9.x (`./mvnw test`, `./mvnw spring-boot:run`)                                                                                                | `pom.xml`, `.mvn/` |
| CT-4 | **Sin persistencia relacional en Fase 1**: prohibidos JPA, Flyway/Liquibase, H2/PostgreSQL/Mongo, `@Entity`, `JpaRepository`                                       | Constitución §2/R1 |
| CT-5 | Único storage: OCI Object Storage (Always Free), solo vía `ArtifactStorePort` en `infrastructure/`                                                                 | Constitución §2/R1 |
| CT-6 | IA solo con Spring AI (OpenAI primario), solo en `infrastructure/` tras `AnalyzePort` / `GeneratePort`                                                             | Constitución §2 |
| CT-7 | **Sin auth en Fase 1**: prohibidos JWT, sesiones, roles. Solo seguridad básica (CORS allowlist, headers, CSRF off por API stateless, Bean Validation, límite 10MB) | Constitución §2/Q3 |
| CT-8 | Fuentes `DISCORD`/`TELEGRAM` y canales `LINKEDIN`/`X`/`NEWSLETTER`/`FAQ` únicamente. Formato de ingesta: JSON único (CSV descartado en spec 001)                   | Constitución R8, spec 001 |
| CT-9 | Secretos solo por entorno (`OPENAI_API_KEY`, `OCI_*`, `CORS_ALLOWED_ORIGINS`). Nunca en git                                                                         | Constitución R4 |

## 2.2 Restricciones organizativas y de proceso

| #    | Restricción                                                                                                                                                                                                                                                                                        | Origen          |
|------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------|
| CO-1 | MVP Hackathon Oracle Next Education ONE — Grupo 10 - Equipo 03. Fase 1 = pipeline IA completo; Fase >1 = auth, usuarios, dashboard, bots directos                                                                                                                                                  | Constitución §5 |
| CO-2 | Sync a `main` solo vía workflow `sync backend to main` (`subtree` a `backend/`). Prohibido push directo a `main`                                                                                                                                                                                   | Constitución R7 |
| CO-3 | Proceso SDD obligatorio: `spec → plan → tasks → código`, con trazabilidad explícita                                                                                                                                                                                                                | Constitución P5 |
| CO-4 | Plazo: 4 semanas de desarrollo. Todo scope fuera de 001–004 queda en Fase >1 por plazo. Semana 4 solo estabilización/demo                                                                                                                                                                         | Equipo          |
| CO-5 | Presupuesto: $0. Solo tiers gratuitos (OCI Always Free + LLM por env). Sin embeddings/vector DB ni servicios pagos en Fase 1; si una cuota gratuita se agota, se reduce consumo (lotes menores, 1 reintento) — sin proveedor alternativo en Fase 1                                                                                          | Equipo          |
| CO-6 | Datos académicos con anonimato parcial. Uso académico/del proyecto. Al LLM se envían texto, tipo y canal; nunca `author` ni identificadores. El contenido del mensaje no se filtra: si incluye datos sensibles, viajan al proveedor (posible uso para entrenamiento). El equipo no se responsabiliza por el contenido | Equipo          |

## 2.3 Convenciones

- Arquitectura hexagonal estricta: `interfaces → application → domain`; `infrastructure` implementa puertos
  (`com.nocountry.simulation.communitylab.{domain,application,infrastructure,interfaces}`). Núcleo sin Spring/JPA.
- API-first: todo endpoint con OpenAPI/Swagger + test `webmvc-test`. Sin Swagger, sin merge.
- Commits convencionales en inglés, un cambio lógico por commit; PRs < 400 líneas.
- Idioma: español en documentación, inglés en código y commits.
- Prohibido el adjetivo sin métrica (`p95`, `%`, conteos) en requisitos y ADRs.
