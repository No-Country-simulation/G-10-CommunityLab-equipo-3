# Tasks 003: Generación multicanal con Spring AI (Discord-only)

> Requiere `TASK-002-00` (Spring AI + BOM) hecho. No añade dependencia nueva. Telegram fuera.

- [ ] **TASK-003-01**: Crear `domain` `Channel, Activo` + `HallucinationGuard` puro (lista negra empresa/salario/fecha/métrica) + validación longitudes por canal.
  - *Derivado de:* `spec.md RF-01,RF-02,RF-05 + plan.md §1`
  - *Verificación:* `./mvnw test -Dtest=HallucinationGuardTest,ActivoTest`
- [ ] **TASK-003-02**: Crear `application` `ChannelPolicy` puro (LinkedIn `80..600+2..5 tags+cta`, X `<=280+1..2 tags`, Newsletter `100..400 palabras+3..5 destacados`, FAQ `50..250 palabras`) + `FaqGrouping` (mismo `topics` → 1 tip con N ids).
  - *Derivado de:* `spec.md RF-01..RF-04 + plan.md §1`
  - *Verificación:* `./mvnw test -Dtest=ChannelPolicyTest,FaqGroupingTest`
- [ ] **TASK-003-03**: Crear `application` `ports/in/GenerateUseCase, ports/out/GeneratePort, services/GenerateService` con filtro `relevance<60` (sin LinkedIn/X, `DUDA` solo FAQ) + tolerancia a fallos (`DRAFT_EMPTY` sin `500`).
  - *Derivado de:* `spec.md RF-06,RNF-01 + plan.md §1`
  - *Verificación:* `./mvnw test -Dtest=GenerateServiceTest`
- [ ] **TASK-003-04**: Crear `infrastructure` `SpringAiGenerateAdapter` (4 prompts `v1` por canal + `ChatClient` + schema por canal + timeout 15s +1 reintento + fallback `DRAFT_EMPTY`). Solo `text+topics+type` al LLM.
  - *Derivado de:* `spec.md RF-01..RF-05,RNF-01..RNF-03 + plan.md §1,§2,§4`
  - *Verificación:* `./mvnw test -Dtest=SpringAiGenerateAdapterTest`
- [ ] **TASK-003-05**: Exponer `interfaces` `GenerateController POST /api/v1/generate → 200 {assets,promptVersion}/207` + Swagger + ejemplo LinkedIn/X sin empresa inventada.
  - *Derivado de:* `spec.md RF-01,RF-02 + constitution P3 + plan.md §1`
  - *Verificación:* `./mvnw test -Dtest=GenerateControllerTest`
