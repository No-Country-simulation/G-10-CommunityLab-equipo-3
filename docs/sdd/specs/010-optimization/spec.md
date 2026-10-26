# Especificación 010: Optimización de rendimiento (Redis, historial SSE, hilos y caché de paquetes)

> Respeta: `constitution.md` v1.13-optimizacion (P1-P6, R1-R8, Q1-Q5, Fase 1). Requiere la enmienda v1.13 (script Lua para el append del buffer, copia de paquetes confirmados en Redis y virtual threads). Solo QUÉ/POR QUÉ.
> Alcance: sin endpoints, eventos ni contratos nuevos. Cambia cómo el backend usa Redis, OCI y los hilos de la VM (Ampere A1, **2 núcleos** Neoverse-N1) para que la ingesta no pierda mensajes, la reconexión SSE tarde lo mismo con 1 o con 20 lotes pendientes y un Redis u OCI lentos no congelen el resto del sistema.
> Secuencia: `mensaje → LLM (virtual thread, máx. LLM_MAX_CONCURRENCY) → append atómico (1 viaje) → flush → OCI (hilo de plataforma)`; `reconexión SSE → historial en viajes fijos`; `PATCH → caché del paquete ? sin GET : GET OCI (+ copia) → invalidar copia → PUT If-Match → copia nueva`.

## 1. Problema y Objetivo

La revisión de rendimiento del 2026-10-25 encontró cinco problemas:

1. **Pérdida de mensajes en el append.** `SADD` (dedup), `RPUSH` e `INCRBY` van en tres viajes separados. Si el proceso o Redis caen entre el `SADD` y el `RPUSH`, el `messageId` queda marcado como visto sin registro, y cada reintento se descarta como duplicado.
2. **Timeouts de Redis ignorados.** La factoría Lettuce se construye a mano, así que `spring.data.redis.timeout` no se aplica en ningún perfil. Lettuce espera 60 s por comando y un Redis colgado bloquea la ingesta, el flush y el historial durante un minuto.
3. **Historial SSE con coste creciente.** Cada reconexión (corte de Cloudflare a los 100 s sin bytes, expiración del emitter a los 30 min, redeploy) lee cada lote pendiente en un viaje propio y borra cada entrada vencida del índice en otro.
4. **Hilos.** El pool de `@Async` es implícito y la VM tiene 2 núcleos. Las llamadas al LLM (hasta 15 s) y a OCI pasan casi todo el tiempo esperando I/O.
5. **Revisión cara.** Cada `PATCH` descarga el paquete completo de OCI (hasta 900 KB) aunque el backend lo acabe de escribir, y cada GET cuenta en la cuota mensual del bucket.

Objetivo 010: corregir 1 y 2, que el coste de 3 sea fijo, aprovechar los 2 núcleos con virtual threads sin que el SDK de OCI los bloquee, y ahorrar el GET de la revisión cuando el paquete ya está confirmado en el bucket.

## 2. Requerimientos Funcionales

- **RF-01 — Timeouts de Redis reales:** `REDIS_TIMEOUT` (por defecto `2000ms`) limita cada comando y la conexión en **todos** los perfiles (dev y prod). Un Redis caído o colgado degrada la operación en ~2 s, nunca en 60 s.
- **RF-02 — Append atómico:** dedup + registro + contador de bytes de un mensaje son un único paso atómico en Redis y un único viaje. O se aplican los tres o ninguno. El contrato de `appendToBatch` no cambia: devuelve el total de bytes del lote, o `0` si es duplicado o si Redis falla.
- **RF-03 — Descarte atómico:** borrar un lote sellado ya subido y quitarlo de `pending` ocurre en un solo paso. Nunca queda un id en `pending` que apunte a una lista borrada.
- **RF-04 — Pendientes en una lectura:** el historial lee todos los lotes sellados pendientes de una fuente en un único viaje a Redis, sin importar cuántos haya. El orden y el contenido del historial (spec 008 RF-01) no cambian.
- **RF-05 — Limpieza del índice en un paso:** las entradas vencidas del índice de paquetes (spec 008 RF-03) se borran juntas en un único comando por lectura.
- **RF-06 — Llamadas a OCI fuera de los virtual threads y con límite propio:** toda llamada al SDK de Object Storage (PUT, GET y su lectura del cuerpo, creación de PAR) se ejecuta en un pool propio de **3** hilos de plataforma: los 2 flushes de fuente a la vez dejan siempre uno libre para un `PATCH`. Quien llama (pipeline, scheduler o `PATCH`) espera sin ocupar un portador de virtual threads. El **propio cliente del SDK** limita cada llamada: conexión `OCI_CONNECT_TIMEOUT` (`5s`), lectura `OCI_READ_TIMEOUT` (`20s`) y como mucho `OCI_MAX_ATTEMPTS` intentos (`2`, backoff ≤ 2 s). Así el hilo se libera de verdad; cancelar la espera del llamador no detiene una llamada HTTP bloqueante. Por defecto, el SDK esperaba 60 s por lectura con hasta 8 intentos. Un timeout es un `BmcException` como cualquier `5xx`: el `PATCH` responde `502` y el lote del flush sigue pendiente. Reintentar es seguro porque el flush es create-only (`412`) y la revisión usa `If-Match`. Los códigos de error de OCI (`404`, `412`, `5xx`) conservan su significado actual.
- **RF-07 — Virtual threads y límite del LLM:** Tomcat, `@Async` y `@Scheduled` usan virtual threads. Como mucho `LLM_MAX_CONCURRENCY` llamadas al LLM a la vez (por defecto `3`, el valor fijo anterior; mínimo `1`, si no la app no arranca). Una llamada que espera turno no bloquea el hilo de ingesta del bot.
- **RF-08 — Copia del paquete confirmado:** Redis guarda una copia del paquete y su `etag` **solo después de que OCI lo confirma en una revisión**: GET correcto o reescritura `If-Match` aceptada. El flush **no** cachea (la mayoría de los paquetes nunca se revisan). Nunca existe en Redis un paquete que no esté en el bucket, ni uno de `1MB` o más (los dos caminos lo rechazan antes). Antes de cada reescritura se invalida la copia, así que tras cualquier fallo (`412`, error, timeout, interrupción) el caché queda vacío y nunca con la versión anterior. La copia que deja un GET solo llena un hueco vacío (`SET NX`), así una lectura lenta nunca pisa la versión más nueva que una reescritura concurrente acaba de guardar; la de un `PUT If-Match` aceptado sí sobrescribe. Un `PATCH` con la copia en Redis no hace GET a OCI; el `PUT If-Match` sigue siendo la validación. Cualquier fallo de Redis, del JSON o de la validación del dominio al leer la copia cuenta como miss y cae a OCI. TTL `PACKAGE_CACHE_TTL` (por defecto `24h`).
- **RF-09 — Lectura desactualizada:** si el cliente envía un `expectedVersion` **mayor** que la versión leída (las versiones solo suben, así que la lectura está atrasada), el backend relee el paquete de OCI ignorando la copia una sola vez antes de decidir el `409`.

## 3. RNF

- **RNF-01 — Viajes a Redis (P4):** append `1` viaje (antes `3`). Historial por reconexión `3` viajes fijos + como mucho `1` `HDEL` (antes `2 + N lotes + M vencidos`).
- **RNF-02 — Cloudflare:** el comentario `connected` se sigue enviando antes de cargar el historial y el heartbeat sigue cada 30 s. Con virtual threads, `@Scheduled(fixedRate)` ya no espera a la ejecución anterior, así que un heartbeat que llega mientras otro sigue en curso (cliente que dejó de leer) se salta en vez de apilarse. Un fallo al limpiar el índice de paquetes vencidos no descarta los paquetes vigentes ya leídos. Con viajes fijos y un timeout de 2 s, cargar el historial no se acerca al corte de 100 s aunque Redis falle.
- **RNF-03 — Hilos (2 núcleos):** las llamadas a OCI usan como mucho 3 hilos de plataforma, que pasan casi todo el tiempo esperando red. El portador de virtual threads se queda con su paralelismo por defecto (= núcleos). Las llamadas al LLM en curso están acotadas por RF-07.
- **RNF-04 — Cuota de OCI:** la primera revisión de un paquete sigue gastando `1` GET + `1` PUT; cada revisión siguiente del mismo paquete, dentro del TTL, gasta solo `1` PUT.
- **RNF-05 — Privacidad (Q3):** la copia en Redis tiene el mismo contenido que el buffer y el bucket (v1.4) y vive en la misma red interna. Los logs del caché solo llevan `batchId`/`source`; nunca el contenido, el autor ni el `etag`.
- **RNF-06 — Memoria de Redis:** solo los paquetes revisados ocupan memoria (como mucho ~900 KB cada uno) y expiran a las `PACKAGE_CACHE_TTL`. La lectura de un objeto se corta en `1MB` aunque OCI no envíe `Content-Length`.

## 4. Dominio y Glosario

- **Viaje (round trip):** una ida y vuelta entre el backend y Redis/OCI. En la VM, la latencia se paga por viaje, no por comando.
- **Script Lua:** varios comandos Redis que el servidor ejecuta como un único paso atómico.
- **Pipeline:** varios comandos enviados juntos, con todas las respuestas en un único viaje. No es atómico.
- **Virtual thread:** hilo ligero de Java 21 que se desmonta de su portador (hilo del SO) mientras espera I/O. Con 2 núcleos hay 2 portadores.
- **Pinning:** un virtual thread que no puede desmontarse (bloquea dentro de `synchronized` en JDK 21) y ocupa su portador mientras espera.
- **Copia confirmada:** paquete + `etag` que OCI devolvió en un GET de revisión o aceptó en un `PUT If-Match`. Es la única forma en que un paquete entra al caché.

## 5. Fuera de Alcance

- CDS / AppCDS en el `Dockerfile` (evaluado: solo compensa si el arranque medido se acerca a 15 s).
- Comprimir los paquetes (`Content-Encoding: gzip`): cambia el presupuesto `<1MB` (R6) y necesita su propia enmienda.
- Validar la copia con `If-None-Match` (el `304` sigue contando como petición en la cuota de OCI).
- Sellado del lote en Lua (corre una vez por hora y fuente, el ahorro no lo justifica).
- Cambiar `REPLAY_MAX`, el timeout del emitter o el heartbeat del SSE.
