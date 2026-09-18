# 05. Vista de Bloques de Construcción

## Nivel 1 (previsto, según constitución R1)

```mermaid
flowchart TB
    subgraph APP [communityLab backend]
        IF[interfaces<br/>Controllers, DTOs HTTP, ExceptionHandler]
        AP[application<br/>UseCases, Ports, Commands, DTOs]
        DM[domain<br/>POJOs: Comentario, Activo, PaqueteDeActivos]
        IN[infrastructure<br/>Spring AI, OCI SDK, config, parsers]
    end
    IF --> AP --> DM
    IN -.implementa puertos de.-> AP
```

Interfaces: HTTP/JSON (vía `interfaces`), puertos `AnalyzePort`, `GeneratePort`, `ArtifactStorePort`
(vía `application`), SDKs externos (vía `infrastructure`).

## Vista de contenedores

![C3](docs/img/c3-diagram.png)

Notas de lectura: el "Frontend" es el dashboard público, único consumidor de la API en Fase 1;
"Admin Panel" es solo nombre, sin roles (CT-7). La flecha HTTPS con el proveedor de IA se lee
Backend→Proveedor (el backend invoca al LLM). La "suscripción persistente" Backend→Frontend es el
stream SSE `GET /api/v1/events` (ver §06, secuencia 5).

## Nivel 2 (pendiente)

> [!TODO] Descomponer por spec al implementar: 001 (`IngestController`, `IngestUseCase`, adaptadores por
> fuente `DiscordAdapter`/`TelegramAdapter` que normalizan el JSON de cada app a `Comentario`),
> 002 (`AnalyzeUseCase`, `SpringAiAnalyzeAdapter`, `RelevancePolicy`), 003 (`GenerateUseCase`,
> `ChannelPolicy`, `HallucinationGuard`), 004 (`PackageRunUseCase`, `OciObjectStorageAdapter`).
> Nivel 3 solo si un componente lo justifica.
