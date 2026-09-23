# Especificación 004: Paquete OCI + transversal (health, docs, seguridad básica)

> Respeta: `constitution.md` v1.0-final. Cierra el pipeline Fase 1. Solo QUÉ/POR QUÉ.
> Alcance semanal: solo `DISCORD #Listen` por ID. `TELEGRAM` deferrado. Secuencia impuesta: `Bot #Listen → LLM (002) → etiquetas canal (003) → OCI + frontend`. `1 mensaje = 1 paquete` (`batchId=uuid`). Anónimo total (sin `author/ids` en LLM/OCI/logs).

## 1. Problema y Objetivo

Sin persistencia del resultado ni superficie operable, la demo del hackathon no es verificable. Objetivo 004: guardar el `PaqueteDeActivos` en OCI Object Storage (único storage Fase 1) y exponer transversalmente `health`, Swagger y seguridad básica.

## 2. Requerimientos Funcionales

- **RF-01 — Guardado OCI:** al completar `001→002→003` por mensaje `#Listen`, persistir `paquetes/paquete-{batchId}.json` en bucket configurado y devolver `{packageUrl, etag, sizeBytes}`. Solo se guarda si `assets[]` no vacío; vacío/`DRAFT_EMPTY` aborta sin OCI.
  - *Criterio:* **Dado** un paquete válido <1MB con activos, **Cuando** se guarda, **Entonces** responde `201` con URL `oci://{bucket}/paquetes/paquete-{batchId}.json`. **Dado** fallo OCI, **Cuando** ocurre, **Entonces** solo `LOG` error sin PII, sin evento al frontend.
- **RF-02 — Idempotencia por batch:** reenviar el mismo `batchId` no duplica objetos, sobrescribe o devuelve el existente con `deduped:true`.
  - *Criterio:* **Dado** `batchId` ya guardado, **Cuando** se re-guarda, **Entonces** 1 solo objeto en bucket y `deduped:true`.
- **RF-03 — Health público:** `GET /actuator/health` responde `{"status":"UP"}` sin auth, `p95 < 100ms`.
  - *Criterio:* **Dado** app arrancada, **Cuando** se llama `/actuator/health`, **Entonces** `200 + UP`.
- **RF-04 — Docs API:** Swagger UI en `/swagger-ui.html` documenta `POST /api/v1/analyze`, `POST /api/v1/generate`, `POST /api/v1/packages:run`, `POST /api/v1/packages:newsletter`, `GET /api/v1/packages`, `GET /api/v1/packages/{batchId}` y `GET /api/v1/events` (SSE). La ingesta Discord es vía bot JDA `#Listen` y no aparece en Swagger (P3 no aplica a Gateway).
  - *Criterio:* **Dado** app arrancada, **Cuando** se abre Swagger, **Entonces** los endpoints aparecen con ejemplos.
- **RF-05 — Seguridad básica:** CORS allowlist por env, headers seguros, 413 en >10MB resto API, 400 con formato problema, sin stacktraces. Sin rate-limit ni `429` en Fase 1 (decisión 001: alcance MVP + riesgo de pérdida).
  - *Criterio:* **Dado** `Origin` no permitido, **Cuando** se llama a la API, **Entonces** bloqueado por CORS; **Dado** `Origin` permitido, pasa.
- **RF-06 — Pipeline orquestado MVP:** el bot Discord `#Listen` dispara `ingesta→análisis→generación→OCI→SSE` mensaje por mensaje (para la demo). `POST /api/v1/packages:run` queda como disparo manual con el mismo resultado. Si `002` da `LLM_FALLBACK` o `003` da vacío/`DRAFT_EMPTY` → aborta sin OCI/SSE, solo `LOG + ⚠️`. Si OCI falla → solo `LOG`, el frontend no se entera.
  - *Criterio:* **Dado** un mensaje válido en `#Listen`, **Cuando** se completa el pipeline, **Entonces** hay `201` interno con `{batchId, assetsCount, packageUrl}` + evento SSE + acuse `✅`; si abort/fallo → sin paquete, sin SSE, con `⚠️` solo en Discord.
- **RF-07 — Suscripción SSE del dashboard (requerida):** `GET /api/v1/events` (`text/event-stream`) emite `asset.created` y `package.completed` con **lo guardado en OCI** `{type, batchId, payload:{assets,packageUrl}}` al frontend suscrito, con reconexión por `Last-Event-ID`. Solo éxitos emiten; fallos solo `LOG`.
  - *Criterio:* **Dado** un dashboard suscrito, **Cuando** se guarda un paquete, **Entonces** recibe el evento `{type, batchId, payload}` en < 5 s; **Dado** reconexión con `Last-Event-ID`, **Entonces** no pierde eventos ya emitidos.
- **RF-08 — Recuperación desde backend (requerida):** solo el backend habla con OCI; el frontend recupera todo vía `GET /api/v1/packages` (lista paginada desde `listObjects`) y `GET /api/v1/packages/{batchId}` (detalle vía `getObject`). Cada registro nuevo se envía por SSE, pero si el frontend cae recupera por estos GETs sin pérdida.
  - *Criterio:* **Dado** frontend reiniciado, **Cuando** llama `GET /api/v1/packages`, **Entonces** recibe lo persistido en OCI.
- **RF-09 — Newsletter batch cada 5 días (requerida):** `@Scheduler` prod (`cron 00:00 UTC */5`, `NEWSLETTER_BATCH_SIZE=15`) lee `listObjects`, sorteo aleatorio de 15 paquetes unitarios (aborta si `<15`), pasa anonimizados al LLM, genera `Activo{NEWSLETTER}` y guarda `paquetes/newsletter-{yyyy-MM-dd}.json` + SSE igual que unitarios. `POST /api/v1/packages:newsletter` dispara lo mismo manual para demo. Fallos solo `LOG`.
  - *Criterio:* **Dado** ≥15 paquetes, **Cuando** corre el job, **Entonces** guarda 1 newsletter con `≥3 temas`; si `<15` o fallback → abort sin guardar.

## 3. RNF

- **RNF-01:** objeto OCI `<1MB`, `Content-Type: application/json`, cifrado del lado OCI por defecto Always Free.
- **RNF-02:** credenciales OCI solo por env/instancia (`OCI_BUCKET`, `OCI_REGION`, `OCI_*_KEY`), nunca en repo.
- **RNF-03:** resto de actuadores (`env,beans,mappings`) apagados en Fase 1.
- **RNF-04:** cada mensaje `#Listen` se orquesta individualmente (`1:1`); el orquestador no retiene lotes grandes. Excepción: job newsletter batch (RF-09) con 15 aleatorios.

## 4. Dominio y Glosario

- **PaqueteDeActivos:** `{batchId=uuid (1:1 vivo) o newsletter-{fecha} (batch), generatedAt, source: DISCORD, assets: Activo[] (vivo: LINKEDIN/X/FAQ simple), stats:{received, analyzed, fallbackCount, assetsCount}, promptVersion, packageUrl?}`. Anónimo total, sin `author/ids`.
- **Bucket:** contenedor OCI. Propuesta TBD por dev cuenta: `communitylab-assets`, región `sa-saopaulo-1`, prefijo `paquetes/`, keys `paquete-{batchId}.json` y `newsletter-{fecha}.json`.
- **Mensaje individual:** cada `Comentario` de `#Listen` pasa por 001→002→003; si hay activos se almacena `1:1`, si fallback/vacío/OCI-fail no hay paquete ni SSE (solo `LOG + ⚠️`).
- **Acuse Discord:** `✅` si `201 + SSE`, `⚠️` si abort/fallo. Sin contenido ni PII.

## 5. Fuera de Alcance

- DB relacional, auth/usuarios/roles, publicación automática a LinkedIn/X, CDN público, versionado histórico de paquetes, multi-bucket/multi-tenant, métricas Prometheus/Grafana (Fase >1).
