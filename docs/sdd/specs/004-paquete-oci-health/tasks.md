# Tasks 004: Paquete OCI + SSE + GETs + newsletter batch (`#Listen` only)

> Telegram fuera. Secuencia: `Bot #Listen → LLM → etiquetas → OCI → SSE`. `LLM_FALLBACK`/vacío/OCI-fail → abort/`LOG` sin SSE. Anónimo total.

- [ ] **TASK-004-00**: Añadir dependencias `oci-java-sdk-objectstorage` + `actuator + springdoc-openapi-starter-webmvc-ui`, cerrar resto actuadores (`health,info` solo). Verificar compatibilidad Boot `4.1.1`.
  - *Derivado de:* `constitution P3,Q3 + plan.md §2`
  - *Verificación:* `./mvnw test` compila; `GET /actuator/health → {"status":"UP"}`
- [ ] **TASK-004-01**: Crear `domain` `PaqueteDeActivos{batchId=uuid 1:1 / newsletter-fecha, source:DISCORD, anon}` + `PackageStats` + validación `<1MB application/json`. Dueño único `Paquete` (`Activo` vive en 003).
  - *Derivado de:* `spec.md RF-01,RNF-01 + plan.md §1`
  - *Verificación:* `./mvnw test -Dtest=PaqueteDeActivosTest`
- [ ] **TASK-004-02**: Crear `application` `ports/in/PackageRunUseCase + GenerateNewsletterUseCase, ports/out/ArtifactStorePort(put/get/list), services/PackageRunService + GenerateNewsletterService` (vivo abort sin guardar si fallback/vacío; OCI-fail solo `LOG`; newsletter sorteo 15 aleatorios, abort si `<15`).
  - *Derivado de:* `spec.md RF-02,RF-06,RF-09 + plan.md §1`
  - *Verificación:* `./mvnw test -Dtest=PackageRunServiceTest,GenerateNewsletterServiceTest`
- [ ] **TASK-004-03**: Crear `infrastructure` `OciObjectStorageAdapter` SDK (`put/get/list`, keys `paquetes/paquete-*.json` + `paquetes/newsletter-*.json`) + `OciConfig` env `OCI_BUCKET,OCI_REGION (+NAMESPACE/AUTH)` + degradado `OCI_NOT_CONFIGURED` + `NewsletterScheduler (@Scheduler cron 5d prod, enabled=false test)` + acuse Discord `✅/⚠️` en listener.
  - *Derivado de:* `spec.md RF-01,RF-02,RF-09,RNF-01,RNF-02 + plan.md §1,§2`
  - *Verificación:* `./mvnw test -Dtest=OciObjectStorageAdapterTest`
- [ ] **TASK-004-04**: Crear `interfaces` `PackageController (:run + :newsletter manual + GETs lista/detalle)` + `Analyze/GenerateController` (cableado) + `EventsController SSE (package.completed con lo guardado)` + `GlobalExceptionHandler` + CORS/headers/413. Sin `429`.
  - *Derivado de:* `spec.md RF-03,RF-04,RF-05,RF-07,RF-08 + plan.md §1`
  - *Verificación:* `./mvnw test -Dtest=PackageControllerTest,EventsControllerTest,HealthTest`
- [ ] **TASK-004-05**: Verificar demo `#Listen → LLM → etiquetas → OCI → SSE/GETs` + newsletter manual.
  - *Derivado de:* `spec.md RF-06..RF-09 + plan.md §4`
  - *Verificación:* `./mvnw test` + `./mvnw spring-boot:run`: `#Listen` → `201 + ✅ + SSE`; fallback/vacío/OCI-fail → `LOG + ⚠️` sin SSE; `GET /packages` recupera; `POST /packages:newsletter` con ≥15 genera newsletter
