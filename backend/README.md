# communityLab — backend

## Stack (definido hasta el momento)

| Componente | Versión | Fuente |
|---|---|---|
| Java | 21 (runtime verificado 21.0.11) | `pom.xml` (`java.version`) |
| Spring Boot | 4.1.1 | `pom.xml` (parent) |
| Maven | 3.9.16 (wrapper 3.3.4) | `.mvn/wrapper/maven-wrapper.properties` |
| Dependencias | webmvc, restclient, devtools, lombok, webmvc-test, restclient-test | `pom.xml` |

## Estructura

```text
backend/    # Spring Boot 4.1.1, Java 21 (Maven)
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

> **Nota:** esto es inicialización del backend. Lo demás está en espera.
> Falta definir la arquitectura (persistencia, seguridad, perfiles,
> observabilidad, etc.) para poder abarcar el resto del stack y la
> documentación.
