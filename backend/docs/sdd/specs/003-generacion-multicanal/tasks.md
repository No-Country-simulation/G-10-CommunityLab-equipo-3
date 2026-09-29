# Tasks 003: Generación multicanal con Spring AI (Discord-only)

> Convención: rutas de *Implementación* relativas a `src/main/java/com/nocountry/simulation/communitylab/`.


> Requiere `TASK-002-00` (Spring AI + BOM) hecho. No añade dependencia nueva. Telegram fuera.

- [x] **TASK-003-01**: Crear `domain` `Channel, Asset` + `HallucinationGuard` puro (lista negra empresa/salario/fecha/métrica) + validación longitudes por canal.
  - *Implementación:* `domain/enums/Channels.java`, `domain/policy/HallucinationGuard.java`, `domain/entity/ResponseModel.java` (campos post) + `domain/entity/EnrichedComment.java` (campos post + longitudes por canal migradas del Asset)
  - *Derivado de:* `spec.md RF-01,RF-02,RF-05 + plan.md §1`
  - *Verificación:* `./mvnw test -Dtest=HallucinationGuardTest,AssetTest`
  - *Cierre 2026-09-28 (alineación de nombres):* `Channel` → `Channels` (`domain/enums/Channels.java`); `Asset` sin clase dedicada → campos de post en `ResponseModel`/`EnrichedComment` (`channelPost/titlePost/copy/hashtags/cta`, ver spec §4); `HallucinationGuard` implementado con ese nombre + `HallucinationGuardTest`. Validación de longitudes por canal declarada en `SystemPrompt` (REGLAS DE REDACCIÓN POR CANAL) y post-hoc mínima en `EnrichmentListener` (copy/hashtags no vacíos); `AssetTest` no aplica (no hay clase).
  - *Cierre 2026-09-29 (renombre + ResponseClient):* `copy` → `outputContentProcessed`, `contentProcessed/messageProcess` → `messageAuthor`, `batchidLote` → `messageBatchId`, canal único `channelPost`; SSE expone `ResponseClient(messageId/messageBatchId/channelPost/...)`, Redis guarda `EnrichedComment`.
- [x] **TASK-003-02**: Crear `application` `ChannelPolicy` puro (LinkedIn `80..600+2..5 tags+cta`, X `<=280+1..2 tags`, Newsletter `100..400 palabras+3..5 destacados`, FAQ `outputContentProcessed 20..500 chars + titlePost obligatorio + cta null`, preguntas y quejas nunca X/LinkedIn, 1 duda o queja = 1 post sin consolidado).
  - *Implementación:* `infrastructure/dto/ai/SystemPrompt.java` (reglas de canal) + `application/services/ai/EnrichmentListener.java` (validación post-hoc)
  - *Derivado de:* `spec.md RF-01..RF-04 + plan.md §1 + enmienda FAQ-pregunta 2026-09-28`
  - *Verificación:* `./mvnw test -Dtest=ChannelPolicyTest`
  - *Cierre 2026-09-28 (alineación de nombres):* no existe clase `ChannelPolicy`; la política de canal vive como reglas del prompt único `SystemPrompt.systemPromptRedactPost()` (elección LINKEDIN/X/FAQ + longitudes + prohibición preguntas/quejas en X/LinkedIn) y verificación post-hoc en `EnrichmentListener` (descarte de post inválido). Sin clase → sin `ChannelPolicyTest`; cobertura vía `EnrichmentListenerTest` + observación con modelo real (plan §3).
- [x] **TASK-003-03**: Crear `application` `ports/in/GenerateUseCase, ports/out/GeneratePort, services/GenerateService` con filtro `relevance<60` (sin LinkedIn/X, `DUDA`/`QUEJA` o pregunta solo FAQ, nunca X/LinkedIn) + tolerancia a fallos (`DRAFT_EMPTY` sin `500`).
  - *Implementación:* `application/port/out/RequestToLLMProcess.java`, `application/services/ai/EnrichmentListener.java`, `application/services/comment/ConvertEnrichedCommentService.java`
  - *Derivado de:* `spec.md RF-06,RNF-01 + plan.md §1`
  - *Verificación:* `./mvnw test -Dtest=GenerateServiceTest`
  - *Cierre 2026-09-28 (alineación de nombres):* generación fusionada con el pipeline de análisis (single-LLM-call): `RequestToLLMProcess` (port) + `EnrichmentListener` (orquestación con tolerancia a fallos: fallo LLM → `LLM_FALLBACK` bufferizado, nunca `500`; post inválido → descarte con LOG) + `ConvertEnrichedCommentService`. Fallback `DRAFT_EMPTY` → `LLM_FALLBACK`. El filtro `relevance<60` queda **eliminado por enmienda 2026-09-28** (spec RF-06: umbral redundante, preguntas reales puntúan ≈95; protección de canal vía reglas del prompt). No hay `GenerateServiceTest`; cobertura vía `EnrichmentListenerTest` + `AnalyzeMessageLlmAdapterTest`.
- [x] **TASK-003-04**: Crear `infrastructure` `SpringAiGenerateAdapter` (4 prompts `v1` por canal + `ChatClient` + schema por canal + timeout 15s +1 reintento + fallback `DRAFT_EMPTY`). Solo `text+topics+type` al LLM.
  - *Implementación:* `infrastructure/adapters/out/ai/AnalyzeMessageLlmAdapter.java`, `infrastructure/dto/ai/SystemPrompt.java`, `infrastructure/adapters/out/ai/LlmOutputSanitizer.java`
  - *Derivado de:* `spec.md RF-01..RF-05,RNF-01..RNF-03 + plan.md §1,§2,§4`
  - *Verificación:* `./mvnw test -Dtest=SpringAiGenerateAdapterTest`
  - *Cierre 2026-09-28 (alineación de nombres):* no existe adapter de generación separado; la redacción por canal vive en el **único prompt** `SystemPrompt.systemPromptRedactPost()` (`v1`, elección de canal + reglas por canal, temp baja) ejecutado por `AnalyzeMessageLlmAdapter` (`ChatClient` + converter + `timeout 15s` + 1 reintento) con higiene de salida en `LlmOutputSanitizer`. Fallback `LLM_FALLBACK`. Solo `text+type` al LLM. No hay `SpringAiGenerateAdapterTest`; cobertura vía `AnalyzeMessageLlmAdapterTest` + `LlmOutputSanitizerTest`.
- [x] **TASK-003-05 (eliminada 2026-09-28, decisión single-GET):** sin `GenerateController` ni `POST /api/v1/generate`. Generación interna verificada por `GenerateServiceTest`; exposición solo vía `GET /api/v1/discord/messages` (004).
  - *Derivado de:* decisión single-GET + `constitution v1.6`
