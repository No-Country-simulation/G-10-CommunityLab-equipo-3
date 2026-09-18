# Especificación 004: Paquete OCI + transversal (health, docs, seguridad básica)

> Respeta: `constitution.md` v1.0-final. Cierra el pipeline Fase 1. Solo QUÉ/POR QUÉ.

## 1. Problema y Objetivo

Sin persistencia del resultado ni superficie operable, la demo del hackathon no es verificable. Objetivo 004: guardar el `PaqueteDeActivos` en OCI Object Storage (único storage Fase 1) y exponer transversalmente `health`, Swagger y seguridad básica.

## 2. Requerimientos Funcionales

- **RF-01 — Guardado OCI:** al completar 001->003 (mensaje por mensaje), persistir `paquete-{batchId}.json` en bucket configurado y devolver `{packageUrl, etag, sizeBytes}`.
  - *Criterio:* **Dado** un paquete válido <1MB, **Cuando** se guarda, **Entonces** responde `201` con URL `oci://{bucket}/paquetes/{batchId}.json` accesible con credenciales OCI del entorno.
- **RF-02 — Idempotencia por batch:** reenviar el mismo `batchId` no duplica objetos, sobrescribe o devuelve el existente con `deduped:true`.
  - *Criterio:* **Dado** `batchId` ya guardado, **Cuando** se re-guarda, **Entonces** 1 solo objeto en bucket y `deduped:true`.
- **RF-03 — Health público:** `GET /actuator/health` responde `{"status":"UP"}` sin auth, `p95 < 100ms`.
  - *Criterio:* **Dado** app arrancada, **Cuando** se llama `/actuator/health`, **Entonces** `200 + UP`.
- **RF-04 — Docs API:** Swagger UI en `/swagger-ui.html` documenta `POST /api/v1/ingest`, `POST /api/v1/analyze`, `POST /api/v1/generate`, `POST /api/v1/packages`.
  - *Criterio:* **Dado** app arrancada, **Cuando** se abre Swagger, **Entonces** los 4 endpoints aparecen con ejemplos.
- **RF-05 — Seguridad básica:** CORS allowlist por env, headers seguros, 413 en >10MB, 400 con formato problema, 429 en rate-limit, sin stacktraces.
  - *Criterio:* **Dado** `Origin` no permitido, **Cuando** se llama a la API, **Entonces** bloqueado por CORS; **Dado** `Origin` permitido, pasa.
- **RF-06 — Pipeline orquestado MVP:** `POST /api/v1/packages:run` ejecuta ingesta->análisis->generación->OCI mensaje por mensaje (para la demo).
  - *Criterio:* **Dado** JSON Discord válido de 1 mensaje, **Cuando** se llama `/packages:run`, **Entonces** `201` con `{batchId, assetsCount, packageUrl}` o `207` si hubo fallbacks parciales.

## 3. RNF

- **RNF-01:** objeto OCI `<1MB`, `Content-Type: application/json`, cifrado del lado OCI por defecto Always Free.
- **RNF-02:** credenciales OCI solo por env/instancia (`OCI_BUCKET`, `OCI_REGION`, `OCI_*_KEY`), nunca en repo.
- **RNF-03:** resto de actuadores (`env,beans,mappings`) apagados en Fase 1.
- **RNF-04:** cada mensaje se orquesta individualmente; el orquestador acumula por `batchId` sin retención de lotes grandes.

## 4. Dominio y Glosario

- **PaqueteDeActivos:** `{batchId, generatedAt, source: DISCORD|TELEGRAM|MIXED, assets: Activo[], stats:{received, analyzed, fallbackCount, assetsCount}, promptVersion, packageUrl?}`.
- **Bucket:** contenedor OCI. Propuesta TBD: `communitylab-assets`, región `sa-saopaulo-1`, prefijo `paquetes/`.
- **Mensaje individual:** cada `Comentario` ingerido pasa por 001→002→003 y se almacena en el paquete acumulado por `batchId`.

## 5. Fuera de Alcance

- DB relacional, auth/usuarios/roles, publicación automática a LinkedIn/X, CDN público, versionado histórico de paquetes, multi-bucket/multi-tenant, métricas Prometheus/Grafana (Fase >1).
