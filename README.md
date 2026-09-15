# communityLab

## Stack (definido hasta el momento)

| Componente | Versión | Fuente |
|---|---|---|
| Java | 21 (runtime verificado 21.0.11) | `backend/pom.xml` (`java.version`) |
| Spring Boot | 4.1.1 | `backend/pom.xml` (parent) |
| Maven | 3.9.16 (wrapper 3.3.4) | `backend/.mvn/wrapper/maven-wrapper.properties` |
| Dependencias | webmvc, restclient, devtools, lombok, webmvc-test, restclient-test | `backend/pom.xml` |

## Estructura

```text
communityLab/
├── backend/    # Spring Boot 4.1.1, Java 21 (Maven)
│   ├── pom.xml
│   ├── src/
│   ├── mvnw
│   └── docs/
└── README.md
```

## Backend

```bash
cd backend
./mvnw test
./mvnw spring-boot:run
```

> **Nota:** esto es inicialización del backend. Lo demás está en espera.
> Falta definir la arquitectura (persistencia, seguridad, perfiles,
> observabilidad, etc.) para poder abarcar el resto del stack y la
> documentación.
