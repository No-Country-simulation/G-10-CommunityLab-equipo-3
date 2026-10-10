# Tasks 007: Aprobación y edición versionada de posts

> Secuencia final: `PATCH /api/v1/{source}/packages/{batchId}/messages/{messageId} → GetObject (paquete + etag) → AssetPackage.revise (reglas de canal; toda actualización guardada sube versionMessage, messageId nunca cambia) → PutObject If-Match → OCI conserva la versión anterior → SSE message.revised → frontend GET parUrlBase (última versión)`. El flush de 006 no cambia (create-only). Sin auth (Fase >1); logs solo `source/batchId/messageId/versionMessage`.
> Editables: `approved` + `topics, channelPost, titlePost, outputContentProcessed, hashtags, cta`. No editables: `language, sentiment, messageType, relevance, messageAuthor` + datos de origen.
> **E1 (2026-10-09):** TASK-007-01 + TASK-007-02 — atributos y endpoint `202` sin persistir. **E2:** TASK-007-00, 03..07 — guardado versionado en OCI.

## E1 — atributos y endpoint

- [x] **TASK-007-01**: Atributos `approved` + `versionMessage` en `EnrichedComment`.
  - Componentes `Boolean approved`, `Integer versionMessage` (wrappers, plan §1.1: con `boolean/int` los registros ya en Redis sin estos campos fallan al deserializar y `readSealed` los descarta); compact constructor `null → false` y `null/<1 → 1`; `ConvertEnrichedCommentService` construye con `false, 1`; ajustar el resto de construcciones (tests incluidos).
  - *Derivado de:* `spec.md RF-01 + plan.md §1.1 (domain),§3`
  - *Verificación:* `./mvnw test -Dtest=EnrichedCommentTest,ConvertEnrichedCommentServiceTest,RedisBufferAdapterTest` + `./mvnw test`
- [x] **TASK-007-02**: Endpoint `PATCH` por fuente que recibe y valida la revisión (`202`).
  - `spring-boot-starter-validation`; `PostChange` (solo campos editables); `RevisePostUseCase` + `RevisePostCommand`; `RevisePostService` E1 (solo log de ids, sin persistir); `PatchPost` (un controller, `{source:discord|telegram}` en la ruta); `RevisePostRequest` con Bean Validation + `@JsonAnySetter` para rechazar campos no editables + `@AssertTrue` "al menos un cambio"; `RevisePostAcceptedResponse{batchId, messageId}`; `GlobalExceptionHandler` (`400`); `SecurityConfig` `permitAll` `PATCH` solo en las 2 rutas; `CorsConfig` `PATCH` + `Content-Type`; Swagger del `PATCH`.
  - *Hecho 2026-10-09:* DTOs en `infrastructure/dto/revision/` (junto a `dto/ai`); `GlobalExceptionHandler` en `infrastructure/exception/` (`ProblemDetail`, solo nombre de campo + mensaje, nunca el valor). `PatchPostTest` (26) corre con la cadena de seguridad real: `202` por fuente, `400` (sin `expectedVersion`, sin cambios, 7 campos no editables, listas, texto largo sin eco, enum inválido sin eco, JSON roto), `415`, `403` (fuente fuera de R8 y otro método), preflight CORS. `SwaggerDocsTest` +2. Suite: 241 verdes.
  - *Pendiente de estilo:* renombrar `RevisionPostService` → `RevisePostService` (+ comentario del porqué); indentación de `PostChange`.
  - *Derivado de:* `spec.md RF-02,RNF-01,RNF-03,RNF-04 + plan.md §1.1 (application, web, security),§2,§3` + `constitution.md P3,Q3`
  - *Verificación:* `./mvnw test -Dtest=PatchPostTest,SwaggerDocsTest` + `./mvnw test` + manual `curl -i -X PATCH` → `202`; con `"sentiment"` → `400`

## E2 — guardado versionado en Object Storage

- [ ] **TASK-007-00**: Preparar el bucket y verificar los requisitos de OCI (antes de codificar E2).
  - Activar Object Versioning en `MessagesUsers` (consola o `oci os bucket update --bucket-name MessagesUsers --versioning Enabled`); verificar con `oci os bucket get` → `versioning: Enabled`.
  - Comprobar con CLI (perfil `session-token`) los tres requisitos de OCI de los que depende el diseño (spec RF-05/RF-07): (1) `put` dos veces al mismo nombre → `list-object-versions` muestra 2; (2) `put --if-none-match '*'` sobre un objeto existente → `412` (el flush de 006 sigue siendo create-only con versioning); (3) una PAR `ObjectRead` creada antes del segundo `put` devuelve la última versión con `curl`.
  - Crear la regla de lifecycle `PREVIOUS_OBJECT_VERSIONS` → `DELETE` a los `10` días sobre `paquetes/` y verificarla con `oci os object-lifecycle-policy get`.
  - `sdd-audit` de 007.
  - *Derivado de:* `spec.md RF-05,RF-07,§6 + plan.md §1.2 (OCI),§3` + `AGENTS.md` (sdd-audit antes de cada spec)
  - *Verificación:* salida de los 3 comandos CLI anotada en esta tarea + `sdd-audit` sin hallazgos bloqueantes
- [ ] **TASK-007-03**: Dominio de revisión y generación de `versionMessage`.
  - `EnrichedComment.revise(PostChange)` (toda actualización guardada `versionMessage + 1`, aprobar incluido; editar conserva `approved`; `messageId` intacto; no-op; revalida contra el `channelPost` final; `LLM_FALLBACK` no revisable); `AssetPackage.revise(messageId, PostChange)`; excepciones no encontrado / no revisable / conflicto.
  - *Derivado de:* `spec.md RF-03,RF-04 + plan.md §1.2 (domain)`
  - *Verificación:* `./mvnw test -Dtest=EnrichedCommentTest,AssetPackageTest`
- [ ] **TASK-007-04**: Caso de uso con persistencia.
  - `RevisePostUseCase` devuelve `RevisionResult`; `ArtifactStore.load/replace` + `StoredPackage` + `ReplaceResult`; `RevisePostService` (comprobación de `expectedVersion`, no-op sin escritura, 2 reintentos ante `CONFLICT`, evento solo si `REPLACED`).
  - *Derivado de:* `spec.md RF-02,RF-04,RF-06,RF-08 + plan.md §1.2 (application),§2,§3`
  - *Verificación:* `./mvnw test -Dtest=RevisePostServiceTest`
- [ ] **TASK-007-05**: Adapter OCI de lectura y reescritura.
  - `OciObjectStorageAdapter.load` (`GetObject`, guarda `<1MB`, Jackson → `AssetPackage`, etag, `404 → empty`) y `replace` (`PutObject` `ifMatch(etag)`, `412 → CONFLICT`, guarda `<1MB`); nombre del objeto calculado en un solo sitio para `save/load/replace`; `ArtifactStoreAdapter` (modo `none`) → `OCI_NOT_CONFIGURED`.
  - *Derivado de:* `spec.md RF-05,RF-06,RF-09,RNF-01,RNF-04 + plan.md §1.2 (infrastructure),§2`
  - *Verificación:* `./mvnw test -Dtest=OciObjectStorageAdapterTest,ArtifactStoreSelectionTest`
- [ ] **TASK-007-06**: Endpoint `200` + SSE `message.revised`.
  - `PATCH` pasa de `202` a `200 RevisePostResponse{batchId, messageId, versionMessage, approved}`; `GlobalExceptionHandler` añade `404/409/413/422/502/503`; `EventPublishPost` / `SseEventPublisherAdapter` con `message.revised {type, batchId, payload:{messageId, versionMessage, approved}}` por `source`, `id={messageId}:v{versionMessage}`, sin contenido; replay `Last-Event-ID` intacto.
  - *Derivado de:* `spec.md RF-02,RF-08,RF-09,RNF-04 + plan.md §1.2 (web),§2`
  - *Verificación:* `./mvnw test -Dtest=PatchPostTest,SseEventPublisherAdapterTest,GetMessagesProcessedDiscordTest,GetMessageProcessedTelegramTest`
- [ ] **TASK-007-07**: IAM y demo en VM (se suma a TASK-006-05).
  - Política del `dynamic-group` con `OBJECT_READ` + `OBJECT_OVERWRITE` sobre `MessagesUsers`; regla de lifecycle de 10 días aplicada.
  - Demo: aprobar un post → `200 versionMessage:2 approved:true` + `message.revised`; editar el texto → `versionMessage:3 approved:true`; cambiar `channelPost` con hashtags inválidos para el canal nuevo → `400`; editar con `expectedVersion` vieja → `409`; `GET parUrlBase` muestra la última versión; `list-object-versions` muestra el historial; `OCI_AUTH_MODE=none` → `503`.
  - *Derivado de:* `spec.md RF-05..RF-09,RNF-05 + plan.md §1.2 (OCI),§5`
  - *Verificación:* `./mvnw test` + pasos manuales de `plan.md §5` en la VM
