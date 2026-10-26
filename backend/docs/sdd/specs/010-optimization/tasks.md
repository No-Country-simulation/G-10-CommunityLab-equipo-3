# Tasks 010: Optimización de rendimiento (Redis, historial SSE, hilos y caché de paquetes)

> Secuencia: `mensaje → LLM (virtual thread, Semaphore) → EVAL append (1 viaje) → flush → OCI (pool oci-storage, 3 hilos de plataforma; cliente SDK 5 s / 20 s / 2 intentos)`; `reconexión SSE → SMEMBERS + pipeline + LRANGE + 1 HDEL`; `PATCH → pkgcache ? sin GET : GET (+ copia) → evict → PUT If-Match → copia nueva`.
> Orden: TASK-010-00 → 01 → 02 → 03 → 04 → 05. Implementado por Claude con autorización expresa y puntual del dev (2026-10-25); AGENTS.md no cambia.

- [ ] **TASK-010-00**: Enmienda constitucional + `sdd-audit` de 010.
  - Ratificar `constitution.md` v1.13-optimizacion (Lua para el append, copia de paquetes confirmados en Redis, virtual threads).
  - *Derivado de:* `constitution.md §6.3,§6.4` + `spec.md §1`
  - *Verificación:* spec/plan revisados contra v1.2, v1.8, v1.9 y 006/007/008 sin contradicciones abiertas.
- [x] **TASK-010-01**: Timeouts de Redis.
  - `RedisConfig` con `LettuceClientConfiguration` (`commandTimeout`, `SocketOptions.connectTimeout`); `spring.data.redis.timeout/connect-timeout` pasan de `application-dev.yaml` a `application.yaml`; `REDIS_TIMEOUT` opcional en el compose.
  - *Hecho 2026-10-25:* tests `RedisConfigTest` (3).
  - *Derivado de:* `spec.md RF-01 + plan.md §1.1`
  - *Verificación:* `./mvnw test -Dtest=RedisConfigTest`
- [x] **TASK-010-02**: Buffer atómico y pendientes en una lectura.
  - `appendToBatch` con script Lua; `discardSealed` en `MULTI/EXEC`; `BufferPort.readSealedAll` con pipeline; `LoadHistoryService` lo usa; `RedisPackageIndexAdapter` con un único `HDEL`.
  - *Hecho 2026-10-25:* `RedisBufferAdapterTest` reescrito en append/discard (+ `readSealedAll`, 31 en total), `LoadHistoryServiceTest` (+1), `RedisPackageIndexAdapterTest` (+1).
  - *Derivado de:* `spec.md RF-02,RF-03,RF-04,RF-05,RNF-01,RNF-02 + plan.md §2,§3`
  - *Verificación:* `./mvnw test -Dtest='RedisBufferAdapterTest,LoadHistoryServiceTest,RedisPackageIndexAdapterTest'`
- [x] **TASK-010-03**: Virtual threads y límite del LLM.
  - `spring.threads.virtual.enabled: true`; se elimina `spring.task.scheduling.pool.size`; `AnalyzeMessageLlmAdapter` con `llm.max-concurrency` (`${LLM_MAX_CONCURRENCY:3}`, mínimo 1).
  - *Hecho 2026-10-25:* `AnalyzeMessageLlmAdapterConcurrencyTest` (3: nunca más de N en curso, límite 1 serializa, `< 1` falla al arrancar).
  - *Derivado de:* `spec.md RF-07,RNF-03 + plan.md §3`
  - *Verificación:* `./mvnw test -Dtest=AnalyzeMessageLlmAdapterConcurrencyTest` + `contextLoads`
- [x] **TASK-010-04**: SDK de OCI en hilos de plataforma + copia confirmada del paquete.
  - Pool privado `oci-storage-*` (3) en `OciObjectStorageAdapter` + límites del cliente en `OciConfig` (`oci.connect-timeout/read-timeout/max-attempts`); `RedisPackageCache`; `ArtifactStore.loadFresh`; `storage.package-cache-ttl`.
  - *Hecho 2026-10-25:* `OciObjectStorageAdapterCacheTest` (12), `RedisPackageCacheTest` (8), mock del caché en `ArtifactStoreSelectionTest` y en los constructores de `OciObjectStorageAdapter*Test`.
  - *Corregido 2026-10-25 tras `/code-review high`:* (1) el flush ya no cachea (RNF-06); (2) cualquier fallo al leer la copia, incluida `InvalidAssetException`, es miss; (3) `replace` invalida **antes** del PUT, así un fallo posterior nunca deja la versión vieja (el camino "sin cambios" no pasa por `If-Match`); (4) pool de 3 (un `PATCH` no espera detrás de 2 flushes); (5) comentario del YAML corregido; (6) sin paquetes `>= 1MB` en caché; (7) lectura del cuerpo cortada en 1MB sin `Content-Length`. Tests: `OciObjectStorageAdapterCacheTest` 18, `RedisPackageCacheTest` 9; `ArtifactStoreSelectionTest` registra el `ConversionService` de Boot (`"30s"` → `Duration`, como la app real). Suite: 595 verdes.
  - *Derivado de:* `spec.md RF-06,RF-08,RNF-04,RNF-05,RNF-06 + plan.md §2,§3`
  - *Verificación:* `./mvnw test -Dtest='OciObjectStorageAdapter*Test,RedisPackageCacheTest,ArtifactStoreSelectionTest'`
  - *Corregido 2026-10-25 tras el segundo `/code-review high`:* (a) el tope de espera del `Future` se reemplaza por límites del **cliente SDK** (`ClientConfiguration`: 5 s conexión, 20 s lectura, 2 intentos con backoff ≤ 2 s), porque `cancel(true)` no detenía la llamada HTTP, el hilo seguía ocupado y un PUT que OCI aplicaba tarde se informaba como fallo; (b) la copia tras un GET usa `SET NX` (no pisa la versión de un `replace` concurrente); (c) `heartbeat()` con `tryLock` (con virtual threads `fixedRate` se solapaba); (d) el `HDEL` de limpieza del índice en su propio `try`. Verificado en JDK 21.0.11 (versión del proyecto; el `current` local era 25, donde el pinning de `synchronized` ya no existe). Tests: `OciConfigTest` (+3, incluida una petición HTTP real a un servidor que no responde: el SDK 3.97.0 corta solo y hace exactamente 2 intentos), `OciObjectStorageAdapterCacheTest` 17, `RedisPackageCacheTest` 11, `RedisPackageIndexAdapterTest` +1, `SseEventPublisherAdapterTest` +2 (helper de test `StalledSseClient`). Suite: 602 verdes.
- [x] **TASK-010-05**: Lectura desactualizada en la revisión.
  - `RevisePostService`: `expectedVersion > versionMessage` → una vez `loadFresh` antes del `409`.
  - *Hecho 2026-10-25:* `RevisePostServiceTest` (+3).
  - *Derivado de:* `spec.md RF-09 + plan.md §2,§4`
  - *Verificación:* `./mvnw test`
- [ ] **TASK-010-06**: Verificación en ejecución.
  - `./mvnw spring-boot:run` con Redis de compose: arranque `< 15s`; abrir el SSE con `curl -N` (`connected` → `snapshot.completed`); detener Redis y reconectar: respuesta degradada en ~2 s.
  - En la VM (JDK 21), los primeros días con `JAVA_TOOL_OPTIONS=-Djdk.tracePinnedThreads=short` (pasa al contenedor por `docker-compose-prod.yaml`): `docker compose -f docker-compose-prod.yaml logs backend | grep -A8 "reason:MONITOR"`. Cada traza (`VirtualThread[#N]... reason:MONITOR` + la línea marcada con `<== monitors`) es un virtual thread fijado y señala la clase que lo causa. Al arrancar, el log muestra `Picked up JAVA_TOOL_OPTIONS: -Djdk.tracePinnedThreads=short`, lo que confirma que el flag llegó a la JVM. Sin trazas con tráfico real → quitar la variable y redesplegar. El flag existe en JDK 21; en JDK 24+ desaparece (allí ya no hay pinning por `synchronized`).
  - Plan B sin cambiar código: `SPRING_THREADS_VIRTUAL_ENABLED=false` + reinicio vuelve a hilos de plataforma (el scheduler queda con 1 hilo: flush y heartbeat son cortos).
  - *Derivado de:* `spec.md RNF-01,RNF-02,RNF-03 + constitution.md R6`
  - *Verificación:* manual, en local y en la VM.
