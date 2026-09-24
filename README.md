# CommunityLab – Motor Inteligente de Transformación y Distribución para Comunidades Digitales

> Proyecto G-10 · Equipo 3 — No Country Simulation.
> **Sector:** MarTech / Gestión de Comunidades / Content Marketing.

## 📋 Descripción

**CommunityLab** es un motor inteligente que transforma el conocimiento disperso en comunidades digitales en contenido listo para distribuir en redes sociales.

## ❓ Conceptos y Problemas

Las comunidades digitales (Discord, Slack, foros) generan a diario decenas de dudas valiosas, testimonios, logros y contrataciones. Sin embargo, esta información se pierde en el historial de chat.

Curar este contenido y redactarlo manualmente para redes sociales toma decenas de horas a los Community Managers.

## 🛠️ Tech Stack


| Capa | Tecnología | Estado |
|------|------------|--------|
| Frontend | Angular | Definido |
| Backend | Java + Spring Boot | Definido |
| Persistencia | OCI Object Storage | Definido |
| Deploy | Por definir | ⏳ Pendiente |
| Testing | Por definir | ⏳ Pendiente |

Versiones backend definidas: Java 21, Spring Boot 4.1.1, Maven 3.9.16. Versiones frontend (Node, Angular CLI) **por definir**.

### Requisitos previos (estimados)

* Node.js LTS + npm -> *Por definir*
* Angular CLI -> *Por definir*
* Java 21
* Spring Boot 4.1.1
* Maven 3.9.16 
* Git

## 🏗️ Arquitectura

**CommunityLab** actúa como núcleo de ingesta, análisis y transformación de contenido de la comunidad.

### C1 — Contexto de Sistema

* Actores:
  * Miembro de la comunidad: genera actividad orgánica no estructurada (Discord, Telegram).
  * Community Manager / Marketing: monitorea sentimiento, revisa, edita y aprueba publicaciones.
* Sistemas externos:
  * Fuentes de Datos (Discord, Telegram, CSV/JSON): proveen conversaciones y retroalimentación.
  * Proveedor de AI (API compatible OpenAI): clasifica texto y redacta copys por canal.
  * OCI Object Storage: persiste paquetes generados y resúmenes semanales en JSON (capa Always Free OCI).
  * App Web: panel donde se concentran los textos procesados.
* Contrato de salida (JSON): `Type Sentiment [pregunta, relatos de superación, contratación laboral, quejas, tendencia], description: String, sugerence: String`.

![C1 - Context](docs/images/C1%20-%20Context.png)

### Despliegue (C4 Deploy)

![Deploy Diagram](docs/images/Deploy%20Diagram.png)

CI/CD desde GitHub hacia Netlify y OCI.

### Flujo general

`Apps / Fuentes -> Backend recibe mensajes -> llama al LLM -> procesa y etiqueta -> almacena en Object Storage -> devuelve datos / subscripción persistente por HTTPS -> Dashboard -> Community Manager aprueba -> publicación en medios`.

### Estructura repo

```text
G-10-CommunityLab-equipo-3/
├── frontend/   # App Angular (por crear)
├── backend/    # API Spring Boot (por crear)
├── docs/       # Documentación adicional
└── README.md
```

Pendiente: diseño API REST, modelo de datos.

> Autenticación (registro/login): opcional, fuera del MVP. Se evaluará al completar el MVP.

## 🚀 Instalación y uso

> **Sin comandos definidos por el momento.** Se completará cuando exista código.

Placeholder orientativo:

```bash
# Frontend (pendiente de confirmar)
# cd frontend
# npm install
# ng serve

# Backend (pendiente de confirmar)
# cd backend
# ./mvnw spring-boot:run
```

## 👥 Equipo 3 — G-10

| Rol | Integrante | GitHub |
|-----|------------|--------|
| Desarrollador Frontend y Diseñador UI | Jeferson Oyola | [@Jefer1026](https://github.com/Jefer1026) |
| Desarrollador Frontend | Alejandro Moreno | [@alejhomoreno](https://github.com/alejhomoreno) |
| Arquitecto de Software y Desarrollador Backend | Yersson David | [@YerssonDavid](https://github.com/YerssonDavid) |
| Arquitecto de BD y Desarrollador Backend | Bruno Vallejos | [@brunovallejos-itti](https://github.com/brunovallejos-itti)
| Desarrollador Backend | Nahuel Perea | [@nahuelDev19](https://github.com/nahuelDev19) |
| PO y Scrum Master | Yoant Alnor Ochoa Torre | [@yoant8a-system](https://github.com/yoant8a-system) |


## 🔀 Flujo de trabajo Git

* Rama principal: `main` (protegida)
* Rama desarrollo: `Frontend`, `Backend`
* Ramas de trabajo: `feature/nombre-tarea`, `fix/nombre-fix`, `docs/...`
* Commits convencionales: `feat:`, `fix:`, `docs:`, `refactor:`, `chore:`
* Todo cambio vía Pull Request hacia `develop` con al menos 1 review.

```bash
git checkout -b feature/mi-tarea
git add .
git commit -m "feat: descripción corta"
git push origin feature/mi-tarea
```

## 🔗 Enlaces

> **Sin enlaces por el momento.**

* Figma: _por definir_
* Gestión (GitHub Projects): [Scrum](https://github.com/No-Country-simulation/G-10-CommunityLab-equipo-3/projects)
* Deploy Frontend: _por definir_
* Deploy Backend / API docs (Swagger): _por definir_
* Discord / Comunicación: _por definir_

## 📌 Roadmap

* [x] Primer commit — README inicial
* [ ] Definir alcance y MVP
* [ ] Definir arquitectura y modelo de datos
* [x] Inicializar `frontend/` (Angular).
* [x] Inicializar `backend/` (Spring Boot)
* [ ] Definir enlaces y comandos de instalación.

## 📄 Licencia

Licensed under the Apache License, Version 2.0. Ver [LICENSE](LICENSE).
