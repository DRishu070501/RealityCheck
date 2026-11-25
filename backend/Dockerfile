# syntax=docker/dockerfile:1

# The build stage runs on the builder's own CPU (the jar runs anywhere); only the runtime stage is per platform,
# so ARM images (for example Oracle's Ampere servers) build on an x86 machine without emulation.
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
# Tests are run by `./mvnw verify` (they need Docker for PostgreSQL); the image build only packages.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
# No RUN steps here, so no emulation is needed for another platform: the process runs as a numeric non-root user.
WORKDIR /app
COPY --from=build /workspace/target/realitycheck-backend.jar app.jar
USER 10001:10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=6 \
    CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
