# Especificación 004: Paquete OCI + transversal (health, docs, seguridad básica)

> Respeta: `constitution.md` v1.2-redis-buffer. Cierra el pipeline Fase 1. Solo QUÉ/POR QUÉ.
> Alcance semanal: solo `DISCORD #Listen` por ID. `TELEGRAM` deferrado. Secuencia impuesta: `Bot #Listen → LLM por mensaje (002) → etiquetas canal (003) → fork: SSE inmediato al frontend + buffer Redis por tamaño → OCI batch + frontend`. `N mensajes = 1 paquete` (`batchId=uuid` lote Redis). Anónimo total (sin `author/ids` en LLM/OCI/logs, solo `messageId/batchId/source`).

## 1. Problema y Objetivo

Sin persistencia del resultado ni superficie operable, la demo del hackathon no es verificable. Objetivo 004: tras el fork post-LLM, enviar vivo al frontend por SSE y a la vez bufferizar en Redis por tamaño en bytes para guardar el `AssetPackage` batch en OCI Object Storage (único storage persistente Fase 1, Redis es buffer volátil) y exponer transversalmente `health`, Swagger y seguridad básica.

## 2. Requerimientos Funcionales

- **RF-01 — Guardado OCI batch:** al cerrar el lote Redis por tamaño (`buffer:current:bytes >= REDIS_BUFFER_MAX_BYTES`), persistir `paquetes/paquete-{batchId lote}.json` con `assets[N mensajes]` y devolver `{packageUrl, etag, sizeBytes}`. Solo se guarda si `assets[]` no vacío; vacío/`DRAFT_EMPTY` aborta sin OCI (tampoco entró al buffer).
  - *Criterio:* **Dado** un lote válido <1MB con activos, **Cuando** se guarda, **Entonces** responde `201` con URL `oci://{bucket}/paquetes/paquete-{batchId}.json`. **Dado** fallo OCI, **Cuando** ocurre, **Entonces** solo `LOG` error sin PII, sin evento `package.completed` al frontend (el `asset.created` vivo ya se emitió).
- **RF-02 — Idempotencia doble:** `SADD buffer:current:ids {messageId}` evita duplicados en buffer (reenvío mismo `messageId` no duplica JSON); reenviar el mismo `batchId` lote no duplica objetos OCI, sobrescribe o devuelve el existente con `deduped:true`.
  - *Criterio:* **Dado** `messageId` ya bufferizado, **Cuando** re-llega, **Entonces** no se hace `RPUSH` ni `INCRBY`. **Dado** `batchId` lote ya guardado, **Cuando** se re-guarda, **Entonces** 1 solo objeto en bucket y `deduped:true`.
- **RF-03 — Health público:** `GET /actuator/health` responde `{"status":"UP"}` sin auth, `p95 < 100ms`.
  - *Criterio:* **Dado** app arrancada, **Cuando** se llama `/actuator/health`, **Entonces** `200 + UP`.
- **RF-04 — Docs API:** Swagger UI en `/swagger-ui.html` documenta `POST /api/v1/analyze`, `POST /api/v1/generate`, `POST /api/v1/packages:run`, `POST /api/v1/packages:newsletter`, `GET /api/v1/packages`, `GET /api/v1/packages/{batchId}` y `GET /api/v1/events` (SSE). La ingesta Discord es vía bot JDA `#Listen` y no aparece en Swagger (P3 no aplica a Gateway).
  - *Criterio:* **Dado** app arrancada, **Cuando** se abre Swagger, **Entonces** los endpoints aparecen con ejemplos.
- **RF-05 — Seguridad básica:** CORS allowlist por env, headers seguros, 413 en >10MB resto API, 400 con formato problema, sin stacktraces. Sin rate-limit ni `429` en Fase 1 (decisión 001: alcance MVP + riesgo de pérdida).
  - *Criterio:* **Dado** `Origin` no permitido, **Cuando** se llama a la API, **Entonces** bloqueado por CORS; **Dado** `Origin` permitido, pasa.
- **RF-06 — Pipeline orquestado MVP con fork:** el bot Discord `#Listen` dispara `ingesta→análisis→generación` por mensaje (para la demo); tras `003` hay fork en paralelo: `A) SSE inmediato asset.created` al frontend + `B) RPUSH buffer Redis` para OCI batch. `POST /api/v1/packages:run` queda como disparo manual/flush con el mismo resultado. Si `002` da `LLM_FALLBACK` o `003` da vacío/`DRAFT_EMPTY` → aborta sin SSE ni buffer/OCI, solo `LOG + ⚠️`. Si OCI falla en flush → solo `LOG + ⚠️`, el frontend ya recibió el vivo pero no `package.completed`.
  - *Criterio:* **Dado** un mensaje válido procesado en `#Listen`, **Cuando** termina `003`, **Entonces** hay `asset.created {persisted:false}` en <5s + `RPUSH` a Redis; **Cuando** el buffer alcanza tamaño, **Entonces** hay `201` interno con `{batchId lote, assetsCount=N, packageUrl}` + evento `package.completed` + acuse `✅`; si abort/fallo LLM → sin SSE ni buffer, con `⚠️` solo en Discord.
- **RF-07 — Suscripción SSE del dashboard (requerida, doble evento):** `GET /api/v1/events` (`text/event-stream`) emite `asset.created` inmediato con lo procesado `{type, messageId, batchId-abierto, payload:{assets}, persisted:false}` y `package.completed` con **lo guardado en OCI** `{type, batchId lote, payload:{assets,packageUrl}}`, con reconexión por `Last-Event-ID`. Fallos LLM solo `LOG`; fallos OCI solo `LOG` sin `package.completed`.
  - *Criterio:* **Dado** un dashboard suscrito, **Cuando** se procesa un mensaje, **Entonces** recibe `asset.created` en <5s; **Cuando** se guarda un paquete batch, **Entonces** recibe `package.completed`; **Dado** reconexión con `Last-Event-ID`, **Entonces** no pierde eventos ya emitidos.
- **RF-08 — Recuperación desde backend (requerida):** solo el backend habla con OCI/Redis; el frontend recupera persistido vía `GET /api/v1/packages` (lista paginada desde `listObjects`) y `GET /api/v1/packages/{batchId}` (detalle vía `getObject`). Lo vivo (`asset.created`) no es recuperable por GETs hasta el flush; si el frontend cae mezcla `GETs persistidos + SSE vivo` sin pérdida de lo persistido.
  - *Criterio:* **Dado** frontend reiniciado, **Cuando** llama `GET /api/v1/packages`, **Entonces** recibe lo persistido en OCI (el buffer abierto no aparece hasta cerrar).
- **RF-09 — Newsletter batch cada 5 días (requerida, 2º nivel):** `@Scheduler` prod (`cron 00:00 UTC */5`, `NEWSLETTER_BATCH_SIZE=15`) lee `listObjects` de `paquete-*.json` batch (no del buffer vivo), sorteo aleatorio de 15 paquetes (aborta si `<15`), pasa anonimizados al LLM, genera `Asset{NEWSLETTER}` y guarda `paquetes/newsletter-{yyyy-MM-dd}.json` + SSE `package.completed` igual que batch vivo. `POST /api/v1/packages:newsletter` dispara lo mismo manual para demo. Fallos solo `LOG`.
  - *Criterio:* **Dado** ≥15 paquetes, **Cuando** corre el job, **Entonces** guarda 1 newsletter con `≥3 temas`; si `<15` o fallback → abort sin guardar.

## 3. RNF

- **RNF-01:** objeto OCI `<1MB`, `Content-Type: application/json`, cifrado del lado OCI por defecto Always Free. Buffer Redis cierra por `REDIS_BUFFER_MAX_BYTES=921600 (900KB)` env para dejar ~100KB margen de envelope bajo el 1MB.
- **RNF-02:** credenciales OCI solo por env/instancia (`OCI_BUCKET`, `OCI_REGION`, `OCI_*_KEY`), Redis solo por env (`REDIS_HOST/PORT`), nunca en repo.
- **RNF-03:** resto de actuadores (`env,beans,mappings`) apagados en Fase 1.
- **RNF-04:** LLM (`002+003`) por mensaje individual con fork `SSE inmediato + RPUSH Redis`; OCI batch por tamaño en bytes (`N:1`), el orquestador no hace `1:1`. Excepción 2º nivel: job newsletter batch (RF-09) con 15 paquetes aleatorios desde OCI.

## 4. Dominio y Glosario

- **AssetPackage:** `{batchId=uuid lote Redis o newsletter-{fecha} (batch), generatedAt, source: DISCORD, assets: Asset[] (vivo: LINKEDIN/X/FAQ simple de N mensajes), stats:{received=N, analyzed=N, fallbackCount, assetsCount=N}, promptVersion, packageUrl?}`. Anónimo total, sin `author/ids` (solo `messageId/batchId` en logs).
- **Redis buffer (volátil, no persistente):** keys `buffer:current:id (STRING uuid lote abierto)`, `buffer:current:list (LIST RPUSH json procesado)`, `buffer:current:ids (SET SADD messageId dedup)`, `buffer:current:bytes (STRING INCRBY len)`. Solo Java `spring-data-redis`, sin Lua. Flush al alcanzar tamaño → `LRANGE` → envuelve `LIST` válida de JSONs en `assets[]` → OCI.
- **Bucket:** contenedor OCI. Propuesta TBD por dev cuenta: `communitylab-assets`, región `sa-saopaulo-1`, prefijo `paquetes/`, keys `paquete-{batchId lote}.json` y `newsletter-{fecha}.json`.
- **Mensaje procesado con fork:** cada `Comment` de `#Listen` pasa por 001→002→003 por mensaje; si hay activos → `A) asset.created inmediato (persisted:false)` + `B) RPUSH Redis`; si fallback/vacío → sin SSE ni buffer (solo `LOG + ⚠️`); si OCI-fail en flush → sin `package.completed` (solo `LOG + ⚠️`, el vivo ya se emitió).
- **Acuse Discord:** `✅` si `asset.created + RPUSH` (y `package.completed` al cerrar lote), `⚠️` si abort/fallo. Sin contenido ni PII.

## 5. Fuera de Alcance

- DB relacional, auth/usuarios/roles, publicación automática a LinkedIn/X, CDN público, versionado histórico de paquetes, multi-bucket/multi-tenant, métricas Prometheus/Grafana (Fase >1).
