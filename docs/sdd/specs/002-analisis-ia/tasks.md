# Tasks 002: Análisis OpenAI vía Spring AI (Discord-only)

- [ ] **TASK-002-00**: Añadir dependencia `spring-ai-starter-model-openai` + BOM `spring-ai-bom` (verificar compatibilidad Boot `4.1.1`) + config `spring.ai.openai.api-key=${OPENAI_API_KEY}`, `model=${OPENAI_MODEL:gpt-4o-mini}` solo en `infrastructure/config`. Sin key → degradado `LLM_NOT_CONFIGURED`.
  - *Derivado de:* `spec.md RF-05,RNF-02 + plan.md §2`
  - *Verificación:* `./mvnw test` compila con Spring AI; arranque sin key no tumba `health`
- [ ] **TASK-002-01**: Crear `domain` `Sentiment, Language, ComentarioEnriquecido` + `RelevancePolicy` pura.
  - *Derivado de:* `spec.md RF-01,RF-03 + plan.md §1`
  - *Verificación:* `./mvnw test -Dtest=RelevancePolicyTest`
- [ ] **TASK-002-02**: Crear `application` `ports/in/AnalyzeUseCase, ports/out/AnalyzePort, services/AnalyzeService` tolerante a fallos.
  - *Derivado de:* `spec.md RF-04 + plan.md §1`
  - *Verificación:* `./mvnw test -Dtest=AnalyzeServiceTest`
- [ ] **TASK-002-03**: Crear `infrastructure` `SpringAiOpenAiAdapter` con Spring AI real (`ChatClient` + `BeanOutputConverter` schema + timeout 15s +1 reintento + fallback `LLM_FALLBACK`) + `promptVersion:v1`, temperatura baja. Solo `text+type` al LLM, nunca `author/ids`.
  - *Derivado de:* `spec.md RF-02,RF-05,RNF-01..RNF-03 + plan.md §1,§2`
  - *Verificación:* `./mvnw test -Dtest=SpringAiOpenAiAdapterTest`
- [ ] **TASK-002-04**: Exponer `interfaces` `AnalyzeController POST /api/v1/analyze` + docs Swagger.
  - *Derivado de:* `spec.md RF-01 + constitution P3`
  - *Verificación:* `./mvnw test -Dtest=AnalyzeControllerTest`
