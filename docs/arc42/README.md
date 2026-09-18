# arc42 — communityLab backend

Documentación arquitectónica del backend (rama `backend`, proyecto `communityLab`).
Estándar arc42, un archivo por sección. Idioma: español (código y commits en inglés, según constitución).

## Índice y estado

| Sección | Archivo | Estado |
|---|---|---|
| 01 Introducción y Objetivos | `01_introduction_and_goals.md` | Vigente (derivado de SDD) |
| 02 Restricciones | `02_architecture_constraints.md` | Vigente (derivado de constitución v1.0-final) |
| 03 Contexto y Alcance | `03_context_and_scope.md` | Vigente |
| 04 Estrategia de Solución | `04_solution_strategy.md` | Borrador (respuestas equipo 2026-09-18; promover al formalizar §09) |
| 05 Vista de Bloques | `05_building_block_view.md` | Borrador (Nivel 1 previsto + contenedores C3 del equipo) |
| 06 Vista de Tiempo de Ejecución | `06_runtime_view.md` | Borrador (5 secuencias: 4 de specs + SSE) |
| 07 Vista de Despliegue | `07_deployment_view.md` | Borrador (diagrama del equipo; bucket/URLs TBD) |
| 08 Conceptos Transversales | `08_crosscutting_concepts.md` | Borrador (SSE añadido; logging/config pendientes) |
| 09 Decisiones de Arquitectura | `09_architecture_decisions.md` | Placeholder + índice de ADRs |
| 10 Requisitos de Calidad | `10_quality_requirements.md` | Placeholder + 1 escenario ejemplo |
| 11 Riesgos y Deuda Técnica | `11_risks_and_technical_debt.md` | Placeholder + deuda registrada desde constitución |
| 12 Glosario | `12_glossary.md` | Vigente (desde specs 001–004) |

Estados: `Vigente` = respaldado por código o SDD ratificado. `Objetivo` = arquitectura decidida, aún no implementada
(ver `src/`: solo existe `CommunityLabApplication`). `Placeholder` = estructura + preguntas guía, sin relleno.

## Fuentes (trazabilidad)

- `docs/sdd/constitution.md` v1.0-final → §01, §02, §09, §10, §11
- `docs/sdd/specs/001-ingesta-normalizada/spec.md` → §01, §03, §06, §12
- `docs/sdd/specs/002-analisis-ia/spec.md` → §01, §06, §12
- `docs/sdd/specs/003-generacion-multicanal/spec.md` → §01, §06, §12
- `docs/sdd/specs/004-paquete-oci-health/spec.md` → §01, §03, §06, §08, §12
- `pom.xml` (Boot 4.1.1, Java 21) + `src/` → §02, §05
- `docs/img/` (C1, C3 y Deploy del equipo) → §03, §05/§06, §07

## Reglas de mantenimiento

- §01: solo top 3–5 calidad. Detalle y árbol en §10.
- §04: resumen ejecutivo. Detalle técnico en §08, razonamiento formal en §09 (ADRs).
- Glosario único (§12) compartido con SDD. No duplicar definiciones.
- Diagramas en Mermaid dentro de bloques de código Markdown.
