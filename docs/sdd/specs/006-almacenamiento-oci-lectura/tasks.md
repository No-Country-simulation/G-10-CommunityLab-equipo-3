# Tasks 006: Almacenamiento OCI opción A + lectura directa PAR

> Secuencia: `buffer Redis por bytes (004 intacto) → flush 1 PUT paqueter/paquete-{batchId}.json → 1 CreatePAR read → SSE package.completed {batchId, parUrlBase, expiresAt} → frontend GET directo a OCI`. `1 lote = 1 objeto = 1 PAR`. TTL solo vence la URL, el objeto persiste. Backend solo escribe (Instance Principal); frontend solo `GET`. Anonimato solo ante el LLM; OCI conserva usuario; logs prod solo `messageId/batchId/source`, PAR nunca en logs.

- [ ] **TASK-006-00**: Añadir `com.oracle.oci.sdk:oci-java-sdk-objectstorage` y verificar compatibilidad `Boot 4.1.1 + Java 21`. Sin tocar código de negocio; solo resolución de dependencias.
  - *Derivado de:* `spec.md RNF-02 + plan.md §3 (SDK tras puerto)`
  - *Verificación:* `./mvnw test` compila en verde
- [ ] **TASK-006-01**: Crear `domain` `AssetPackage{batchId lote, generatedAt, source: DISCORD|TELEGRAM, assets[], enriched[] con authorId/authorName/channelId/messageId, stats{received, analyzed, fallbackCount, assetsCount}, promptVersion: "v1"}` + `PackageStats` + validación `<1048576 bytes`, `assets[]` no vacío, `relevance clamp 0..100`, `topics/hashtags null→[]`. Puro, sin Spring/JPA (R1-R3). Dueño único 006 (`Asset` vive en 003).
  - *Derivado de:* `spec.md RF-01,RNF-01 + plan.md §1`
  - *Verificación:* `./mvnw test -Dtest=AssetPackageTest`
- [ ] **TASK-006-02**: Crear `application` `ports/in PackageRunUseCase.flush + ports/out ArtifactStorePort{put, exists, createReadPar, listPars} + extender BufferPort{flushIfNeeded, readBatch, rotate}` + `services BufferService + PackageRunService` (solo Java sin Lua, `synchronized`: dedup `SADD messageId` → `RPUSH` → `INCRBY` → si `>=900KB`: `LRANGE` → envelope → `put` → `exists? deduped:true` → `listPars? reuse deduped:true : createReadPar 7d` → `SSE` → `DEL + nuevo uuid`; `LLM_FALLBACK` auditable entra, vacío/post-inválido aborta; OCI-fail solo `LOG` sin `package.completed`).
  - *Derivado de:* `spec.md RF-01,RF-03,RF-05,RNF-01 + plan.md §1,§2`
  - *Verificación:* `./mvnw test -Dtest=BufferServiceTest,PackageRunServiceTest`
- [ ] **TASK-006-03**: Crear `infrastructure` `OciObjectStorageAdapter` (único que toca SDK: `PutObject + GetNamespace auto + CreatePreauthenticatedRequest ObjectRead sin listing + ListPreauthenticatedRequests` solo en re-flush, `Content-Type: application/json`, keys `paquetes/paquete-{batchId}.json`) + `OciConfig` (`InstancePrincipalsAuthenticationDetailsProvider` en prod, desactivado en local/test → `OCI_NOT_CONFIGURED` con SSE vivo intacto) + completar `RedisBufferAdapter` (`LRANGE/DEL/nuevo uuid`, env `REDIS_HOST/PORT + REDIS_BUFFER_MAX_BYTES=921600`) + config no-secreta `${OCI_BUCKET/OCI_REGION/OCI_NAMESPACE/OCI_PREFIX/PAR_TTL_DAYS}` sin `*.pem/*.key/.oci/` (R4). Docker VM con egress a `169.254.169.254` + `objectstorage.{region}.oraclecloud.com`.
  - *Derivado de:* `spec.md RF-02,RNF-02,RNF-05 + plan.md §1,§3`
  - *Verificación:* `./mvnw test -Dtest=OciObjectStorageAdapterTest,RedisBufferAdapterTest`
- [ ] **TASK-006-04**: Extender `SseEventPublisherAdapter` a `package.completed {type, batchId, payload:{assetsCount, parUrlBase, expiresAt ISO-8601}}` por `source` (`GET /api/v1/discord/messages` + `GET /api/v1/telegram/messages`, dual-get, `id=batchId`, `Last-Event-ID` replay intacto) + Swagger solo `GET SSE + health`, sin `GET /packages*`. Sin contenido ni PII ni PAR en logs.
  - *Derivado de:* `spec.md RF-04,RF-06,RNF-06 + plan.md §1,§2`
  - *Verificación:* `./mvnw test -Dtest=GetMessagesProcessedDiscordTest,GetMessageProcessedTelegramTest,HealthTest`
- [ ] **TASK-006-05**: Verificar demo + IAM/prod: dynamic-group + políticas (`OBJECT_CREATE + OBJECT_OVERWRITE + PAR_MANAGE + OBJECT_READ` solo para otorgar PAR) antes del primer flush; `sdd-audit` 006; `package.completed` con `parUrlBase` vigente; `GET parUrlBase → 200 application/json`; re-flush mismo `batchId` → `deduped:true` sin re-PUT ni `CreatePAR`; PAR expirada → error OCI sin exponer contenido; OCI-caído → `LOG + ⚠️` sin evento.
  - *Derivado de:* `spec.md RF-02,RF-05,§6 TBD + plan.md §4,§5`
  - *Verificación:* `./mvnw test` + `./mvnw spring-boot:run` (Redis Docker): por mensaje `asset.created + RPUSH`; a `900KB` → flush + `package.completed + ✅` en su `source`; `curl GET parUrlBase` manual `200`; re-flush `deduped:true`; sin `GET /packages*`
