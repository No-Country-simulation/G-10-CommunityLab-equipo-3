# 🎯 Documento de Alcance del MVP – CommunityLab

| | |
|---|---|
| **Proyecto** | CommunityLab – Motor Inteligente de Transformación y Distribución para Comunidades Digitales |
| **Programa** | ONE (Oracle Next Education) & Alura – Grupo 10 |
| **Stack principal** | Java (Spring Boot), Angular, Oracle Cloud Infrastructure (OCI), Cloudflare, Vercel |

---

## 1. Contexto y Problema de Negocio

### 1.1 Sector

MarTech / Gestión de Comunidades Digitales / Content Marketing para plataformas educativas, comunidades de desarrolladores y empresas SaaS.

### 1.2 Problema Detectado

Las comunidades digitales activas (Discord, Telegram, Slack, foros) generan diariamente decenas de testimonios de contratación, logros de estudiantes y dudas técnicas enriquecedoras. Esta información de alto valor se pierde continuamente en el historial del chat debido a que la recolección, filtrado y redacción manual de contenidos consumen decenas de horas de trabajo semanal de los equipos de Community Management.

### 1.3 Propósito de la Solución

CommunityLab actúa como un motor inteligente que ingiere la actividad orgánica no estructurada de una comunidad, la analiza mediante modelos de lenguaje (LLM), clasifica su sentimiento y relevancia, redacta automáticamente activos de comunicación estructurados (LinkedIn, Newsletter, FAQ) y los almacena de forma segura en OCI Object Storage.

---

## 2. Definición del Alcance (Scope Boundary)

```text
┌───────────────────────────────────────────────────────────────────────────────────┐
│                                DENTRO DEL ALCANCE                                 │
│                                                                                   │
│  [Ingestión JSON/CSV] ──> [Análisis LLM] ──> [Copywriting] ──> [Persistencia OCI]  │
│                                                                        │          │
│                                                                        ▼          │
│                                                               [Panel Curaduría]   │
└───────────────────────────────────────────────────────────────────────────────────┘
```

### 2.1 Dentro del Alcance (In-Scope)

- **Ingestión de Datos:** Endpoint REST en Spring Boot capaz de recibir lotes de interacciones de la comunidad en formato JSON o CSV.
- **Procesamiento de Lenguaje Natural (IA):**
  - Clasificación automática de sentimiento (Positivo, Neutro, Frustrado/Atención necesaria).
  - Extracción temática y detección de entidades (ej. Contratación, LangGraph, Oracle Cloud).
  - Scoring de relevancia y potencial de engagement.
- **Redacción Multicanal (Copywriting):**
  - Redacción estructurada de publicaciones optimizadas para LinkedIn (tono motivacional/profesional).
  - Generación de contenido destacado para Newsletter Semanal y sugerencias para la sección de FAQ.
- **Persistencia Nube Obligatoria:**
  - Almacenamiento automático del paquete de activos formateado en JSON en OCI Object Storage (Capa Always Free).
- **Dashboard de Curaduría (Frontend Admin Panel):**
  - Interfaz web en Angular (alojada en Vercel) para examinar la salud de la comunidad, previsualizar los copys y copiarlos al portapapeles.
- **Perímetro de Red y Seguridad:**
  - Enrutamiento y protección WAF/Proxy con Cloudflare para enmascarar la VM de OCI Compute.

### 2.2 Fuera del Alcance (Out-of-Scope)

- **Publicación Automática Directa vía OAuth:** El MVP no se conecta mediante Tokens OAuth a las APIs públicas de LinkedIn o X (Twitter) para publicar de forma autónoma. Produce el borrador final para aprobación y copiado del usuario.
- **Moderación Automática de Usuarios:** No incluye sanciones, bloqueos ni expulsión automática de miembros en plataformas de chat externas.
- **Recursos Pagos de OCI:** Queda excluido cualquier servicio fuera de la cuota Always Free de Oracle Cloud Infrastructure.

---

## 3. Entregables Clave del Hackathon

| # | Entregable | Descripción |
|---|------------|-------------|
| 1 | Repositorio de Código | Código fuente organizado para Backend (Spring Boot) y Frontend (Angular). |
| 2 | Documentación Arquitectónica | Diagramas C4 (Contexto, Contenedores y Despliegue en la Nube). |
| 3 | Paquetes en OCI | Mínimo 3 ejecuciones guardadas exitosamente en el Bucket de OCI Object Storage. |
| 4 | Demostración Práctica | Procesamiento de lote de prueba y visualización en la interfaz de curaduría. |
