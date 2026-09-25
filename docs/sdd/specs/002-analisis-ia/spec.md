# Especificación 002: Análisis con IA (sentimiento, temas, relevancia)

> Respeta: `constitution.md` v1.2-redis-buffer. Consume `Comment[]` de 001 por mensaje (LLM per-msg, sin cambios por buffer Redis). Solo QUÉ/POR QUÉ.

## 1. Problema y Objetivo

El lote normalizado aún es texto no estructurado. El objetivo 002 es enriquecer cada `Comment` con `sentimiento, temas[], relevancia (0-100)` usando LLMs vía Spring AI, para priorizar qué merece convertirse en activo de marketing.

## 2. Requerimientos Funcionales

- **RF-01 — Enriquecimiento por comentario:** para cada `Comment` válido, calcular `type: TESTIMONIO|LOGRO|DUDA|OTRO`, `sentiment: POSITIVO|NEUTRAL|NEGATIVO`, `topics: string[1..5]`, `relevance: 0..100`, `language: es|en|pt|other`. La entrada llega siempre `type=OTRO` (Discord JDA o Telegram long polling, 001); la IA la clasifica aquí. Si `truncated:true`, no se penaliza relevancia por el corte.
  - *Criterio:* **Dado** "Conseguí empleo como dev Java, gracias comunidad!" con `type=OTRO` **Cuando** se analiza, **Entonces** `type=LOGRO`, `sentiment=POSITIVO`, `relevance >= 70`, `topics` contiene `empleo` o `contratación`.
- **RF-02 — Salida estructurada obligatoria:** el LLM debe devolver JSON validable contra schema, nunca texto libre.
  - *Criterio:* **Dado** cualquier comentario válido, **Cuando** se analiza, **Entonces** la salida parsea sin `repair` manual en ≥99% de casos; si falla, se reintenta 1 vez y luego se marca `relevance=0, topics=[], sentiment=NEUTRAL` con `flag=LLM_FALLBACK`.
- **RF-03 — Reglas de relevancia (negocio, no prompt libre):** `LOGRO` y `TESTIMONIO` con sentimiento positivo pesan más que `DUDA`; texto <15 chars irrelevante.
  - *Criterio:* **Dado** un `LOGRO` positivo vs una `DUDA` neutra similar, **Cuando** se comparan, **Entonces** `relevance(LOGRO) > relevance(DUDA)`.
- **RF-04 — Lote con tolerancia a fallos:** si 1 comentario falla, el resto sigue; la respuesta incluye `analyzed, fallbackCount`.
  - *Criterio:* **Dado** 10 comentarios y caída del LLM en 1, **Cuando** se procesa el lote, **Entonces** responde con 9 enriquecidos + 1 en fallback, sin `500`.
- **RF-05 — Proveedor único OpenAI:** el análisis usa exclusivamente OpenAI vía Spring AI; el modelo se configura por env sin cambiar código de negocio.
  - *Criterio:* **Dado** `OPENAI_MODEL` válido y key válida, **Cuando** se analiza, **Entonces** usa ese modelo sin desplegar de nuevo.

## 3. RNF

- **RNF-01:** latencia LLM excluida del `p95 < 300ms` de ingesta; 002 documenta `p50/p95` observados y timeout `15s` por lote con reintento 1 vez.
- **RNF-02:** key solo por env (`OPENAI_API_KEY`), nunca en logs ni respuestas (R4/Q3).
- **RNF-03:** coste acotado MVP: max 500 comentarios/lote, prompt versionado, temperatura baja para clasificación.

## 4. Dominio y Glosario

- **EnrichedComment = Comment + {sentiment, topics[], relevance, language, flag?}**.
- **Relevance:** 0-100, prioriza qué se convierte en activo. Umbral sugerido publicación: `>=60` (ajustable en 003).
- **Structured Output:** JSON con schema fijo exigido al LLM.

## 5. Fuera de Alcance

- Redacción de copys (003), guardado OCI (004), fine-tuning, embeddings/vector DB, moderación automática/baneo, auth/usuarios.
