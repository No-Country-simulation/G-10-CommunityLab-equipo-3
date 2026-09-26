# communityLab — Resumen general

Motor que convierte la actividad orgánica de comunidades tech en contenido listo para publicar. Proyecto MVP del Hackathon Oracle Next Education (ONE), Grupo 10 - Equipo 03.

> Este documento es la visión general y no técnica. El detalle técnico vive en la documentación enlazada al final.

## 1. El problema

En comunidades como Discord y Telegram se comparten a diario testimonios, logros de contratación y dudas técnicas muy valiosas. Ese contenido se pierde en el historial del chat y el equipo de Community Management tiene que curarlo a mano: copiar, resumir y adaptar cada mensaje a cada red.

## 2. La solución

communityLab automatiza esa curaduría con inteligencia artificial y nube Oracle:

1. **Recibe y ordena.** Toma un mensaje de la comunidad y lo convierte en una ficha limpia y normalizada, lista para procesar.
2. **Entiende con IA.** Analiza sentimiento, detecta temas y calcula una relevancia de 0 a 100 para priorizar lo que merece ser publicado. Solo lo relevante (60 o más) avanza a redacción.
3. **Redacta borradores multicanal.** Genera versiones adaptadas a LinkedIn, X, Newsletter y FAQ, con el tono de cada canal y sin inventar datos que no estén en el mensaje original.
4. **Empaqueta y guarda.** Agrupa los borradores por lote, los guarda en Oracle Object Storage y avisa en vivo al panel para que el equipo los revise y publique manualmente.

Principio clave: fidelidad a la fuente. Si el mensaje no dice empresa, salario o fecha, el borrador tampoco lo dice.

## 3. Alcances de la Fase 1 (MVP)

### Sí incluye

- **Ingesta vía bots Discord JDA + Telegram long polling:** el backend es ambos bots, sin endpoint; punto de entrada validado (`OTRO`, truncado con flag).
- **Análisis con IA (sentimiento, temas, relevancia, idioma):** priorizar qué se publica.
- **Generación LinkedIn, X, Newsletter, FAQ:** ahorrar el trabajo de adaptación.
- **Guardado del paquete en nube Oracle:** persistencia del resultado.
- **Orquestación del flujo completo por lote:** demo de punta a punta.
- **Salud verificable y catálogo visual de funciones:** que el jurado pueda comprobarlo en vivo.
- **Avisos en vivo al panel:** ver el avance sin recargar.

### No incluye (Fase posterior)

- **Bots como lectores del chat:** en alcance Fase 1 (el backend es ambos bots); webhook Telegram en fase posterior.
- **Publicación automática en redes:** solo borradores, publica una persona.
- **Cuentas, login, roles y permisos:** API pública con protección básica en Fase 1.
- **Base de datos de usuarios:** no se requiere para el MVP.
- **Panel persistente, facturación, multi-cliente:** alcance posterior al hackathon.
- **Imágenes, calendario editorial, pruebas A/B, traducción completa:** trabajo editorial futuro.
- **Búsqueda avanzada o moderación automática:** no es objetivo del MVP.

Límites del MVP: plazo de 4 semanas y presupuesto cero, solo niveles gratuitos. Por eso el alcance se concentra en los 4 pasos anteriores.

## 4. Métodos

### Método de trabajo: especificaciones antes que código

1. **Constitución:** reglas del proyecto que nadie puede saltarse.
2. **Especificación:** qué debe hacer cada parte, con criterios `Dado / Cuando / Entonces`.
3. **Plan y tareas:** cómo se hará y cómo se verificará, tarea por tarea.
4. **Código:** solo si hay especificación aprobada.

Sin especificación aprobada no hay código. Cada tarea indica de qué especificación deriva.

### Método de calidad

- Todo cambio lleva pruebas y revisión.
- Los errores se devuelven claros y clasificados, nunca como fallo opaco.
- Documentación interactiva obligatoria por cada función.
- Seguridad básica proporcional a una API pública sin cuentas: validación total, límites anti-abuso, cabeceras seguras y lista de orígenes permitidos.
- Privacidad académica: al modelo se envía texto y tipo de mensaje, nunca autor ni identificadores. No se guardan datos personales en registros.

## 5. Cómo se verifica la demo

Pensado para jurado y stakeholders, sin saber programar:

- La aplicación enciende y responde “en servicio”.
- El catálogo visual muestra las funciones disponibles con ejemplos.
- Se envía un mensaje de ejemplo y se ve el recorrido: ficha → análisis → borradores → paquete guardado.
- Si la IA falla en un caso, el resto sigue y se marca de forma visible, sin romper la demo.

## 6. Glosario mínimo

- **Comentario:** mensaje individual ya normalizado, unidad mínima del flujo.
- **Relevancia:** puntaje 0-100 de prioridad, publica desde 60.
- **Activo:** borrador listo para publicar para un canal.
- **Paquete:** conjunto de borradores de un mismo lote guardado en la nube.
- **Fuente:** origen del mensaje: Discord o Telegram.
- **Canal:** destino del borrador: LinkedIn, X, Newsletter o FAQ.

## 7. Estado actual y siguiente paso

Estado (2026-09-26, rama `backend`): vamos en la especificación `001-ingesta-normalizada`.

- **Hecho:** mensajes validados y normalizados (`TASK-001-01`), base del bot Discord `#Listen` con arranque fail-fast, y cada mensaje ya recibe el número del lote abierto con aviso al siguiente paso (`TASK-001-02` en revisión final).
- **En curso:** nueva `TASK-001-06`: guardar los mensajes en Redis, el contenedor temporal del lote. Sin este guardado, el número de lote sería decorativo; Redis acumula N mensajes y cuenta su tamaño para luego empaquetarlos a la nube en `004`. El hilo del chat nunca se bloquea (`<300ms` por mensaje).
- **Falta de 001:** mapeador del mensaje, acuses `✅/⚠️`, seguridad básica y verificación final.
- **Falta total:** `002` (análisis con IA), `003` (borradores multicanal), `004` (paquete OCI + avisos en vivo + salud + catálogo).


## 8. Dónde encontrar el detalle

Documentación completa y técnica del backend:

- **Arquitectura (arc42, 12 secciones):** [documentacion-arc42](https://github.com/No-Country-simulation/G-10-CommunityLab-equipo-3/tree/main/backend/docs/arc42)
- Incluye: introducción y objetivos, restricciones, contexto y alcance, estrategia, vistas de bloques / ejecución / despliegue, conceptos transversales, decisiones, calidad, riesgos y glosario.
- Ruta local: `backend/docs/arc42/`

- **Especificaciones (SDD, 001 a 004):** [documentacion-sdd](https://github.com/No-Country-simulation/G-10-CommunityLab-equipo-3/tree/main/backend/docs/sdd/specs)
- Incluye: `001-ingesta-normalizada`, `002-analisis-ia`, `003-generacion-multicanal`, `004-paquete-oci-health`.
- Ruta local: `backend/docs/sdd/specs/` + constitución en `backend/docs/sdd/constitution.md`

- **Repositorio principal:** [README-main](https://github.com/No-Country-simulation/G-10-CommunityLab-equipo-3)
- Rama `backend` para este servicio, diagramas en `backend/docs/img/`.
