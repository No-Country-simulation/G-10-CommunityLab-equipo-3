# 03. Contexto y Alcance

> Fuente: specs 001–004 + constitución R8. Estado: **vigente**.

## 3.1 Contexto de negocio (caja negra)

```mermaid
flowchart LR
    D[Comunidad Discord] -->|actividad orgánica| API
    T[Comunidad Telegram] -->|actividad orgánica| API
    subgraph communityLab [communityLab backend]
        API[API REST]
    end
    API -->|texto no estructurado| LLM[(LLM vía Spring AI<br/>OpenAI)]
    API -->|paquete JSON| OCI[(OCI Object Storage<br/>Always Free)]
    API -->|borradores| CM[Community Managers]
    CM -->|publican| LI[LinkedIn]
    CM -->|publican| X[X / Twitter]
    CM -->|publican| NL[Newsletter]
    CM -->|publican| FAQ[FAQ]
```

> **Diagrama C1 del equipo** (vista simplificada: Newsletter y FAQ se omiten en el dibujo; el alcance de 4 canales sigue intacto según R8).

![C1](../img/c1-context.png)

| Actor / sistema vecino | Interacción | Dirección |
|---|---|---|
| Comunidades Discord / Telegram | Discord entra vía bot JDA y Telegram vía bot TelegramBots long polling (el backend es ambos bots). Sin polling REST ni endpoints | Entrada |
| LLM externo (OpenAI) | Análisis (sentimiento, temas, relevancia) y redacción multicanal con salida estructurada | Bidireccional |
| OCI Object Storage | Persistencia del `PaqueteDeActivos` (`paquete-{batchId}.json`, `< 1MB`) | Salida |
| Frontend (dashboard público) | Único consumidor REST de la API en Fase 1. Recibe `201`, errores tipados (`400/413`, fallbacks `207`) y los presenta; seguridad futura en Fase >1 | Bidireccional |
| Community Managers | Consumen borradores y publican manualmente | Salida (humana) |
| Jurado / plataforma ONE | Verifican `/actuator/health` y Swagger | Lectura |

Fuera de alcance Fase 1: publicación automática en redes, webhook Telegram, auth/usuarios (constitución §5). Bots JDA + TelegramBots long polling en alcance (spec 001).

## 3.2 Contexto técnico

| Interfaz | Protocolo / formato | Notas |
|---|---|---|
| Ingesta Discord (bot JDA) | Gateway Discord, eventos `MessageReceivedEvent`, sin endpoint REST | Se ignora `author.isBot`; `type=OTRO`; canal = nombre; >2000 truncado con flag |
| Ingesta Telegram (bot TelegramBots long polling) | Telegram API, `Update`, sin endpoint REST ni URL pública | Se ignora `from.isBot`; `type=OTRO`; chat = nombre; >2000 truncado con flag |
| Análisis `POST /api/v1/analyze` | HTTPS + REST, JSON | Clasifica `OTRO→TESTIMONIO|LOGRO|DUDA|OTRO`; timeout 15 s por lote, 1 reintento, fallback sin `500` |
| Análisis `POST /api/v1/analyze` | HTTPS + REST, JSON | Timeout 15 s por lote, 1 reintento, fallback sin `500` |
| Generación `POST /api/v1/generate` | HTTPS + REST, JSON | Prompts versionados (`promptVersion` trazable) |
| Orquestación `POST /api/v1/packages:run` | HTTPS + REST, JSON | `201` ok / `207` con fallbacks parciales |
| Eventos `GET /api/v1/events` | HTTPS + SSE (`text/event-stream`) | Tipos `asset.created` / `package.completed`; reconexión por `Last-Event-ID`; mismo CORS que el resto |
| Storage | OCI SDK, objetos `application/json` | Credenciales por entorno, nunca en repo |
| Salud | `GET /actuator/health` (solo `health,info` expuestos) | Sin auth, `p95 < 100ms` |
| Docs API | Swagger UI (`springdoc-openapi`) | Obligatorio por endpoint (P3) |

Seguridad de borde Fase 1: CORS allowlist por env, headers (`nosniff`, `DENY`), CSRF deshabilitado
(API stateless sin cookies), límite 10 MB por request. Sin autenticación por decisión (CT-7).
