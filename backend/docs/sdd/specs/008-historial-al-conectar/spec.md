# Especificación 008: Historial al conectar (snapshot por SSE)

> Respeta: `constitution.md` v1.9-revision-versionada (P1-P6, R1-R8, Q1-Q5, Fase 1). Extiende `004` (SSE), `005` (dual-get) y `006` (paquete + PAR) sin cambiar el flush ni el formato de los eventos. Solo QUÉ/POR QUÉ.
> Alcance: cuando un dashboard abre el stream SSE de una fuente sin un `Last-Event-ID` que el backend pueda continuar, recibe primero el **historial vigente** (paquetes con PAR no vencida + mensajes procesados que aún no llegaron a OCI) y después los eventos en vivo, por la misma conexión.
> Secuencia: `GET /api/v1/{discord|telegram}/messages → :connected → package.completed × N (PAR vigente) → asset.created × M (lotes pendientes + lote abierto) → snapshot.completed → vivo (asset.created / package.completed / message.revised / :heartbeat)`.

## 1. Problema y Objetivo

Hoy el dashboard solo ve lo que ocurre mientras está conectado. Una pestaña nueva, otro usuario o el mismo usuario tras recargar no ven nada: 004 RF-08 lo definió así ("solo recibe eventos nuevos desde la suscripción") y el replay por `Last-Event-ID` vive en memoria, está limitado a 100 eventos por fuente y se pierde en cada redeploy. Los datos sí persisten (Redis con AOF + objetos en OCI), pero ningún usuario que llegue tarde puede llegar a ellos: la PAR de cada paquete solo viajó en el `package.completed` en vivo y los mensajes del lote abierto solo salieron por SSE a quien estaba conectado.

Objetivo 008: que **todo usuario que abra la web vea el mismo historial de los últimos 3 días** y siga recibiendo los eventos nuevos, demostrando la persistencia de extremo a extremo sin añadir endpoints ni cambiar el formato de los eventos que el frontend ya procesa.

## 2. Requerimientos Funcionales

- **RF-01 — Snapshot al conectar sin `Last-Event-ID`:** al suscribirse a `GET /api/v1/discord/messages` o `GET /api/v1/telegram/messages` sin `Last-Event-ID`, el backend envía, en este orden y antes de cualquier evento en vivo pendiente de esa conexión:
  1. el comentario `:connected` (sin cambios respecto al hallazgo de 006 TASK-006-05);
  2. un `package.completed` por cada paquete de esa fuente con PAR **vigente** (`expiresAt > ahora`), del más antiguo al más reciente, con el mismo formato y el mismo `id=batchId` que el evento en vivo (006 RF-06);
  3. un `asset.created` por cada mensaje procesado de esa fuente que **aún no está en OCI** (lotes sellados pendientes y lote abierto), con el mismo formato (`ResponseClient`) y el mismo `id=messageId` que el evento en vivo, incluidos los `LLM_FALLBACK` (igual que el vivo, 004 RF-06);
  4. un evento `snapshot.completed` (RF-04).
  - *Criterio:* **Dado** 2 paquetes Telegram con PAR vigente y 3 mensajes en el lote abierto, **Cuando** un dashboard se suscribe al stream Telegram sin `Last-Event-ID`, **Entonces** recibe `:connected`, 2 `package.completed` (el más antiguo primero), 3 `asset.created` y `snapshot.completed`, en ese orden. **Dado** un paquete con PAR vencida, **Cuando** se suscribe, **Entonces** ese paquete no aparece. **Dado** ningún dato, **Cuando** se suscribe, **Entonces** recibe `:connected` y `snapshot.completed` con contadores `0`.
- **RF-02 — Reconexión: replay si se puede, snapshot si no:** con un `Last-Event-ID` presente en el replay en memoria de su fuente, el backend repite solo lo posterior (comportamiento actual, sin snapshot). Con un `Last-Event-ID` **desconocido** (p. ej. tras un redeploy, o porque salió de la ventana de 100 eventos) envía el snapshot completo de RF-01. En ambos casos el bloque inicial termina con `snapshot.completed`.
  - *Criterio:* **Dado** un `Last-Event-ID` presente en el replay, **Cuando** el dashboard se reconecta, **Entonces** recibe solo los eventos posteriores y `snapshot.completed`, sin `package.completed` anteriores. **Dado** un backend reiniciado y un `Last-Event-ID` de antes del reinicio, **Cuando** el dashboard se reconecta, **Entonces** recibe el snapshot completo.
- **RF-03 — Índice de paquetes vigentes:** cada vez que un paquete queda guardado y anunciado (`package.completed`, incluido el re-flush `ALREADY_STORED` con PAR nueva de 006 RF-05), el backend registra `{batchId, source, assetsCount, parUrlBase, expiresAt}` en un índice por fuente. Un re-flush del mismo `batchId` **sustituye** la entrada (una por lote, con la PAR más reciente). Las entradas vencidas nunca se envían y se eliminan. El índice sobrevive a un reinicio del backend.
  - *Criterio:* **Dado** un flush correcto, **Cuando** se suscribe una pestaña nueva, **Entonces** recibe ese paquete en el snapshot. **Dado** un re-flush del mismo `batchId` con PAR nueva, **Cuando** se suscribe, **Entonces** recibe un solo `package.completed` para ese lote, con la PAR nueva. **Dado** un backend reiniciado, **Cuando** se suscribe, **Entonces** el snapshot sigue incluyendo los paquetes vigentes anteriores al reinicio.
- **RF-04 — Fin de historial (`snapshot.completed`):** evento SSE con nombre `snapshot.completed`, **sin `id`** (no mueve el `Last-Event-ID` del navegador) y `data: {type:"snapshot.completed", packages, pending}` (`packages` = `package.completed` enviados en el bloque inicial, `pending` = `asset.created` enviados en el bloque inicial). Se envía una vez por conexión. Indica al frontend que ya tiene el historial (p. ej. para quitar el estado de carga).
  - *Criterio:* **Dado** un snapshot con 2 paquetes y 3 mensajes, **Cuando** termina, **Entonces** llega `snapshot.completed {packages:2, pending:3}` sin línea `id:`.
- **RF-05 — Contrato idempotente para el frontend:** el historial usa los mismos tipos de evento e `id` que el vivo, y un evento en vivo puede llegar mientras se envía el snapshot. El mismo `messageId` o `batchId` puede llegar más de una vez; el frontend debe **actualizar si ya existe** por `id`, nunca duplicar. El frontend lee cada `parUrlBase` directo de OCI como hoy (006 RF-04; CORS de OCI verificado el 2026-10-14: `Access-Control-Allow-Origin: *`, sin `credentials: "include"`).
  - *Criterio:* **Dado** un mensaje que llega en vivo mientras se envía el snapshot, **Cuando** el lote abierto ya lo contiene, **Entonces** el stream puede repetir su `asset.created` con el mismo `id` y ningún mensaje del historial se pierde.
- **RF-06 — Aislamiento por fuente:** el snapshot de una fuente solo contiene paquetes y mensajes de esa fuente (dual-get, constitución v1.7).
  - *Criterio:* **Dado** datos en Discord y Telegram, **Cuando** se suscribe al stream Discord, **Entonces** ningún `batchId` ni `messageId` de Telegram aparece.
- **RF-07 — Degradado sin romper el stream ni el flush:** si Redis no responde al montar el snapshot, la conexión se abre igual con lo que se pudo leer (posiblemente nada) y `snapshot.completed`; con `OCI_AUTH_MODE=none` no hay paquetes en el índice. Un fallo al registrar en el índice (RF-03) nunca hace fallar el flush ni el `package.completed` en vivo.
  - *Criterio:* **Dado** Redis caído, **Cuando** se suscribe, **Entonces** recibe `:connected` y `snapshot.completed {packages:0, pending:0}` y sigue recibiendo el vivo. **Dado** un fallo al escribir el índice, **Cuando** se guarda un paquete, **Entonces** el `package.completed` en vivo se emite igual y el lote no vuelve a pendientes.

## 3. RNF

- **RNF-01 — Ventana de 3 días:** el historial cubre los paquetes con PAR vigente (`PAR_TTL_DAYS=3`, 006 RNF-03) más lo que aún no llegó a OCI. Un paquete de más de 3 días no aparece: renovar PAR sigue fuera de Fase 1 (006 §5). Decidido 2026-10-14: suficiente para demostrar la persistencia.
- **RNF-02 (Q3, riesgo aceptado 2026-10-14):** sin autenticación (constitución §5), **cualquiera que abra el stream** recibe todas las PAR vigentes de esa fuente, y los paquetes llevan `authorName/authorId` (006 RNF-06). La PAR es visible en las herramientas del navegador y puede compartirse fuera de la web hasta que vence. Ya ocurría con el `package.completed` en vivo; el snapshot lo extiende a quien llega después. CORS solo limita a navegadores en orígenes no permitidos, no a `curl`. Mitigación: PAR de solo lectura por objeto, sin listing, vencimiento a 3 días, nunca en logs. Ocultar la PAR exige que el backend sirva el contenido (descartado, §6).
- **RNF-03 — Coste del snapshot:** montarlo solo lee Redis (sin llamadas a OCI ni al LLM): objetivo `p95 < 300ms` local, como la ingesta (R6). Tamaño acotado: lote abierto `< 900KB` + lotes pendientes + índice de como mucho `~72` entradas por fuente con el flush horario de producción (con el cron temporal de desarrollo cada 5 min, hasta `~864`). El frontend hace un `GET` a OCI por paquete del snapshot.
- **RNF-04 (Q3/R5):** logs de la suscripción solo `source` y contadores (`packages`, `pending`); nunca `parUrlBase`, contenido ni `authorName`. La PAR guardada en Redis sigue el régimen de 006 RNF-06: Redis sin puertos publicados, nunca en logs.

## 4. Dominio y Glosario

- **Historial vigente:** paquetes de una fuente con PAR no vencida + mensajes procesados de esa fuente que aún no están en OCI.
- **Snapshot:** el bloque inicial de eventos que reproduce el historial vigente al abrir el stream; usa los mismos eventos que el vivo.
- **Índice de paquetes:** registro por fuente, en Redis, de los `package.completed` emitidos (`batchId → {assetsCount, parUrlBase, expiresAt}`); una entrada por lote.
- **Lote pendiente:** lote sellado que todavía no se guardó en OCI (006 RF-07); sus mensajes ya salieron por SSE pero no están en ningún paquete.
- **`snapshot.completed`:** marca de fin del bloque inicial; sin `id`.

## 5. Fuera de Alcance

- Historial de más de 3 días o renovación de PAR (006 §5, Fase 2).
- Endpoints `GET /packages*` o de historial (constitución v1.6/v1.7): el historial viaja por el `GET` SSE existente.
- Que el backend sirva el contenido de los paquetes en vez de la PAR.
- Paginación o filtros del historial; estado por usuario (leído/no leído).
- Repetir `message.revised` en el snapshot: la PAR sirve siempre la última versión del paquete (007 RF-07).

## 6. Trazabilidad y decisiones

- Deriva de: `004` RF-06/RF-08 (SSE + replay), `005` RF-07 (dual-get), `006` RF-03/RF-04/RF-05/RF-06/RNF-03 (paquete, PAR por objeto, re-flush, `package.completed`, TTL) y `007` RF-07 (la PAR sirve la última versión).
- **Enmienda a `004` RF-08:** su criterio "solo recibe eventos nuevos desde la suscripción" queda sustituido por RF-01/RF-02 de esta spec. Sigue vigente el resto de RF-08: sin `GET /packages*`, solo el backend habla con OCI/Redis.
- Sin enmienda constitucional: no hay endpoint nuevo, Redis y OCI siguen siendo internos (v1.6) y el dashboard sigue consumiendo solo SSE + PAR (§5).
- *Decidido 2026-10-14 (dev):* ventana = PAR vigentes (3 días); todo usuario ve el mismo historial; la PAR puede llegar al navegador (riesgo RNF-02); el cron de 5 min es temporal de desarrollo, producción se queda en 1 h.
- *Alternativa descartada 2026-10-14:* que el backend lea cada paquete de OCI y lo envíe como `asset.created`, sin exponer la PAR. Cambiaba constitución v1.9 (`GetObject` solo para revisar), 006 RF-04/RF-06 y 007 RF-07/RF-08, y hasta 72 lecturas a OCI por pestaña nueva y fuente, para ocultar una URL que no da acceso a nada que el usuario no reciba ya.
- *Alternativa descartada 2026-10-14:* caché en el frontend (`localStorage`) o guardar ahí las PAR: es por usuario y por navegador, no un historial común.
- `sdd-audit` obligatorio antes de codificar.
