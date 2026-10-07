# Mascota 3D — 32 estados (SVG para frontend)

Reemplazo directo de la versión plana: **mismos nombres de archivo y misma carpeta** (`assets/osos/`).
Basta con sobrescribir los SVG anteriores; el código que ya los carga no cambia.

## Archivos
- `osos/NN-estado.svg`: 32 archivos, `viewBox="0 0 64 64"`, el fondo es un disco de color con las esquinas transparentes.
- Peso: 22–31 KB por archivo (unos 6 KB con gzip/brotli del servidor).
- Sin scripts, fuentes, imágenes incrustadas ni referencias externas: solo vectores y degradados.
- Los IDs internos llevan prefijo por archivo (`oso01-…`, `oso02-…`), así que se pueden incrustar varios en la misma página sin choques.
- Tamaño recomendado en pantalla: 48–128 px.

## Vocabulario para el modelo
El LLM devuelve exactamente una de estas etiquetas; el archivo es `NN-etiqueta.svg`.

| Grupo | Etiquetas |
|---|---|
| Positivo | neutral, contento, risa, celebrando, encantado, impresionado, guino, relajado |
| Calma y actividad | tranquilo, pensativo, escuchando, analizando, procesando, cargando, esperando, dormido |
| Tensión | curioso, sorprendido, confundido, preocupado, nervioso, avergonzado, molesto, frustrado |
| Negativo y vacío | enojado, furioso, decepcionado, triste, llorando, desanimado, desconectado, sin-resultados |

## Uso en Angular
```ts
export const OSOS = {
  neutral: '01-neutral', contento: '02-contento', risa: '03-risa', celebrando: '04-celebrando',
  encantado: '05-encantado', impresionado: '06-impresionado', guino: '07-guino', relajado: '08-relajado',
  tranquilo: '09-tranquilo', pensativo: '10-pensativo', escuchando: '11-escuchando', analizando: '12-analizando',
  procesando: '13-procesando', cargando: '14-cargando', esperando: '15-esperando', dormido: '16-dormido',
  curioso: '17-curioso', sorprendido: '18-sorprendido', confundido: '19-confundido', preocupado: '20-preocupado',
  nervioso: '21-nervioso', avergonzado: '22-avergonzado', molesto: '23-molesto', frustrado: '24-frustrado',
  enojado: '25-enojado', furioso: '26-furioso', decepcionado: '27-decepcionado', triste: '28-triste',
  llorando: '29-llorando', desanimado: '30-desanimado', desconectado: '31-desconectado', 'sin-resultados': '32-sin-resultados',
} as const;
export type EstadoOso = keyof typeof OSOS;

// si el modelo devuelve algo fuera de la lista, se muestra el neutral
export const osoSrc = (estado: string) =>
  `assets/osos/${OSOS[estado as EstadoOso] ?? OSOS.neutral}.svg`;
```
```html
<img [src]="osoSrc(estado)" [alt]="estado" width="64" height="64">
```
Con `<img>` el navegador cachea cada archivo. Para animar el cambio de estado basta con CSS sobre el `<img>` (`transition: transform`, `opacity`).

## Paleta
Pelaje `#1C1917` → `#4A3A40` · marcas `#FBE0BE` → `#BC7E4B` · hocico `#FFF4E4` · luz de borde `#A78BFA` · acento `#EA580C`
Fondos: éxito `#FDBA74` · actividad `#BAE6FD` · neutro `#E7E5E4` · aviso `#FCD34D` · error `#FCA5A5` · vacío `#D6D3D1`
