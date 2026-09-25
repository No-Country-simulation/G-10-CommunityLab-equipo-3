# Especificación 003: Generación multicanal (LinkedIn, X, Newsletter, FAQ)

> Respeta: `constitution.md` v1.2-redis-buffer. Consume `EnrichedComment[]` de 002 por mensaje (LLM per-msg; tras 003 hay fork `SSE inmediato + RPUSH Redis`, ver 004). Solo QUÉ/POR QUÉ.

## 1. Problema y Objetivo

El análisis sin redacción no sirve a Community Management. Objetivo 003: convertir comentarios con `relevance >= 60` en borradores listos para publicar, adaptados al tono de cada canal, más tips FAQ a partir de `DUDA`s.

## 2. Requerimientos Funcionales

- **RF-01 — Copy LinkedIn:** para cada `LOGRO|TESTIMONIO` con `relevance>=60`, generar `{copy (80..600 chars), hashtags[2..5], cta}` en tono inspirador/profesional español.
  - *Criterio:* **Dado** un logro "primer empleo Java", **Cuando** se genera, **Entonces** el copy incluye el logro, menciona comunidad, 2-5 hashtags (`#EmpleoTech` etc.) y CTA, sin inventar empresa/salario no presentes.
- **RF-02 — Post X/Twitter:** variante `<=280 chars` + 1-2 hashtags, tono directo.
  - *Criterio:* **Dado** el mismo logro, **Cuando** se genera X, **Entonces** `length<=280`.
- **RF-03 — Resumen Newsletter semanal:** a partir de N comentarios, generar `{titulo, resumen (100..400 palabras), destacados[3..5]}`.
  - *Criterio:* **Dado** 10 enriquecidos, **Cuando** se genera newsletter, **Entonces** cubre ≥3 temas distintos sin duplicar copys de LinkedIn.
- **RF-04 — Tips FAQ:** por cada `DUDA` recurrente (mismo `topics`), generar `{pregunta, respuesta Didáctica (50..250 palabras), fuentes: ids[]}`.
  - *Criterio:* **Dado** 3 dudas de "NullPointer", **Cuando** se genera FAQ, **Entonces** 1 tip consolidado cita los 3 ids.
- **RF-05 — Prohibido alucinar:** nunca inventar nombres de empresas, salarios, fechas o métricas no presentes en el texto fuente.
  - *Criterio:* **Dado** texto sin empresa, **Cuando** se genera copy, **Entonces** no contiene empresa; test de alucinación con lista negra pasa.
- **RF-06 — Filtrado por umbral:** `relevance<60` no genera LinkedIn/X, solo puede ir a FAQ si es `DUDA`.
  - *Criterio:* **Dado** `relevance=20`, **Cuando** se genera, **Entonces** sin salida LinkedIn/X.

## 3. RNF

- **RNF-01:** salidas siempre JSON validable; reintento 1 vez + fallback `DRAFT_EMPTY` sin `500`.
- **RNF-02:** prompts versionados (`promptVersion: v1`) trazables en la respuesta para auditoría jurado.
- **RNF-03:** tono por canal documentado en spec (LinkedIn inspirador, X conciso, Newsletter resumen, FAQ didáctico), no libre.

## 4. Dominio y Glosario

- **Asset:** `{id, sourceCommentIds[], channel: LINKEDIN|X|NEWSLETTER|FAQ, title?, copy, hashtags?, cta?, promptVersion}`.
- **AssetPackage (anticipa 004):** `{batchId, generatedAt, assets: Asset[], stats}`.
- Ejemplo LinkedIn: `{copy:"De la comunidad al primer empleo... 🚀", hashtags:["#ONE","#EmpleoTech"], cta:"Comparte tu historia en #logros"}`.

## 5. Fuera de Alcance

- Publicación automática en LinkedIn/X (solo borradores), imágenes/canva, calendario editorial, A/B testing, traducción multi-idioma completa (solo es/en base), auth/usuarios.
