# Especificación 003: Generación multicanal (LinkedIn, X, Newsletter, FAQ)

> Respeta: `constitution.md` v1.2-redis-buffer. Consume `EnrichedComment[]` de 002 por mensaje (LLM per-msg; tras 003 hay fork `SSE inmediato + RPUSH Redis`, ver 004). Solo QUÉ/POR QUÉ.

## 1. Problema y Objetivo

El análisis sin redacción no sirve a Community Management. Objetivo 003: convertir comentarios publicables en borradores listos para publicar, adaptados al tono de cada canal, más preguntas FAQ curadas a partir de `DUDA`s y `QUEJA`s. Alcance estricto: recolectar → procesar → redactar; el modelo NUNCA responde preguntas ni soluciona dudas o quejas, solo las cura y redacta.

## 2. Requerimientos Funcionales

- **RF-01 — outputContentProcessed LinkedIn:** para cada `LOGRO|TESTIMONIO` publicable, generar `{outputContentProcessed (80..600 chars), hashtags[2..5], cta}` en tono inspirador/profesional español.
  - *Criterio:* **Dado** un logro "primer empleo Java", **Cuando** se genera, **Entonces** el outputContentProcessed incluye el logro, menciona comunidad, 2-5 hashtags (`#EmpleoTech` etc.) y CTA, sin inventar empresa/salario no presentes.
- **RF-02 — Post X/Twitter:** variante `<=280 chars` + 1-2 hashtags, tono directo.
  - *Criterio:* **Dado** el mismo logro, **Cuando** se genera X, **Entonces** `length<=280`.
- **RF-03 — Resumen Newsletter semanal:** a partir de N comentarios, generar `{titulo, resumen (100..400 palabras), destacados[3..5]}`.
  - *Criterio:* **Dado** 10 enriquecidos, **Cuando** se genera newsletter, **Entonces** cubre ≥3 temas distintos sin duplicar outputContentProcessed de LinkedIn.
- **RF-04 — Preguntas FAQ:** por cada `DUDA` o `QUEJA`, generar `{titlePost (título de la duda o queja), outputContentProcessed (reformulada + contexto solo-del-mensaje, 20..500 chars), hashtags[0..5], cta: null}`. PROHIBIDO responder, solucionar, aconsejar o diagnosticar; cada duda o queja va a su propio post (sin consolidado batch). Las preguntas y quejas nunca van a X ni LinkedIn.
  - *Criterio:* **Dado** una duda "error 401 en OCI", **Cuando** se genera FAQ, **Entonces** 1 post con `titlePost` temático y `outputContentProcessed` pregunta reformulada sin solución, `cta: null`.
  - *Criterio:* **Dado** una duda "error 401 en OCI", **Cuando** se genera FAQ, **Entonces** 1 post con `titlePost` temático y `outputContentProcessed` pregunta reformulada sin solución, `cta: null`.
- **RF-05 — Prohibido alucinar:** nunca inventar nombres de empresas, salarios, fechas o métricas no presentes en el texto fuente.
  - *Criterio:* **Dado** texto sin empresa, **Cuando** se genera outputContentProcessed, **Entonces** no contiene empresa; test de alucinación con lista negra pasa.
- **RF-06 — (eliminada 2026-09-28) Filtrado por umbral:** `relevance<60` no genera LinkedIn/X, solo puede ir a FAQ si es `DUDA`.
  - *Criterio:* **Dado** `relevance=20`, **Cuando** se genera, **Entonces** sin salida LinkedIn/X.
  - *Causa de eliminación:* en la práctica las preguntas reales puntúan alto (≈95) y superarían el corte, dejando la protección de canal en manos de un umbral redundante. La prohibición de preguntas/quejas en X/LinkedIn ya está garantizada por RF-04 (reglas de canal del prompt `SystemPrompt`: DUDA/QUEJA/pregunta → FAQ siempre). El `relevance` queda como estimación informativa sin uso de filtrado (ver spec 002 §4); su rúbrica no se define y su deriva carece de efecto.

## 3. RNF

- **RNF-01:** salidas siempre JSON validable; reintento 1 vez + fallback `LLM_FALLBACK` sin `500`.
- **RNF-02:** prompts versionados (`promptVersion: v1`) trazables en la respuesta para auditoría jurado.
- **RNF-03:** tono por canal documentado en spec (LinkedIn inspirador, X conciso, Newsletter resumen, FAQ pregunta curada — prohibido responder), no libre.

## 4. Dominio y Glosario

- **Asset:** `{id, sourceCommentIds[], channel: LINKEDIN|X|NEWSLETTER|FAQ, title?, outputContentProcessed, hashtags?, cta?, promptVersion}`.
  - *Alineación 2026-09-28 (implementación):* no existe clase dedicada; el Asset se materializa como los campos de post embebidos en `ResponseModel` y `EnrichedComment` (`channelPost/titlePost/outputContentProcessed/hashtags/cta`), con `promptVersion` global (`EnrichedComment.PROMPT_VERSION`), sin `id` ni `sourceCommentIds[]` por asset (trazabilidad por `messageId` del comentario).
  - *Renombre 2026-09-29:* `copy` → `outputContentProcessed` (redacción nueva lista para publicar); `contentProcessed` / `messageProcess` → `messageAuthor` (texto del autor con curaduría mínima, conserva sus palabras, no es publicable); `batchidLote` → `messageBatchId`; canal único `channelPost` en wire LLM, dominio y cliente SSE.
- **Split de contratos (2026-09-29):** wire LLM `ResponseModel(messageAuthor/outputContentProcessed/channelPost)` → interno+Redis `EnrichedComment(messageBatchId/messageId/channelPost/...)` → SSE público `ResponseClient(messageId/messageBatchId/channelPost/...)`. El fork tras 003 es `A) SSE inmediato ResponseClient + B) RPUSH Redis EnrichedComment`.
- **messageAuthor:** texto del autor tras curaduría mínima (corrige solo typos evidentes, conserva palabras y estilo; si es pregunta, sigue siendo su pregunta). No es redacción nueva.
- **outputContentProcessed:** redacción nueva del LLM lista para publicar, adaptada al canal (`80..600` LinkedIn, `<=280` X, `20..500` FAQ), con hook propio y prohibido reutilizar `>5` palabras literales del original.
- **AssetPackage (anticipa 004):** `{batchId, generatedAt, assets: Asset[], stats}`.
- Ejemplo LinkedIn: `{outputContentProcessed:"De la comunidad al primer empleo... 🚀", hashtags:["#ONE","#EmpleoTech"], cta:"Comparte tu historia en #logros"}`.
- Ejemplo SSE `asset.created` (`ResponseClient`, réplica de `GetMessagesProcessedDiscord`):
```json
{
  "authorName": "author-name",
  "messageAuthor": "Ayer conseguí mi primer empleo como dev Java, gracias por todo el apoyo!",
  "messageId": "msg-1",
  "messageBatchId": "b3e1a2c4-0000-4000-8000-000000000001",
  "sentiment": "POSITIVO",
  "language": "ES",
  "messageType": "LOGRO",
  "topics": ["empleo", "java", "logro"],
  "relevance": 85,
  "flag": null,
  "sentTime": "2026-09-28T10:00:00Z",
  "source": "DISCORD",
  "channelPost": "LINKEDIN",
  "titlePost": null,
  "outputContentProcessed": "De la comunidad al primer empleo como dev Java. Gracias por el apoyo en el camino!",
  "hashtags": ["#ONE", "#EmpleoTech", "#Java"],
  "cta": "Comparte tu historia en #logros"
}
```
`id:` del evento SSE = `messageId` (reconexión por `Last-Event-ID`).

## 5. Fuera de Alcance

- Publicación automática en LinkedIn/X (solo borradores), imágenes/canva, calendario editorial, A/B testing, traducción multi-idioma completa (solo es/en base), auth/usuarios.
- Responder preguntas o solucionar dudas de la comunidad (el modelo nunca responde; solo cura y redacta posts).
