# syntax=docker/dockerfile:1

# Multi-arch base images: built on the ARM (Ampere A1) VM they resolve to linux/arm64 natively.

# ---- build: compile the fat jar and split it into layers ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY src/main src/main

# Why the cache mount: ~/.m2 survives between builds, so a rebuild only downloads what pom.xml changed.
# Why maven.test.skip: tests run before every PR (./mvnw test); the VM only packages.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -Dmaven.test.skip=true package \
    && cp target/communityLab-*.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# ---- runtime: JRE only, non-root ----
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app --no-create-home app

# Why layers in this order: dependencies change rarely, so a code change only rebuilds the last layer.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER app
EXPOSE 8080

# Why MaxRAMPercentage: the JVM sizes its heap from the container/VM memory instead of a fixed -Xmx.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
