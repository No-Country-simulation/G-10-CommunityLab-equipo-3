# communityLab — backend

## Stack (definido hasta el momento)

| Componente | Versión | Fuente |
|---|---|---|
| Java | 21 (runtime verificado 21.0.11) | `pom.xml` (`java.version`) |
| Spring Boot | 4.1.1 | `pom.xml` (parent) |
| Maven | 3.9.16 (wrapper 3.3.4) | `.mvn/wrapper/maven-wrapper.properties` |
| Dependencias | webmvc, restclient, devtools, lombok, webmvc-test, restclient-test, data-redis, docker-compose, JDA | `pom.xml` |

## Estructura

```text
.    # Spring Boot 4.1.1, Java 21 (Maven) — rama backend
├── pom.xml
├── src/
├── mvnw
├── docs/
├── README.md
├── .gitignore
└── .gitattributes
```

## Uso

```bash
./mvnw test
./mvnw spring-boot:run
```

## Redis local como buffer (Fase 1)

Redis es el buffer volátil del pipeline: agrupa los mensajes ya normalizados
en un lote abierto (`batchId` = uuid del lote) y acumula su tamaño en bytes.
Al alcanzar `REDIS_BUFFER_MAX_BYTES=921600` (900KB) el lote se cierra y va a
OCI como un solo objeto `<1MB` (spec 004; el flush vive en 004, aquí solo
append). Detalle de keys y flujo en `docs/sdd/specs/004-paquete-oci-health/`.

En local Redis corre vía `docker-compose-dev.yaml` (`redis-community-lab`,
`localhost:6379`, sin auth) y Spring Boot lo levanta solo al arrancar
(`spring-boot-docker-compose` en `runtime` + `spring.docker.compose.file`
en `application-dev.yaml`). Arranque manual si se prefiere:

```bash
docker compose -f docker-compose-dev.yaml up -d
docker exec -it redis-community-lab redis-cli PING  # -> PONG
```

Los datos del bind mount (`./redis-data/`) están en `.gitignore`: nunca se
commitean. Verificación tras un mensaje en `#Listen`:

```bash
docker exec -it redis-community-lab redis-cli GET buffer:current:id
docker exec -it redis-community-lab redis-cli SCARD buffer:current:ids
docker exec -it redis-community-lab redis-cli LLEN buffer:current:list
docker exec -it redis-community-lab redis-cli GET buffer:current:bytes
```

> **Nota:** esto es inicialización del backend. Lo demás está en espera.
> Falta definir la arquitectura (persistencia, seguridad, perfiles,
> observabilidad, etc.) para poder abarcar el resto del stack y la
> documentación.
