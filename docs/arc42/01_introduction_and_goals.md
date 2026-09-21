# 01. Introducción y Objetivos

> Fuente: `docs/sdd/constitution.md` (negocio ratificado) + specs 001–004. Estado: **vigente**.

## 1.1 Problema y requisitos clave

En comunidades tech (Discord, Telegram) se generan a diario testimonios, logros de contratación y dudas
técnicas valiosas que se pierden en el historial del chat. El equipo de Community Management las cura a mano.

`communityLab` es un motor de transformación y distribución de contenido (MVP, Hackathon Oracle Next
Education ONE — Grupo 10 - Equipo 03) que automatiza esa curaduría con IA y nube Oracle (OCI): ingiere actividad
orgánica, la analiza con LLMs y redacta activos listos para publicar.

Casos de uso principales (trazan a specs):

| Caso | Spec | Descripción |
|---|---|---|
| Ingesta normalizada | `001-ingesta-normalizada` | Bots JDA (Discord) y TelegramBots long polling (Telegram): el backend es ambos bots; ignoran bots, validan y normalizan a `Comentario` con `type=OTRO` (la IA clasifica) |
| Análisis con IA | `002-analisis-ia` | Sentimiento, temas y relevancia (0–100) vía Spring AI con salida estructurada |
| Generación multicanal | `003-generacion-multicanal` | Borradores `LINKEDIN` / `X` / `NEWSLETTER` / `FAQ` desde comentarios con relevancia ≥ 60, sin alucinar datos |
| Paquete OCI + transversal | `004-paquete-oci-health` | Persiste `PaqueteDeActivos` en OCI Object Storage; expone `/actuator/health`, Swagger y seguridad básica |

## 1.2 Objetivos de calidad (top 4)

Solo los principales. Escenarios medibles y árbol completo en §10.

| # | Atributo (ISO/IEC 25010) | Meta | Motivación |
|---|---|---|---|
| QG-1 | Rendimiento (eficiencia) | Ingesta sin LLM `p95 < 300ms`; arranque local `< 15s` | La ingesta es mensaje-por-mensaje en caliente; la demo no puede bloquearse |
| QG-2 | Mantenibilidad / Testeabilidad | Cobertura ≥ 80% en `domain`+`application`; núcleo sin dependencias Spring/JPA | Núcleo hexagonal puro que sobrevive a cambios de LLM y storage |
| QG-3 | Seguridad (básica, Fase 1) | CORS allowlist, headers, validación total del input, cero secretos en repo, sin PII en logs | API pública sin auth en MVP; el daño se acota por diseño |
| QG-4 | Operabilidad | `GET /actuator/health → UP` con `p95 < 100ms`; Swagger obligatorio por endpoint | Verificabilidad por el jurado y por OCI con costo casi nulo |

## 1.3 Stakeholders

| Rol | Expectativa respecto a la arquitectura |
|---|---|
| Equipo backend (ONE Grupo 10) | Arquitectura implementable por tasks atómicas, trazable a specs |
| Jurado Hackathon ONE | Demo verificable: `/health`, Swagger, latencias documentadas |
| Community Managers | Borradores multicanal fieles a la fuente (cero datos inventados) |
| Oracle OCI (Always Free) | Único storage persistente; objetos `< 1MB`, credenciales por entorno |