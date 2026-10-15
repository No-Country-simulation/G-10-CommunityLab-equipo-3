# Especificación 009: Estado de Object Storage (métricas del bucket)

> Respeta: `constitution.md` v1.10-metricas-storage (P1-P6, R1-R8, Q1-Q5, Fase 1). Requiere la enmienda v1.10 (endpoint `GET` de solo lectura no SSE + `oci-java-sdk-monitoring` en el stack). Solo QUÉ/POR QUÉ.
> Alcance: un endpoint `GET /api/v1/storage/stats` que devuelve una foto del estado del bucket de paquetes: espacio usado y su límite, número de objetos y llamadas recibidas por el bucket en el mes en curso y su límite. El backend solo envía los datos (`usado` + `límite`); el frontend los formatea y los pinta (`x / 10 GB Standard`, `x / 50 000 este mes`). Los datos salen de OCI Monitoring y el backend los cachea.
> Secuencia: `Dashboard → GET /api/v1/storage/stats → caché vigente ? foto : OCI Monitoring (StoredBytes, ObjectCount, *Requests, ClientErrors) → foto + caché`.

## 1. Problema y Objetivo

El bucket `MessagesUsers` es el único almacenamiento persistente (constitución §2), pero hoy nadie ve desde la web cuánto ocupa, cuánto queda dentro del margen gratuito ni cuánto se usa. Las lecturas del dashboard van directas a OCI por PAR (006 RF-04, 008), así que el backend no puede contarlas: solo OCI las ve.

Objetivo 009: que el dashboard muestre el estado del bucket (uso frente al límite, objetos, llamadas del mes frente al límite, por tipo, y errores de cliente) con un número verificable, sin credenciales en el navegador, sin ampliar los permisos del backend sobre el bucket y sin que el número de pestañas abiertas multiplique las llamadas a OCI.

## 2. Requerimientos Funcionales

- **RF-01 — Foto del estado:** `GET /api/v1/storage/stats` responde `200` con un JSON que contiene:
  1. `storage.tier`: `STANDARD` (tier del bucket, frente al que se mide el límite);
  2. `storage.usedBytes`: último valor de `StoredBytes` del bucket en el tier Standard (dimensión `tier = "NORMAL"` en Monitoring): **todo lo almacenado**, versiones actuales y anteriores (Object Versioning, spec 007), para leerlo directamente como `usado / límite`;
  3. `storage.objectCount`: último valor de `ObjectCount` en el tier Standard; con Object Versioning cuenta **versiones almacenadas** (actuales + anteriores, sin delete markers), no solo objetos visibles;
  4. `storage.limitBytes` (RF-02);
  5. `storage.measuredAt`: instante del dato de `StoredBytes` usado;
  6. `requests.periodStart` (inicio del mes en curso, RF-03) y `requests.limit` (RF-02);
  7. `requests`: suma desde `periodStart` (RF-03) de `AllRequests` (`total`, el valor que se compara con `limit`), `GetRequests` (`get`), `PutRequests` (`put`), `ListRequests` (`list`), `HeadRequests` (`head`), `DeleteRequests` (`delete`) y `ClientErrors` (`clientErrors`);
  8. `generatedAt` (cuándo se consultó OCI) y `stale` (RF-05).
  El backend no envía campos derivados (disponible, porcentaje) ni textos formateados: el frontend los calcula y los pinta.
  - *Criterio:* **Dado** un bucket con `StoredBytes=1048576`, `ObjectCount=12` y, en lo que va de mes, 40 `GetRequests` + 5 `PutRequests`, **Cuando** se llama al endpoint, **Entonces** responde `200` con `usedBytes=1048576`, `limitBytes=10737418240`, `objectCount=12`, `requests.get=40`, `requests.put=5`, `requests.limit=50000` y `stale=false`.
- **RF-02 — Límites configurados:** OCI no impone un límite por bucket; los límites del margen gratuito se envían desde configuración. Espacio: `STORAGE_LIMIT_BYTES` (por defecto `10737418240` = 10 GB, el margen de Object Storage **Standard** de una cuenta con créditos o de pago; en una cuenta solo Always Free son 20 GB compartidos entre Standard, Infrequent Access y Archive, y se configura con la variable). Llamadas: `STORAGE_REQUESTS_MONTHLY_LIMIT` (por defecto `50000`, peticiones a la API de Object Storage al mes del margen gratuito). Los valores se envían tal cual; el uso puede superar el límite y se envía igual (sin recortar).
  - *Criterio:* **Dado** la configuración por defecto, **Cuando** se llama, **Entonces** `storage.limitBytes=10737418240` y `requests.limit=50000`. **Dado** `STORAGE_LIMIT_BYTES=21474836480`, **Cuando** se llama, **Entonces** `storage.limitBytes=21474836480`. **Dado** `usedBytes` mayor que el límite, **Cuando** se llama, **Entonces** se envían ambos valores sin modificar.
- **RF-03 — Llamadas del mes en curso:** las llamadas se suman desde el día 1 del mes en curso a las `00:00Z` (`periodStart`, UTC, como el ciclo de facturación de OCI) hasta el momento de la consulta. El día 1 el contador vuelve a empezar. Una métrica sin datos en el periodo vale `0`. Incluye las llamadas de cualquier cliente (backend y navegadores por PAR).
  - *Criterio:* **Dado** el 14 de octubre, **Cuando** se llama, **Entonces** `requests.periodStart=2026-10-01T00:00:00Z` y solo se suman llamadas desde ese instante. **Dado** el 1 de noviembre a las `00:30Z`, **Cuando** se llama, **Entonces** `periodStart=2026-11-01T00:00:00Z` y no cuenta ninguna llamada de octubre. **Dado** un mes sin llamadas, **Cuando** se llama, **Entonces** todos los contadores de `requests` valen `0` y la respuesta es `200`.
- **RF-04 — Caché compartida:** el backend consulta OCI como mucho una vez por `STORAGE_STATS_CACHE_TTL` (por defecto `PT60S`) para todos los clientes; mientras la foto está vigente se sirve sin llamar a OCI.
  - *Criterio:* **Dado** una foto obtenida hace 10 s, **Cuando** 10 dashboards llaman al endpoint, **Entonces** OCI Monitoring no recibe ninguna consulta nueva y todos reciben el mismo `generatedAt`. **Dado** una foto de hace más de 60 s, **Cuando** se llama, **Entonces** se consulta OCI y `generatedAt` cambia.
- **RF-05 — Degradado:** si OCI Monitoring falla (red, `401/403`, `429`, `5xx`) y existe una foto anterior, se devuelve esa foto con `200` y `stale=true`. Si no hay foto anterior, `503` con el cuerpo de error estándar. Con `OCI_AUTH_MODE=none`, `503` sin intentar llamar a OCI. Un fallo nunca afecta al flush, al SSE ni a la revisión.
  - *Criterio:* **Dado** una foto previa y Monitoring devolviendo `500`, **Cuando** se llama, **Entonces** `200` con la foto previa y `stale=true`. **Dado** que nunca se obtuvo foto y Monitoring falla, **Cuando** se llama, **Entonces** `503`. **Dado** `OCI_AUTH_MODE=none`, **Cuando** se llama, **Entonces** `503`.
- **RF-06 — Contrato documentado:** Swagger documenta el endpoint, el esquema de la respuesta, un ejemplo y los códigos `200`/`503` (P3).
  - *Criterio:* **Dado** la app arrancada, **Cuando** se abre Swagger, **Entonces** aparece `GET /api/v1/storage/stats` con su ejemplo.

## 3. RNF

- **RNF-01 — Frescura (P4):** `requests` refleja las llamadas con un retraso de hasta `~5 min` (OCI emite cada 100 ms pero agrega y publica con retraso); `usedBytes`/`objectCount` se publican **cada hora**, así que pueden ir hasta `~1 h` por detrás de lo real. `measuredAt` permite al dashboard mostrarlo. Datos "en tiempo real" quedan fuera.
- **RNF-02 — Latencia:** respuesta desde caché `p95 < 50ms` local; con consulta a OCI, `p95 < 3000ms` (las consultas a Monitoring van en paralelo).
- **RNF-03 — Coste y límites de OCI:** como mucho `9` consultas a Monitoring por `STORAGE_STATS_CACHE_TTL`, independientemente del número de clientes (`~13 000/día` con TTL de 60 s), dentro del Always Free de Monitoring. Las consultas a Monitoring no cuentan como llamadas al bucket.
- **RNF-04 — Mínimo privilegio (R4):** el backend solo obtiene `read metrics` del compartment `communityLab`, limitado al namespace `oci_objectstorage`. **No** se amplían los permisos sobre el bucket (sigue sin `BUCKET_READ`, `OBJECT_INSPECT` ni `OBJECT_DELETE`). En producción con Instance Principal; sin claves en repo, env ni imagen.
- **RNF-05 — Exposición (Q3, riesgo aceptado):** sin autenticación (Fase 1), cualquiera puede leer volumen y tasa de llamadas del bucket. La respuesta **no** incluye namespace, OCID de compartment ni de bucket, región ni PAR; tampoco el nombre del bucket. CORS con la allowlist existente. Logs: solo `periodStart`, duración de la consulta y error de OCI (código), nunca OCIDs ni el texto completo de la respuesta.

## 4. Dominio y Glosario

- **Foto (snapshot de estado):** respuesta completa del endpoint, calculada a partir de una consulta a Monitoring; tiene `generatedAt` y caduca a los `STORAGE_STATS_CACHE_TTL`.
- **Límites (espacio y llamadas):** valores configurados del margen gratuito que el frontend usa como denominador; no los impone OCI.
- **Periodo:** intervalo `[día 1 del mes 00:00Z, ahora]` sobre el que se suman las llamadas.
- **`stale`:** la foto servida es anterior a la última consulta fallida a OCI.
- **OCI Monitoring:** servicio de métricas de OCI; namespace `oci_objectstorage`, dimensiones `resourceDisplayName` (nombre del bucket), `resourceId`, `tier`.

## 5. Fuera de Alcance

- Stream SSE de métricas o evento `storage.stats` en los streams existentes (los datos cambian como mucho cada minuto).
- Latencias (`FirstByteLatency`, `TotalRequestLatency`), `UncommittedParts`, `EnabledOLM`, series temporales o gráficas históricas (solo totales del mes en curso); meses anteriores.
- Métricas de Redis, del LLM o del backend (Actuator `metrics` sigue cerrado, constitución §2).
- Alarmas o avisos al acercarse al límite; campos derivados o formateados (disponible, porcentaje, `"2.5 GB"`): los calcula el frontend.
- Coste en dinero (facturación es Fase >1).
- Contar las llamadas por fuente (`DISCORD`/`TELEGRAM`): Monitoring agrega por bucket.

## 6. Trazabilidad y decisiones

- Deriva de: constitución §2 (OCI Object Storage, Always Free), `006` RF-02/RF-04 (Instance Principal, lectura del frontend por PAR) y `004` RF-04 (Swagger).
- **Enmienda constitucional v1.10-metricas-storage:** añade `GET /api/v1/storage/stats` (lectura, no SSE, sin datos de negocio) a la API y `oci-java-sdk-monitoring` al stack cerrado. v1.6/v1.7 siguen vigentes para los datos de negocio: sin `GETs packages`.
- **Enmienda a `004` RF-04:** Swagger documenta también `GET /api/v1/storage/stats`.
- *Decidido 2026-10-14 (dev):* las llamadas salen de OCI Monitoring (cuentan también las lecturas del navegador por PAR); transporte `GET` REST con caché, el frontend consulta periódicamente; el espacio usado es el total almacenado en el tier Standard (versiones anteriores incluidas) frente a un límite configurable (10 GB por defecto); las llamadas son las del mes en curso frente a `50000`; el backend envía `usado` + `límite` y el frontend pinta `x / 10 GB Standard` y `x / 50 000 este mes`.
- *Alternativa descartada 2026-10-14:* contador propio en Redis (no ve las lecturas por PAR, que son la mayoría); evento SSE periódico (duplicado en los dos streams aunque el bucket es uno, y sin valor con datos que cambian por minuto u hora); `ObjectStorage.getBucket(approximateSize, approximateCount)` para el espacio (exige `BUCKET_READ`, un permiso más sobre el bucket, para un dato también aproximado y periódico).
- *Resuelto 2026-10-14 (TASK-009-00):* `StoredBytes` **incluye las versiones anteriores**: su valor (`71413`) coincide con la suma de todas las versiones del bucket (`list-object-versions`) y con `approximateSize`. El tier Standard aparece como `tier = "NORMAL"`. `ObjectCount` (`11`) cuenta versiones, no objetos visibles.
- *Nota:* los márgenes gratuitos (10 GB y 50 000 llamadas/mes) son por **tenancy**, no por bucket. Hoy `MessagesUsers` es el único bucket; si se crearan otros, consumirían del mismo margen sin aparecer aquí.
- `sdd-audit` obligatorio antes de codificar.
