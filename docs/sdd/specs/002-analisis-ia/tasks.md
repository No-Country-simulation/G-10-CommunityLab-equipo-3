# Tasks 002: Análisis bajo contrato OpenAI vía Spring AI (Discord-only)

> Convención: rutas de *Implementación* relativas a `src/main/java/com/nocountry/simulation/communitylab/`.


- [x] **TASK-002-00**: Añadir dependencia `spring-ai-starter-model-openai` + BOM `spring-ai-bom` (verificar compatibilidad Boot `4.1.1`) + config `spring.ai.openai.api-key=${API_KEY_LLM_MISTRAL_DEV}`, `model=${MODEL_MISTRAL}`, `base-url=${BASE_URL_MODEL_AI}` (requerido) solo en `infrastructure/config`. Sin key o sin base-url → degradado `LLM_NOT_CONFIGURED`.
  - *Implementación:* `pom.xml` (spring-ai-bom + spring-ai-starter-model-openai), `src/main/resources/application.yaml` (spring.ai.openai), `infrastructure/config/ai/FailEnvModelConfiguration.java`
  - *Derivado de:* `spec.md RF-05,RNF-02 + plan.md §2`
  - *Verificación:* `./mvnw test` compila con Spring AI; arranque sin key no tumba `health`
  - *Cierre 2026-09-27:* operativo en vivo con Mistral (contrato OpenAI + envs). Degradado sin key pendiente de test dedicado.
  - *Derivado de:* `spec.md RF-05,RNF-02 + plan.md §2`
  - *Verificación:* `./mvnw test` compila con Spring AI; arranque sin key no tumba `health`
- [x] **TASK-002-01**: Crear `domain` `Sentiment, Language, EnrichedComment` + `RelevancePolicy` pura.
  - *Implementación:* `domain/enums/ai/Sentiment.java`, `domain/enums/ai/Language.java`, `domain/entity/EnrichedComment.java`, `domain/policy/RelevancePolicy.java`
  - *Derivado de:* `spec.md RF-01,RF-03 + plan.md §1`
  - *Verificación:* `./mvnw test -Dtest=RelevancePolicyTest`
  - *Cierre 2026-09-27:* `RelevancePolicyTest` 7/7 + `Language{es,en,pt,other}` literal spec + `EnrichedComment` record con `batchidLote, messageType, topics, relevance, flag, promptVersion`.
- [x] **TASK-002-02**: Crear `application` `ports/in/AnalyzeUseCase, ports/out/AnalyzePort, services/AnalyzeService` tolerante a fallos.
  - *Implementación:* `application/port/out/RequestToLLMProcess.java`, `application/services/ai/EnrichmentListener.java`, `application/services/comment/ConvertEnrichedCommentService.java`
  - *Derivado de:* `spec.md RF-04 + plan.md §1`
  - *Verificación:* `./mvnw test -Dtest=AnalyzeMessageLlmAdapterTest` (nota: el test mencionado aquí siempre fue del adapter de 002-03; el caso de uso se verifica vía `EnrichmentListenerTest` + suite).
  - *Cierre 2026-09-27:* equivalencia funcional con otros nombres (`EnrichmentListener` + `RequestToLLMProcess` + `ConvertEnrichedCommentService`); fallo LLM → `flag=LLM_FALLBACK` bufferizado, nunca `500`.
- [x] **TASK-002-03**: Crear `infrastructure` `SpringAiOpenAiAdapter` con Spring AI real (`ChatClient` + `BeanOutputConverter` schema + timeout 15s +1 reintento + fallback `LLM_FALLBACK`) + `promptVersion:v1`, temperatura baja. Solo `text+type` al LLM, nunca `author/ids`.
  - *Implementación:* `infrastructure/adapters/out/ai/AnalyzeMessageLlmAdapter.java`, `infrastructure/adapters/out/ai/LlmOutputSanitizer.java`, `infrastructure/dto/ai/SystemPrompt.java`
  - *Derivado de:* `spec.md RF-02,RF-05,RNF-01..RNF-03 + plan.md §1,§2`
  - *Verificación:* `./mvnw test -Dtest=SpringAiOpenAiAdapterTest`
  - *Cierre 2026-09-27:* implementado como `AnalyzeMessageLlmAdapter` (`ChatClient` + converter + `timeout 15s` + 1 reintento + mapper permisivo, temp `0.1`); test con `ChatClient` mockeado (válido/fences/retry/throw).
  - *Sanitizer 2026-09-28:* higiene de wire-format extraída a `LlmOutputSanitizer` (`@Component` inyectado en el adapter): `sanitize` (strip de fences, recorte balanceado de llaves, ignorando llaves dentro de strings), `convert` (bucle de candidatos + `BeanOutputConverter` con feedback al reintento) y fingerprint para logs sin PII. Cubierto por `LlmOutputSanitizerTest` (7 casos: cercas, preámbulo/epílogo con y sin llaves, llaves dentro de strings, recorte desbalanceado). El adapter queda solo con prompt + options + llamada + reintento.
  - *Renombrado 2026-09-28:* la clase de infraestructura antes llamada `AnalyzeService` pasa a `AnalyzeMessageLlmAdapter` (nomenclatura de adapter hexagonal, alineado con el nombre original de esta task); test `AnalyzeServiceTest` → `AnalyzeMessageLlmAdapterTest`. Razón: claridad de rol (adapter out), sin cambio de comportamiento.
- [x] **TASK-002-04 (eliminada 2026-09-28, decisión single-GET):** sin `AnalyzeController` ni `POST /api/v1/analyze`. El análisis es interno vía evento (`IngestAcceptedEvent` → `EnrichmentListener`); la API solo expone `GET /api/v1/discord/messages`.
  - *Derivado de:* decisión single-GETendpoint + `constitution v1.6`
