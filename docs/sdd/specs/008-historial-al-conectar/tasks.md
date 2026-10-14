# Tasks 008: Historial al conectar (snapshot por SSE)

> Secuencia: `flush ok → package.completed + PackageIndex.record`; `GET SSE → :connected → replay (Last-Event-ID conocido) o snapshot (índice vigente → pendientes → lote abierto) → snapshot.completed → vivo`. Mismos eventos e `id` que el vivo; el frontend actualiza por `id`. Ventana 3 días (PAR vigentes). Sin endpoints nuevos. Logs solo `source` + contadores, nunca la PAR.
> Orden: TASK-008-00 → 01 → 02 → 03 → 04 → 05 → 06. Los tests de cada tarea se escriben después de implementarla.

- [x] **TASK-008-00**: `sdd-audit` de 008 antes de codificar.
  - Revisar spec/plan contra constitución v1.9, 004 RF-08 (enmendado), 006 RF-03..RF-06 y el código actual (`SseEventPublisherAdapter`, `PackageRunService`, `RedisBufferAdapter`, controllers SSE).
  - *Hecho 2026-10-14:* sin bloqueantes. Verificado: `RedisConfig` usa `StringRedisSerializer` también para claves y valores de HASH (el índice guarda JSON como texto, igual que el buffer); `PackageCompleted` es un record serializable por el `ObjectMapper` de Jackson 3 (`Instant` como texto ISO-8601); `PackageRunService.upload` emite `publishPackage` solo con `result.ok()`, que cubre `STORED` y `ALREADY_STORED` (RF-03); los controllers SSE solo llaman `publisher.subscribe(source, lastEvent)`. Ajustes: (1) `BufferPort` tiene una sola implementación (`RedisBufferAdapter`), no hay adapter degradado que tocar en TASK-008-03; (2) el índice captura `DataAccessException` (no solo `RedisConnectionFailureException`), como `RedisBufferAdapter.appendToBatch` (`:97-99`): un timeout de Redis no es un fallo de conexión pero tampoco debe escapar.
  - *Derivado de:* `constitution.md §6.4` + `spec.md §6`
  - *Verificación:* hallazgos registrados aquí y sin bloqueantes abiertos.
- [x] **TASK-008-01**: Índice de paquetes vigentes.
  - Puerto `PackageIndex` (`record`, `active(source, now)`) + `RedisPackageIndexAdapter` (HASH `packages:{SOURCE}`, upsert por `batchId`, limpieza de vencidas al leer, orden por `expiresAt`, degradado si Redis cae).
  - *Hecho 2026-10-14:* `application/port/out/PackageIndex` + `infrastructure/adapters/out/index/RedisPackageIndexAdapter` (`HashOperations<String, String, String>`; `DataAccessException` en lectura y escritura; entrada corrupta → se salta sin borrar; vigente = `expiresAt` posterior a `now`). Tests: `RedisPackageIndexAdapterTest` (9). Suite: 419 verdes. *Pendiente de estilo:* mensaje `package index entry skipped` en la entrada corrupta, comentarios "Why" de `record` y del orden, espacios.
  - *Derivado de:* `spec.md RF-03,RF-06,RF-07,RNF-04 + plan.md §1.1,§2,§3`
  - *Verificación:* `./mvnw test -Dtest=RedisPackageIndexAdapterTest` + `./mvnw test`
- [x] **TASK-008-02**: Registrar cada paquete anunciado en el índice.
  - `PackageRunService.upload`: `packageIndex.record(completed)` tras `publishPackage`, en su propio `try/catch` (`log.warn` con `batchId/source`, sin relanzar).
  - *Hecho 2026-10-14:* `PackageIndex` como último parámetro del constructor. Tests: `PackageRunServiceTest` (+3: orden aviso → índice con el mismo `PackageCompleted`, re-flush con PAR nueva, fallo del índice sin `upload failed` ni vuelta a pendientes; +2 aserciones: sin subida no se indexa) y `PackageRunServiceStartupTest` (bean `PackageIndex`). Suite: 422 verdes. *Pendiente de estilo:* comentario "Why" del `try` y espacios. *Nota:* si `publishPackage` lanzara, el paquete no se indexa (hoy el adapter SSE captura por emitter); registrar antes del aviso lo blindaría.
  - *Derivado de:* `spec.md RF-03,RF-07 + plan.md §1.1,§3`
  - *Verificación:* `./mvnw test -Dtest=PackageRunServiceTest` + `./mvnw test`
- [x] **TASK-008-03**: Leer el lote abierto.
  - `BufferPort.readOpen(Source)` + implementación en `RedisBufferAdapter` (mismo manejo de errores que `readSealed`) y en el adapter degradado si lo hay.
  - *Hecho 2026-10-14:* `readOpen` lee `buffer:{SOURCE}:current:list` (`LRANGE 0 -1`, orden del `RPUSH`), salta registros corruptos con `log.warn` solo con `source` y captura `DataAccessException` (también timeout). Sin adapter degradado (TASK-008-00). Tests: `RedisBufferAdapterTest` (+5; el de orden detectó un `return` dentro del bucle antes de integrarlo). Suite: 427 verdes. *Pendiente de estilo:* comentario del método en `BufferPort` (y colocarlo junto a `readSealed`), espacios.
  - *Derivado de:* `spec.md RF-01,RF-07 + plan.md §1.1`
  - *Verificación:* `./mvnw test -Dtest=RedisBufferAdapterTest` + `./mvnw test`
- [x] **TASK-008-04**: Caso de uso del historial.
  - `History` (dto), `LoadHistoryUseCase` + `LoadHistoryService` (orden de lectura abierto → pendientes → índice; `History.empty()` ante fallo) y conversión única `EnrichedComment → ResponseClient`, usada también por `EnrichmentListener`.
  - *Hecho 2026-10-14:* `History` (copias inmutables + `empty()`), `LoadHistoryUseCase`, `LoadHistoryService`; `ResponseClient.from` y su uso en `EnrichmentListener` escritos por Claude con autorización expresa del dev para esas piezas. Tests: `LoadHistoryServiceTest` (7), `ResponseClientTest` (2: cada campo sale de su homónimo por reflexión; sin campos internos). Suite: 436 verdes. *Pendiente de estilo:* comentario "Why" del orden de lectura en `LoadHistoryService`; indentación de `:54-58`.
  - *Derivado de:* `spec.md RF-01,RF-06,RF-07 + plan.md §1.1,§3`
  - *Verificación:* `./mvnw test -Dtest=LoadHistoryServiceTest,EnrichmentListenerTest` + `./mvnw test`
- [x] **TASK-008-05**: Snapshot en el stream SSE.
  - `SseEventPublisherAdapter.subscribe(source, lastEventId, Supplier<History>)`: replay si el `id` es conocido, si no snapshot; `snapshot.completed` sin `id` con contadores; log solo `source` + contadores. Controllers: inyectan `LoadHistoryUseCase` y pasan el supplier. Swagger de ambos `GET` con el bloque inicial y `snapshot.completed`.
  - *Hecho 2026-10-14 (escrito por Claude con autorización expresa del dev):* `subscribe(source, lastEventId, Supplier<History>)` con un único `try` para todo el bloque inicial; `canReplay` (id en la cola) → `replayAfter` que cuenta `package.completed`/`asset.created`, si no `sendHistory(history.get())`; `snapshot.completed` sin `id`; `assetEvent`/`packageEvent`/`send` compartidos con el vivo; log `sse subscribed: source, packages, pending`. Controllers inyectan `LoadHistoryUseCase` y pasan el supplier. Swagger: descripción del bloque inicial + ejemplo `snapshot.completed`. Tests: `SseEventPublisherAdapterTest` (+9, 3 ajustados al bloque inicial), controllers (+1 cada uno: supplier perezoso de su fuente), `SwaggerDocsTest` (+1). Suite: 448 verdes.
  - *Derivado de:* `spec.md RF-01,RF-02,RF-04,RF-05,RF-06,RNF-04 + plan.md §1.1,§2,§3`
  - *Verificación:* `./mvnw test -Dtest=SseEventPublisherAdapterTest,GetMessagesProcessedDiscordTest,GetMessageProcessedTelegramTest,SwaggerDocsTest` + `./mvnw test`
- [ ] **TASK-008-06**: Despliegue, verificación y contrato con el frontend.
  - Comunicar al frontend: bloque inicial, `snapshot.completed`, actualizar por `id`, `fetch(parUrlBase)` sin `credentials: "include"`.
  - VM: `curl -N` sin `Last-Event-ID` → bloque inicial completo; segunda pestaña → mismo historial; `docker compose restart backend` → historial conservado; medir el montaje del snapshot (RNF-03).
  - *Derivado de:* `spec.md RF-01..RF-05,RNF-01,RNF-03 + plan.md §5`
  - *Verificación:* `./mvnw test` + checks manuales anteriores
