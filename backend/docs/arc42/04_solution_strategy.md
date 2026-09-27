# 04. Estrategia de la Solución

Monolito modular hexagonal: un solo desplegable Spring Boot con núcleo puro (`domain` + `application`)
e `infrastructure` tras puertos, porque el plazo de 4 semanas (CO-4) y el equipo descartan
microservicios — y porque desacoplar el negocio de la infraestructura externa da escalabilidad a largo
plazo si el proyecto crece (QG-2). Stateless sin base relacional, con OCI Object Storage como único
storage, porque el cliente no requiere persistencia ni usuarios en el MVP (aunque no se descartan para
Fase >1) y el presupuesto es $0 (CO-5, QG-1). Ingesta con ambos bots en el mismo backend (JDA con intents
`MESSAGE_CONTENT`/`GUILD_MESSAGES` y TelegramBots long polling temporal MVP sin URL pública; webhook en
Fase >1): se eligieron frente al endpoint REST y frente a RestClient sin SDK por rapidez de desarrollo
(parsing de `Update`/eventos + polling + reconexión ya resueltos); se ignoran bots, sin IDs ni allowlist
(solo importa el contenido), `>2000` truncado con flag y `type=OTRO` lo clasifica la IA en 002.
Una sola integración contra el contrato API de OpenAI,
con modelos compatibles intercambiables por env (`MODEL_MISTRAL` + `BASE_URL_MODEL_AI` requerido para dirigir la request según proveedor, ej. actual Mistral), porque un único contrato simplifica el MVP y el
riesgo de caída queda cubierto solo por fallback tipado. API consumida únicamente por el dashboard
externo vía GET (paquetes + SSE) en Fase 1 —sin endpoint de ingesta ni POSTs—, con seguridad básica
por estándar profesional aunque ningún stakeholder la exige (QG-3). Observabilidad = contrato de errores tipados hacia el frontend más `/health` (QG-4):
todo fallo se retorna clasificado, nunca como `500` opaco (taxonomía en §08). Tiempo real hacia el dashboard por SSE
(`GET /api/v1/events`): el frontend escucha `asset.created`/`package.completed` con reconexión por
`Last-Event-ID`. Buffer Redis append-only por tamaño (`900KB`, solo-Java sin Lua, degradado sin tumbar
ingesta): en local corre en Docker, en producción dentro de la misma VM del backend; el lote
(`batchId` = uuid del lote abierto) se cierra por bytes y va a OCI como 1 objeto `<1MB`.
