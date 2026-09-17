# 📋 Especificación de Requisitos de Sistema – CommunityLab

| | |
|---|---|
| **Proyecto** | CommunityLab |
| **Documento** | Requisitos Funcionales, No Funcionales y Matriz de Trazabilidad |

---

## 1. Requisitos Funcionales (RF)

### Módulo 1: Ingestión de Datos y API REST

- **RF-01 (Ingestión de Interacciones):** El sistema debe proveer un endpoint REST que reciba lotes o mensajes individuales de la comunidad en formato JSON o CSV.
- **RF-02 (Validación de Payload):** El sistema debe validar que cada objeto recibido contenga los campos mínimos obligatorios: autor, canal, tipo y texto.
- **RF-03 (Scoring de Relevancia):** El sistema debe aplicar, mediante el LLM durante el análisis, una puntuación para seleccionar las interacciones con mayor impacto comunicacional.

### Módulo 2: Procesamiento y Análisis con IA

- **RF-04 (Análisis de Sentimiento):** El sistema debe enviar el texto a un LLM con contrato compatible OpenAI (p.ej. Mistral, Z.ai) para etiquetar el sentimiento predominante (Positivo, Neutro, Frustrado/Atención requerida).
- **RF-05 (Extracción de Temas):** El sistema debe extraer etiquetas y conceptos clave representativos de cada interacción.
- **RF-06 (Bifurcación Condicional de Flujo):** El sistema debe desviar la lógica de generación según la categoría detectada (ej. testimonios a LinkedIn, dudas técnicas a FAQ).

### Módulo 3: Redacción Multicanal (Copywriting)

- **RF-07 (Redacción Multicanal):** El sistema debe redactar copys adaptados a al menos dos formatos (LinkedIn y Newsletter/FAQ) incluyendo hashtags y llamada a la acción.
- **RF-08 (Construcción de Respuesta JSON):** El modelo debe retornar los datos formateados; el backend debe consolidarlos en un objeto JSON unificado (métricas de análisis, contenidos generados y estado de almacenamiento), persistirlo en OCI Object Storage y retornarlo al cliente (Frontend).

### Módulo 4: Persistencia e Integración con OCI

- **RF-09 (Almacenamiento en OCI Object Storage):** El sistema debe subir automáticamente el paquete JSON resultante a un Bucket en OCI Object Storage (Capa Always Free).
- **RF-10 (Trazabilidad de Objeto Guardado):** El sistema debe incluir en la respuesta JSON la ruta del objeto (ruta_objeto) y el nombre del Bucket confirmado por la API de OCI.

### Módulo 5: Dashboard de Curaduría (Frontend Angular)

- **RF-11 (Visualización de Métricas):** La interfaz web debe desplegar indicadores gráficos de sentimiento general, volumen procesado y temas en tendencia.
- **RF-12 (Tarjetas de Borradores):** La interfaz web debe organizar los activos redactados por tarjetas clasificadas por red social.
- **RF-13 (Copiado Rápido):** La interfaz web debe proveer un botón de acción rápida para copiar el texto formateado al portapapeles.

---

## 2. Requisitos No Funcionales (RNF)

### 2.1 Rendimiento y Latencia

- **RNF-01 (Tiempo de Respuesta):** El tiempo de procesamiento completo de un lote (Análisis + IA + Guardado OCI) depende de la latencia del proveedor LLM; debe medirse y reportarse por lote (objetivo orientativo, no bloqueante).
- **RNF-02 (Payload Size):** El backend debe soportar solicitudes HTTP con un tamaño de cuerpo de hasta 2 MB por petición.

### 2.2 Seguridad y Red

- **RNF-03 (Cifrado en Tránsito):** Toda la comunicación entre Cliente, Netlify, Cloudflare, Spring Boot y OCI debe realizarse sobre HTTPS (TLS 1.2+).
- **RNF-04 (Protección WAF y Ocultamiento de IP):** La IP pública de la VM en OCI Compute no debe exponerse públicamente; el tráfico entrante debe pasar por el Proxy/WAF de Cloudflare.
- **RNF-05 (Gestión de Secretos):** Las claves de API (LLM_API_KEY, credenciales OCI) deben inyectarse mediante variables de entorno en la instancia Linux de OCI.

### 2.3 Cumplimiento Nube

- **RNF-06 (Capa Always Free OCI):** La infraestructura debe funcionar dentro de las cuotas gratuitas de OCI (1 VM Compute Linux y 1 Bucket de Object Storage).
- **RNF-07 (Aislamiento VCN):** El backend debe operar dentro de una Virtual Cloud Network (VCN) en OCI configurada con Internet Gateway y reglas de Security Lists acotadas.

### 2.4 Calidad de Código

- **RNF-08 (Tipado Estricto):** El backend en Spring Boot debe emplear Java Records y DTOs inmutables para validar el contrato JSON.
- **RNF-09 (Documentación OpenAPI):** El backend debe exponer la documentación interactiva de sus endpoints a través de SpringDoc OpenAPI (Swagger) en /swagger-ui.html.

---

## 3. Matriz de Trazabilidad de Requisitos

| Requisito Funcional | Componente Técnico Responsable | Criterio de Evaluación Hackathon |
|---------------------|-------------------------------|----------------------------------|
| RF-01, RF-02 | IngestionController.java (Spring Boot) | Ingestión funcional de interacciones en JSON |
| RF-03, RF-04, RF-05 | LlmAnalysisService.java (Spring AI) | Análisis de sentimiento y extracción de temas |
| RF-06, RF-07, RF-08 | AssetGeneratorService.java (Spring AI) | Generación automatizada de 2+ formatos de activos |
| RF-09, RF-10 | OciStorageService.java (OCI Java SDK) | Persistencia obligatoria en OCI Object Storage |
| RF-11, RF-12, RF-13 | DashboardComponent.ts (Angular + Netlify) | Interfaz visual de curaduría y métricas |
