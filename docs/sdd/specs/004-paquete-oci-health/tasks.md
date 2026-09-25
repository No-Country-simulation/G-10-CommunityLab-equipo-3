# Tasks 004: Paquete OCI batch + buffer Redis por tamaño + fork SSE vivo (`#Listen` only)

> Telegram fuera. Secuencia: `Bot #Listen → LLM por mensaje → etiquetas → fork: SSE inmediato asset.created + RPUSH Redis → flush OCI batch → SSE package.completed`. `LLM_FALLBACK`/vacío → abort sin SSE ni buffer. OCI-fail flush → `LOG + ⚠️` sin `package.completed` (vivo ya emitido). Anónimo total (solo `messageId/batchId/source`).

- [ ] **TASK-004-00**: Añadir dependencias `spring-data-redis + oci-java-sdk-objectstorage` + `actuator + springdoc-openapi-starter-webmvc-ui`, cerrar resto actuadores (`health,info` solo). Verificar compatibilidad Boot `4.1.1`. Redis local vía Docker.
  - *Derivado de:* `constitution v1.2 §2 P3,Q3 + plan.md §2`
  - *Verificación:* `./mvnw test` compila; `GET /actuator/health → {"status":"UP"}`
- [ ] **TASK-004-01**: Crear `domain` `AssetPackage{batchId=uuid lote Redis / newsletter-fecha, source:DISCORD, anon}` + `PackageStats{received=N, analyzed, assetsCount}` + validación `<1MB application/json` + threshold buffer `900KB`. Dueño único `AssetPackage` (`Asset` vive en 003).
  - *Derivado de:* `spec.md RF-01,RNF-01,RNF-04 + plan.md §1`
  - *Verificación:* `./mvnw test -Dtest=AssetPackageTest`
- [ ] **TASK-004-02**: Crear `application` `ports/in/PackageRunUseCase + GenerateNewsletterUseCase, ports/out/ArtifactStorePort(put/get/list) + ports/out/BufferPort(getCurrentBatchId, append, flushIfNeeded), services/BufferService + PackageRunService + GenerateNewsletterService` (fork post-LLM: `SSE inmediato + RPUSH`; `LLM_FALLBACK`/vacío nunca entra; flush por `bytes>=900KB` `synchronized` sin Lua; OCI-fail flush solo `LOG` sin `package.completed`; newsletter sorteo 15 desde OCI, abort si `<15`).
  - *Derivado de:* `spec.md RF-01,RF-02,RF-06,RF-09,RNF-01,RNF-04 + plan.md §1,§2`
  - *Verificación:* `./mvnw test -Dtest=BufferServiceTest,PackageRunServiceTest,GenerateNewsletterServiceTest`
- [ ] **TASK-004-03**: Crear `infrastructure` `RedisBufferAdapter` (`spring-data-redis` solo-Java `ListOps/SetOps`, keys `buffer:current:id/list/ids/bytes`, env `REDIS_HOST/PORT + REDIS_BUFFER_MAX_BYTES=921600`, degradado `REDIS_NOT_CONFIGURED`) + `OciObjectStorageAdapter` SDK (`put/get/list`, keys `paquetes/paquete-{batchId lote}.json` + `paquetes/newsletter-*.json`) + `OciConfig` env `OCI_BUCKET,OCI_REGION (+NAMESPACE/AUTH)` + degradado `OCI_NOT_CONFIGURED` + `NewsletterScheduler (@Scheduler cron 5d prod, enabled=false test)` + acuse Discord `✅/⚠️` en listener.
  - *Derivado de:* `spec.md RF-01,RF-02,RF-09,RNF-01,RNF-02 + plan.md §1,§2`
  - *Verificación:* `./mvnw test -Dtest=RedisBufferAdapterTest,OciObjectStorageAdapterTest`
- [ ] **TASK-004-04**: Crear `interfaces` `PackageController (:run flush manual + :newsletter manual + GETs lista/detalle solo persistido)` + `Analyze/GenerateController` (cableado) + `EventsController SSE doble (asset.created inmediato persisted:false + package.completed con lo guardado)` + `GlobalExceptionHandler` + CORS/headers/413. Sin `429`.
  - *Derivado de:* `spec.md RF-03,RF-04,RF-05,RF-07,RF-08 + plan.md §1`
  - *Verificación:* `./mvnw test -Dtest=PackageControllerTest,EventsControllerTest,HealthTest`
- [ ] **TASK-004-05**: Verificar demo `#Listen → LLM por mensaje → fork SSE vivo + buffer → flush OCI batch → SSE/GETs` + newsletter manual.
  - *Derivado de:* `spec.md RF-06..RF-09 + plan.md §4`
  - *Verificación:* `./mvnw test` + `./mvnw spring-boot:run` (Redis Docker): por mensaje `asset.created + RPUSH`; al `900KB` → `201 + package.completed + ✅`; fallback/vacío → `LOG + ⚠️` sin SSE ni buffer; OCI-fail → `LOG + ⚠️` sin `package.completed`; `GET /packages` solo persistido; `POST /packages:newsletter` con ≥15 genera newsletter
