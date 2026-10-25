<p align="center">
  <img src="public/image/logo/oso-full.png" alt="Kora" width="140" />
</p>

<h1 align="center">Kora · Frontend</h1>

<p align="center">
  <b>Tu comunidad habla. Kora publica.</b><br />
  Panel web del motor de IA que convierte las conversaciones de Discord y Telegram en contenido listo para publicar.
</p>

<p align="center">
  Angular 20 · PrimeNG 20 · Tailwind CSS 3 · TypeScript 5.9
</p>

---

> Proyecto **G-10 · Equipo 3** — Reto CommunityLab (No Country Simulation).
> Este documento cubre solo el frontend. La visión general del proyecto está en el [README principal](../README.md).

## Índice

- [Qué hace](#qué-hace)
- [Inicio rápido](#inicio-rápido)
- [Modo demo y conexión con el backend](#modo-demo-y-conexión-con-el-backend)
- [Páginas](#páginas)
- [Idiomas (ES · EN · PT)](#idiomas-es--en--pt)
- [Diseño](#diseño)
- [Estructura del proyecto](#estructura-del-proyecto)
- [Scripts](#scripts)
- [Despliegue](#despliegue)
- [Flujo de ramas](#flujo-de-ramas)

## Qué hace

Kora sigue el flujo del reto en cuatro pasos:

```text
Mensaje de la comunidad  →  Análisis con LLM  →  Orquestación  →  OCI Object Storage
 (Discord / Telegram)       sentimiento, temas,   logro → LinkedIn + Newsletter    paquete JSON
                            relevancia            duda → FAQ · negativo → alerta   en el bucket
```

El Community Manager revisa lo que genera la IA, lo edita, lo aprueba y lo publica desde el panel.

## Inicio rápido

**Requisitos:** Node.js 20.19+, 22.12+ o 24 (probado con Node 24) y npm.

```bash
cd frontend
npm install
npm start
```

Abre `http://localhost:4200`. La app arranca en **modo demo**, así que no necesitas el backend para probarla.

## Modo demo y conexión con el backend

La app habla con el backend a través de la interfaz `CommunityLabApi`, que tiene dos implementaciones:

| Modo | Implementación | Uso |
|---|---|---|
| Demo (`useMocks: true`) | `MockCommunityLabApi` | Simula el backend en el navegador y guarda los datos en `localStorage`. Ideal para presentaciones. |
| Real (`useMocks: false`) | `HttpCommunityLabApi` | Llama a la API de Spring Boot. |

Para conectar el backend real, cambia la configuración en [`src/app/app.config.ts`](src/app/app.config.ts):

```ts
provideCommunityLabApi({ baseUrl: 'http://localhost:8080/api/v1', useMocks: false }),
```

El contrato que debe cumplir el backend (endpoints, JSON de entrada y salida, valores permitidos) está en **[API_CONTRACT.md](API_CONTRACT.md)**. Resumen:

| Método | Endpoint | Página |
|---|---|---|
| `POST` | `/interactions/process` | Ingesta y análisis |
| `GET` | `/assets` | Curaduría, Resumen |
| `PATCH` | `/assets/{id}` | Curaduría |
| `POST` | `/assets/{id}/publish` | Curaduría |
| `GET` | `/storage/objects` | OCI Object Storage, Resumen |
| `GET` | `/storage/objects/{objectName}` | OCI Object Storage |

Todos los nombres y valores del contrato están en inglés (`status`, `route`, `sentiment: positive | neutral | negative`, `source: Discord | Telegram`). La app los traduce al idioma elegido.

> Para borrar los datos del demo, usa **Restablecer datos demo** en el menú de usuario (arriba a la derecha).

## Páginas

| Página | Ruta | Qué permite |
|---|---|---|
| **Resumen** | `/` | Bienvenida con la mascota, flujo "Cómo funciona", métricas, pendientes de revisión y últimos paquetes en OCI. |
| **Ingesta y análisis** | `/ingest` | Cargar mensajes (ejemplos del reto, JSON/CSV o escritos a mano), procesarlos con IA y ver el análisis, el contenido generado y el recibo de OCI. |
| **Curaduría** | `/content` | Filtrar por estado, editar, aprobar, rechazar, publicar (individual o en lote) y generar un banner PNG para redes. |
| **OCI Object Storage** | `/storage` | Ver los paquetes JSON guardados en el bucket, su tamaño y su contenido, y descargarlos. |

**Cambio de red:** la tarjeta debajo del logo alterna entre **Discord** y **Telegram**. Todas las páginas se filtran por la red activa.

**Importar mensajes:** el lector de JSON/CSV acepta campos en inglés o español (`author`/`autor`, `content`/`mensaje`, `channel`/`canal`…). Hay una plantilla CSV descargable en la página de Ingesta.

## Idiomas (ES · EN · PT)

El selector **ES · EN · PT** de la barra superior cambia el idioma al instante. La elección se guarda en el navegador. La primera vez se usa el idioma del navegador y, si no es inglés ni portugués, el español.

- Los textos están en [`src/app/core/i18n/messages.ts`](src/app/core/i18n/messages.ts), un diccionario por idioma.
- Los diccionarios `EN` y `PT` están tipados contra `ES`: **si falta una traducción, la app no compila**.
- Las fechas se formatean según el idioma.
- El contenido generado por la IA y los mensajes de la comunidad no se traducen, porque son datos.

**Para agregar un texto nuevo:**

1. Agrega la clave en `ES`, `EN` y `PT` dentro de `messages.ts`:
   ```ts
   'summary.export': 'Exportar',   // ES
   'summary.export': 'Export',     // EN
   'summary.export': 'Exportar',   // PT
   ```
2. Úsala en el HTML con el pipe `t` (importa `TranslatePipe` en el componente):
   ```html
   {{ 'summary.export' | t }}
   {{ 'ingest.samples.add' | t: { n: 3 } }}   <!-- con parámetros -->
   ```
3. En TypeScript, inyecta `I18n` y usa `this.i18n.t('summary.export')`.

## Diseño

Estilo "clay" cálido con modo claro y oscuro:

- **Paleta:** ámbar `#d97706` (principal), espresso `#1c1917` (texto) y arena `#f6f3ee` (fondo). En [`tailwind.config.js`](tailwind.config.js), las clases `blue-*` y `slate-*` están redirigidas a ámbar y a grises cálidos (`stone`), así que usarlas ya respeta la paleta.
- **Tipografía:** Plus Jakarta Sans (texto), Outfit (títulos) y JetBrains Mono (código).
- **Mascota:** un oso de anteojos 3D. El original está en `public/image/logo/osoLogo.png` y hay versiones ligeras para cada uso (`oso-head.png`, `oso-full.png`, `oso-favicon.png`, `oso-touch.png`).
- **Íconos de navegación:** [Fluent Emoji 3D](https://github.com/microsoft/fluentui-emoji) de Microsoft (licencia MIT), definidos en [`src/app/core/page-icons.ts`](src/app/core/page-icons.ts). Se cargan desde el CDN jsDelivr, así que requieren conexión a internet.
- **Estilos globales:** en [`src/styles.css`](src/styles.css) (sombras, animaciones, selector de idioma, tarjeta de bienvenida, etc.). Todas las animaciones respetan la opción del sistema de reducir movimiento.

## Estructura del proyecto

```text
frontend/
├── API_CONTRACT.md                # Contrato con el backend
├── public/image/logo/             # Logo y mascota
├── src/
│   ├── index.html
│   ├── styles.css                 # Estilos globales y tema
│   └── app/
│       ├── app.config.ts          # Providers: router, PrimeNG, API (demo/real)
│       ├── core/
│       │   ├── api/               # Modelos, API real, API demo y motor simulado
│       │   ├── i18n/              # Servicio de idioma, pipes y diccionarios
│       │   ├── workspace.store.ts # Estado compartido (contenidos, red activa, OCI)
│       │   ├── interaction-parser.ts  # Lector de JSON / CSV
│       │   ├── ui-maps.ts         # Colores e íconos por estado, tipo y red
│       │   ├── page-icons.ts      # Íconos 3D de cada página
│       │   ├── banner.ts          # Generador de banners PNG
│       │   └── theme.service.ts   # Modo claro / oscuro
│       ├── dashboard/
│       │   ├── layouts/           # Layout con sidebar + barra superior
│       │   ├── components/        # Sidebar y barra superior
│       │   └── pages/             # summary, ingest, content, storage
│       └── shared/                # Vista previa de posts, tarjeta de métricas, pipes
├── tailwind.config.js
└── angular.json
```

## Scripts

| Comando | Qué hace |
|---|---|
| `npm start` | Servidor de desarrollo en `http://localhost:4200` con recarga automática |
| `npm run build` | Build de producción en `dist/frontend/browser` |
| `npm run watch` | Build de desarrollo que se regenera al guardar |
| `npm test` | Tests unitarios con Karma + Jasmine |
| `npm run build:netlify` | Build de producción + copia de `_redirects` para Netlify |

## Despliegue

La app está preparada para **Netlify**:

1. Ejecuta `npm run build:netlify`.
2. Publica la carpeta `dist/frontend/browser`.

El archivo [`_redirects`](_redirects) hace que todas las rutas (`/ingest`, `/content`…) sirvan `index.html`, necesario para una SPA.

> El script `copy-redirects` usa el comando `copy` de Windows. En Linux o macOS, cámbialo por `cp _redirects dist/frontend/browser/_redirects`.

## Flujo de ramas

- El frontend se desarrolla en la rama **`frontend`**.
- Cada push a `frontend` dispara el workflow [`automation-update-frontend.yml`](../.github/workflows/automation-update-frontend.yml), que sincroniza la carpeta `frontend/` con `main` y abre un Pull Request.
- Usa commits convencionales: `feat:`, `fix:`, `docs:`, `refactor:`, `chore:`.
