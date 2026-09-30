# Multi-stage build for App Runner (deployed as a container image via ECR — see
# docs/ARCHITECTURE.md "Suggested starting point"). Stage 1 builds the jar with
# the same Maven wrapper used locally all session; stage 2 runs it on a minimal
# JRE (not a full JDK) since nothing at runtime needs the compiler/build tools.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# The Maven wrapper (mvnw) self-bootstraps by downloading and untarring its own
# Maven distribution — under QEMU cross-platform emulation (building linux/amd64
# on Apple Silicon) that untar step hits "Cannot open: Function not implemented"
# on several jars, a known emulation gap. Using an image with Maven already
# installed and invoking `mvn` directly sidesteps that self-extraction entirely.
COPY pom.xml ./
RUN mvn -B dependency:go-offline

COPY src/ src/
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Runs as a non-root user — the default openjdk images run as root otherwise,
# which App Runner doesn't require but is worth not doing anyway.
RUN useradd --system --create-home --shell /usr/sbin/nologin everychurch
COPY --from=build /app/target/*.jar app.jar
RUN chown everychurch:everychurch app.jar
USER everychurch

# App Runner probes whatever port the service is configured for (see
# apprunner.yaml / the service's PORT env var) — SERVER_PORT lets that stay
# configurable rather than baking 8080 in twice.
ENV SERVER_PORT=8080
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java -jar app.jar --server.port=${SERVER_PORT}"]
