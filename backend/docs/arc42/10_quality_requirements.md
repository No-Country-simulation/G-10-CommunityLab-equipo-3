# 10. Requisitos de Calidad

> Estado: **vigente**. En §01 solo el top; aquí el detalle medible trasladado de los specs 001–004.

## Árbol de calidad (esqueleto)

- Eficiencia (rendimiento, arranque)
- Mantenibilidad (testeabilidad, pureza del núcleo)
- Seguridad (básica Fase 1)
- Operabilidad (salud, documentación API)
- Fiabilidad (tolerancia a fallos del LLM)

## Escenarios de calidad

| Atributo | Fuente | Estímulo | Artefacto | Respuesta | Métrica |
|---|---|---|---|---|---|
| Eficiencia | Bots Discord/Telegram | 1 mensaje válido (no-bot; >2000 truncado) | Evento→`Comment{OTRO}` (sin LLM) | normalizado | `p95 < 300ms` local |
| Eficiencia | Arranque local | Boot con proveedor alcanzable (handshake excluido) | app | lista | `< 15s` |
| Operabilidad | Jurado/ONE | `GET /actuator/health` sin auth | health | `UP` | `p95 < 100ms` |
| Mantenibilidad | CI | suite `domain+application` | tests | verde | cobertura ≥ 80% (JaCoCo pendiente) |
| Fiabilidad | LLM caído | 1 comentario de cada 10 falla (15s + 1 reintento) | pipeline | resto procesado + fallback | sin `500`; `fallbackCount` reportado |
| Fiabilidad | Generación fallida | 002/003 sin post válido | pipeline | aborta sin SSE ni buffer | `LLM_FALLBACK`, solo `LOG + ⚠️` |
| Eficiencia | Buffer Redis | lote alcanza `bytes` | `buffer:current:*` | flush a OCI | `≥ 921600B` (900KB) |
| Fiabilidad | Paquete OCI | lote cerrado no vacío | `paquete-{batchId}.json` | persistido | `< 1MB`, `application/json` |
| Fiabilidad | Orquestación | mensaje procesado con fallbacks parciales | `POST` diferido / job | `207` con detalle | `deduped:true` por `batchId` |
| Fiabilidad | Frontend cae | reconexión SSE | `GET /api/v1/discord/messages` | reanuda sin pérdida | `Last-Event-ID` |
| Seguridad | Origin no permitido | request cross-origin | API | bloqueado por CORS | sin `Access-Control-Allow-Origin` |
