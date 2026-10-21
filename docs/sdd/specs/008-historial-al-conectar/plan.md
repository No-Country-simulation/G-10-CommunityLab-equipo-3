# Plan 008: Historial al conectar (snapshot por SSE)

Derivado de: `spec.md RF-01..RF-07 + RNF-01..RNF-04` + `constitution.md v1.9-revision-versionada P1-P3,R1-R6,Q1-Q3` + `004 RF-06/RF-08` + `006 RF-03..RF-06 + plan 006 §1.1,§3`.
Alcance: `flush ok → PackageRunService → PackageIndex.record (Redis)`; `GET SSE → controller → SseEventPublisherAdapter.subscribe(source, lastEventId, historySupplier) → :connected → replay o snapshot (LoadHistoryUseCase: lote abierto + pendientes + índice) → snapshot.completed → vivo`. Sin endpoints nuevos ni cambios en el formato de `asset.created` / `package.completed`.

## 1. Arquitectura y componentes

```mermaid
flowchart LR
  FLUSH[PackageRunService upload] -->|package.completed| SSE[SseEventPublisherAdapter]
  FLUSH -->|record PackageCompleted| IDX[PackageIndex]
  IDX -.implementa.-> RIDX[RedisPackageIndexAdapter HASH packages:SOURCE]
  FE[Dashboard] -->|GET /api/v1/source/messages| WEB[GetMessagesProcessed* controller]
  WEB -->|subscribe source, lastEventId, () -> load source| SSE
  SSE -->|Last-Event-ID desconocido o ausente| UC[LoadHistoryUseCase]
  UC --> SVC[LoadHistoryService]
  SVC -->|readOpen + pendingBatches + readSealed| BUF[BufferPort / RedisBufferAdapter]
  SVC -->|active source, now| IDX
  SSE -->|:connected, package.completed x N, asset.created x M, snapshot.completed| FE
  FE -->|GET parUrlBase| OCI[(Bucket OCI)]
```

### 1.1 Componentes

* `application/dtos/History`: `record History(List<PackageCompleted> packages, List<ResponseClient> pending)` + `History.empty()`. `PackageCompleted` ya enmascara la PAR en `toString` (no se loguea).
* `application/port/out/PackageIndex`: `void record(PackageCompleted completed)` y `List<PackageCompleted> active(Source source, Instant now)` (vigentes, ordenados por `expiresAt` ascendente, que equivale a orden de creación porque el TTL es fijo).
* `application/port/out/BufferPort` + `List<EnrichedComment> readOpen(Source source)`: lee `buffer:{SOURCE}:current:list` igual que `readSealed` (registro que no deserializa → se salta con `log.warn`; Redis caído → `List.of()`).
* `application/port/in/LoadHistoryUseCase` + `application/services/history/LoadHistoryService`: `History load(Source source)`. Lee en el orden de §3 (lote abierto → pendientes → índice) y devuelve `packages` + `pending` (pendientes primero, después el lote abierto). Cualquier `RuntimeException` → `History.empty()` + `log.warn` con `source` (RF-07).
* Conversión `EnrichedComment → ResponseClient`: hoy está inline en `EnrichmentListener` (`:96-117`). Se extrae a un único sitio en `application` (p. ej. fábrica estática `ResponseClient.from(EnrichedComment)`) y la usan `EnrichmentListener` y `LoadHistoryService`, para que el snapshot y el vivo no diverjan.
* `application/services/storage/PackageRunService.upload`: tras `packagePublisher.publishPackage(completed)`, `packageIndex.record(completed)` en su propio `try/catch` (`log.warn` con `batchId/source`, sin relanzar: RF-07).
* `infrastructure/adapters/out/index/RedisPackageIndexAdapter implements PackageIndex`: key `packages:{SOURCE}` (HASH), campo `batchId`, valor JSON `{batchId, source, assetsCount, parUrlBase, expiresAt}`. `record` = `HSET` (sustituye en re-flush, RF-03). `active` = `HGETALL` → deserializa → descarta y `HDEL` las vencidas → ordena por `expiresAt`. Redis caído → `record` no lanza (`log.warn`), `active` → `List.of()`.
* `infrastructure/adapters/out/event/SseEventPublisherAdapter.subscribe(Source, String lastEventId, Supplier<History> history)`:
  1. registra el emitter y sus callbacks (como hoy);
  2. `:connected` (como hoy);
  3. si `lastEventId` está en la cola de replay de su fuente → `replayAfter` (como hoy) y contadores del replay;
  4. si no (ausente o desconocido) → `history.get()` y envía cada `package.completed` (`id=batchId`, mismo `PackageCompletedEvent` que `publishPackage`) y cada `asset.created` (`id=messageId`, mismo `data` que `publish`);
  5. `snapshot.completed` sin `id` con `{type, packages, pending}`;
  6. un fallo de envío en cualquier paso → quita el emitter y sale (mismo patrón que `:connected`).
  Log de la suscripción: solo `source`, `packages`, `pending` (RNF-04).
* `infrastructure/adapters/in/web/GetMessagesProcessedDiscord` / `GetMessageProcessedTelegram`: inyectan `LoadHistoryUseCase` y llaman `publisher.subscribe(Source.X, lastEvent, () -> loadHistory.load(Source.X))`. Sin lógica: la decisión replay/snapshot vive en el adapter (depende de su cola en memoria) y la lectura del historial en `application`. Swagger de ambos `GET`: documentar el bloque inicial y `snapshot.completed` (API-first).

## 2. Contratos de Datos / Interfaces

* SSE (por fuente), bloque inicial sin `Last-Event-ID` o con uno desconocido:
  ```text
  :connected

  id:b-101
  event:package.completed
  data:{"type":"package.completed","batchId":"b-101","payload":{"assetsCount":3,"parUrlBase":"https://…","expiresAt":"2026-10-17T10:00:00Z"}}

  id:m-871
  event:asset.created
  data:{…ResponseClient…}

  event:snapshot.completed
  data:{"type":"snapshot.completed","packages":1,"pending":1}
  ```
* Con `Last-Event-ID` conocido: `:connected` + eventos posteriores del replay + `snapshot.completed {packages, pending}` con lo repetido (`packages` = `package.completed` repetidos, `pending` = `asset.created` repetidos).
* Redis: `packages:{SOURCE}` HASH, `batchId → JSON`. Sin TTL de key (cada campo vence por su `expiresAt`, limpiado al leer).
* El frontend actualiza por `id` (spec RF-05).

## 3. Decisiones técnicas y trade-offs

* Decisión: el historial viaja por el `GET` SSE existente como bloque inicial.
  Alternativa descartada: `GET /api/v1/{source}/history` (choca con constitución v1.6/v1.7, sin `GETs packages`; dos llamadas que el frontend tendría que coordinar sin perder eventos entre ambas).
  Razón: una sola conexión garantiza que el historial y el vivo llegan en orden por el mismo canal, sin enmienda constitucional.
* Decisión: índice en Redis como HASH por fuente (`HSET` por `batchId`, limpieza de vencidas al leer).
  Alternativa descartada: `ZSET` por `expiresAt` + HASH de datos (dos estructuras que mantener sincronizadas); `HEXPIRE` por campo (Redis 7.4+, disponible en `redis:8.10`, pero añade una API que el código aún no usa, para limpiar como mucho ~168 entradas con `PAR_TTL_DAYS=7`, ~72 cuando eran 3 días); listar el bucket en OCI (IAM sin `OBJECT_INSPECT` y PAR nueva por lectura).
  Razón: una sola estructura, upsert natural en re-flush (RF-03) y tamaño acotado. Persiste con el AOF que ya protege el buffer.
* Decisión: orden de lectura lote abierto → pendientes → índice; orden de envío índice → pendientes → lote abierto.
  Alternativa descartada: leer en el orden de envío.
  Razón: sin lock, un sellado o un flush pueden ocurrir entre lecturas. Leyendo primero lo más "joven", un mensaje que cambia de estado durante el montaje aparece dos veces (el frontend actualiza por `id`, RF-05) en vez de ninguna.
* Decisión: `Supplier<History>` como parámetro de `subscribe`.
  Alternativa descartada: el controller decide replay/snapshot (lógica en el adapter de entrada, prohibido); el adapter SSE inyecta `LoadHistoryUseCase` (un adapter de salida llamando a un puerto de entrada).
  Razón: el adapter decide con su cola en memoria si hace falta historial y solo entonces lo pide; `application` no conoce el SSE y el controller solo cablea.
* Decisión: `Last-Event-ID` desconocido → snapshot completo.
  Alternativa descartada: no enviar nada (comportamiento actual: tras un redeploy el dashboard se queda sin historial hasta recargar sin cabecera).
  Razón: `EventSource` siempre reconecta con su último `id`; tras un reinicio ese `id` ya no está en memoria y sin esta regla el usuario perdería el historial justo cuando el backend vuelve.
* Decisión: `snapshot.completed` siempre, sin `id`.
  Razón: el frontend sabe cuándo quitar el estado de carga en cualquier caso (snapshot o replay); sin `id` no altera la reconexión.
* Decisión: el índice se escribe tras `publishPackage`, fuera de la condición de éxito del flush.
  Razón: el índice es una vista para lectores tardíos; si falla, el paquete sigue en OCI y el vivo ya se emitió. No debe devolver el lote a pendientes (RF-07).

## 4. Estrategia de pruebas

Los tests se escriben tras implementar cada tarea.

* Unit `RedisPackageIndexAdapterTest` (`RedisTemplate` mockeado o el patrón de `RedisBufferAdapterTest`): `record` hace upsert por `batchId`; `active` excluye y borra vencidas, ordena por `expiresAt`, aísla por fuente; Redis caído → `record` no lanza, `active` vacío.
* Unit `RedisBufferAdapterTest` (+): `readOpen` lee `current:list`, salta registros corruptos, Redis caído → vacío.
* Unit `LoadHistoryServiceTest` (puertos mockeados): pendientes antes que el lote abierto; paquetes del índice; orden de lectura abierto → pendientes → índice (`InOrder`); fallo de un puerto → `History.empty()`.
* Unit `PackageRunServiceTest` (+): flush correcto → `record` tras `publishPackage`; `record` lanza → no relanza, lote descartado igual; flush fallido → sin `record`.
* `SseEventPublisherAdapterTest` (+, MockMvc standalone como hoy): sin `Last-Event-ID` → orden exacto `:connected → package.completed → asset.created → snapshot.completed`; `snapshot.completed` sin `id` y con contadores; `Last-Event-ID` conocido → solo replay, sin llamar al supplier; desconocido → snapshot; supplier vacío → contadores `0`; aislamiento por fuente; log sin PAR.
* `GetMessagesProcessedDiscordTest` / `GetMessageProcessedTelegramTest` (+): el controller pasa su `Source` al caso de uso. `SwaggerDocsTest` (+): descripción con `snapshot.completed`.
* `ResponseClient.from` (o equivalente): mismo resultado campo a campo que la construcción actual de `EnrichmentListener`.

## 5. Verificación

* `./mvnw test -Dtest=RedisPackageIndexAdapterTest,RedisBufferAdapterTest,LoadHistoryServiceTest,PackageRunServiceTest,SseEventPublisherAdapterTest,GetMessagesProcessedDiscordTest,GetMessageProcessedTelegramTest,SwaggerDocsTest` + `./mvnw test`.
* VM: con al menos un paquete vigente y mensajes en el lote abierto, `curl -N` al stream sin `Last-Event-ID` → bloque inicial completo + `snapshot.completed`; segunda pestaña del navegador → mismo historial; `docker compose restart backend` → el snapshot conserva los paquetes vigentes y el lote abierto.
