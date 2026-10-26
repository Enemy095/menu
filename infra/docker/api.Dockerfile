# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /workspace
COPY gradlew gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY apps/api/build.gradle.kts ./apps/api/build.gradle.kts
COPY apps/api/src/main ./apps/api/src/main
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon :apps:api:bootJar

FROM eclipse-temurin:25-jre-noble AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 menu && useradd --uid 10001 --gid menu --create-home menu
WORKDIR /app
COPY --from=build --chown=menu:menu /workspace/apps/api/build/libs/menu-platform-api.jar ./app.jar
USER menu
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
