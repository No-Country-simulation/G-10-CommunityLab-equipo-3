# Contrato de API — Kora (reto CommunityLab)

Endpoints que el frontend Angular consume. Hoy se simulan en el navegador (`MockCommunityLabApi`);
cuando el backend Spring Boot esté listo, basta con cambiar `useMocks: false` en
`src/app/app.config.ts`, y el cliente HTTP (`HttpCommunityLabApi`) usará estas rutas.

- **Base URL:** `http://localhost:8080/api/v1` (configurable en `app.config.ts`)
- **Formato:** JSON, UTF-8, fechas ISO-8601
- **CORS:** permitir `http://localhost:4200` (métodos `GET, POST, PATCH`)
- **Tipos TypeScript de referencia:** `src/app/core/api/api.models.ts`

| # | Método | Ruta | Pantalla | Obligatorio |
|---|--------|------|----------|-------------|
| 1 | POST | `/interactions/process` | Ingesta y análisis | ✅ MVP |
| 2 | GET | `/assets` | Curaduría, Resumen | ✅ |
| 3 | PATCH | `/assets/{id}` | Curaduría | ✅ |
| 4 | POST | `/assets/{id}/publish` | Curaduría | ✅ |
| 5 | GET | `/storage/objects` | OCI Object Storage, Resumen | ✅ |
| 6 | GET | `/storage/objects/{objectName}` | OCI Object Storage | ✅ |

---

## 1. `POST /interactions/process` — el corazón del MVP

Recibe mensajes, los analiza con el LLM, aplica la bifurcación condicional, genera el contenido
por canal, guarda el paquete en OCI Object Storage y devuelve todo.

**Request**

```json
{
  "interactions": [
    {
      "id": "msg-001",
      "author": "Mariana López",
      "content": "Me contrataron como Dev Jr de IA gracias a mi proyecto con LangChain y OCI. ¡Gracias comunidad!",
      "source": "Discord",
      "channel": "#logros",
      "timestamp": "2026-09-22T14:05:00Z"
    },
    {
      "id": "msg-002",
      "author": "Lucas Fernández",
      "content": "¿Cómo hago nodos de reintento en LangGraph?",
      "source": "Discord",
      "channel": "#ayuda-ia",
      "timestamp": "2026-09-22T14:12:00Z"
    }
  ],
  "formats": ["linkedin_post", "newsletter_highlight", "x_post", "faq"]
}
```

- `source`: `Discord | Slack | GitHub | Foro | Formulario`
- `formats` es opcional; si falta, se generan todos los que permita la rama.
- El frontend ya parsea JSON y CSV (con alias en español: `autor`, `mensaje`, `canal`…) y siempre envía este formato normalizado.

**Response `200`**

```json
{
  "batchId": "batch-20260922-a1b2c3",
  "processedAt": "2026-09-22T19:00:00Z",
  "model": "gemini-2.0-flash",
  "summary": {
    "totalInteractions": 2,
    "relevantInteractions": 2,
    "discardedInteractions": 0,
    "overallSentiment": "Altamente Positivo",
    "averageSentimentScore": 0.5,
    "mainTopics": ["LangChain", "OCI", "IA Generativa", "LangGraph"]
  },
  "interactions": [
    {
      "id": "msg-001",
      "author": "Mariana López",
      "content": "…",
      "source": "Discord",
      "channel": "#logros",
      "timestamp": "2026-09-22T14:05:00Z",
      "analysis": {
        "sentiment": "positivo",
        "sentimentScore": 0.95,
        "topics": ["LangChain", "OCI", "Empleabilidad"],
        "relevance": 0.97,
        "route": "success_story",
        "reason": "Logro profesional con sentimiento muy positivo."
      }
    }
  ],
  "assets": [
    {
      "id": "ast-7f3a91c2",
      "interactionId": "msg-001",
      "batchId": "batch-20260922-a1b2c3",
      "origin": {
        "author": "Mariana López",
        "source": "Discord",
        "channel": "#logros",
        "excerpt": "Me contrataron como Dev Jr de IA…",
        "sentiment": "positivo",
        "relevance": 0.97,
        "route": "success_story"
      },
      "type": "linkedin_post",
      "tone": "Inspirador",
      "title": "Celebramos a Mariana López",
      "body": "🎉 ¡Celebramos a Mariana! …",
      "hashtags": ["#Kora", "#LangChain", "#OCI"],
      "status": "in_review",
      "createdAt": "2026-09-22T19:00:00Z"
    }
  ],
  "storage": {
    "provider": "OCI Object Storage",
    "namespace": "axqlc3m1hy2k",
    "bucket": "kora-assets",
    "region": "sa-saopaulo-1",
    "objectName": "batches/2026/09/22/batch-20260922-a1b2c3.json",
    "sizeBytes": 8421,
    "etag": "4d46a5245b8d…",
    "url": "https://objectstorage.sa-saopaulo-1.oraclecloud.com/n/axqlc3m1hy2k/b/kora-assets/o/batches%2F2026%2F09%2F22%2Fbatch-20260922-a1b2c3.json",
    "storedAt": "2026-09-22T19:00:01Z"
  }
}
```

**Valores cerrados**

| Campo | Valores |
|-------|---------|
| `analysis.sentiment` | `positivo`, `neutral`, `negativo` |
| `analysis.sentimentScore` | `-1.0` … `1.0` |
| `analysis.relevance` | `0.0` … `1.0` |
| `analysis.route` | `success_story`, `testimonial`, `faq`, `alert`, `discard` |
| `asset.type` | `linkedin_post`, `x_post`, `newsletter_highlight`, `faq`, `success_story` |
| `asset.status` | `draft`, `in_review`, `approved`, `published`, `rejected` |

**Bifurcación condicional esperada** (la que muestra la UI)

| `route` | Cuándo | Contenidos a generar |
|---------|--------|----------------------|
| `success_story` | Logro profesional ("me contrataron") | `linkedin_post` (inspirador) + `newsletter_highlight` ("Logro de la Semana") + `x_post` (conciso) |
| `testimonial` | Opinión muy positiva sobre la comunidad | `linkedin_post` + `newsletter_highlight` |
| `faq` | Duda técnica reutilizable | `faq` (didáctico) + `x_post` (tip) |
| `alert` | Sentimiento negativo | Ninguno: se avisa al Community Manager |
| `discard` | Sin valor para contenido | Ninguno |

**Errores:** `400` si `interactions` viene vacío; `502` si el LLM u OCI fallan. Cuerpo:
`{ "message": "texto legible" }`. La UI muestra `message` en un toast.

---

## 2. `GET /assets`

Todos los contenidos generados, del más reciente al más antiguo. Devuelve `GeneratedAsset[]`
(mismo objeto que `assets[]` del endpoint 1).

## 3. `PATCH /assets/{id}`

Edición y cambio de estado desde la curaduría. Todos los campos son opcionales:

```json
{ "title": "…", "body": "…", "hashtags": ["#OCI"], "status": "approved" }
```

Devuelve el `GeneratedAsset` actualizado.

## 4. `POST /assets/{id}/publish`

Publicación con un clic. El backend marca `status: "published"`, completa `publishedAt` y, si
hay integración, publica en el canal (por ejemplo con un webhook de n8n). Body vacío `{}`.
Devuelve el `GeneratedAsset` actualizado.

## 5. `GET /storage/objects`

Lista de los paquetes del bucket (uno por lote), del más reciente al más antiguo:

```json
[
  {
    "objectName": "batches/2026/09/22/batch-20260922-a1b2c3.json",
    "batchId": "batch-20260922-a1b2c3",
    "sizeBytes": 8421,
    "storedAt": "2026-09-22T19:00:01Z",
    "interactions": 2,
    "assets": 5,
    "url": "https://objectstorage…"
  }
]
```

## 6. `GET /storage/objects/{objectName}`

Descarga el JSON guardado en OCI (`objectName` va con URL encoding). Devuelve el mismo
`ProcessResult` del endpoint 1.
