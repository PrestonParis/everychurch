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

# JDK 25 at runtime (the code still compiles to 21 above) for its AOT cache (JEP 483/514):
# a training run below records the classes Spring loads and links while booting, and every
# later start maps that in instead of redoing it — the bulk of each Cloud Run cold start.
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# Runs as a non-root user — the default openjdk images run as root otherwise,
# which App Runner doesn't require but is worth not doing anyway.
RUN useradd --system --create-home --shell /usr/sbin/nologin everychurch

# The AOT cache only applies to classes loaded from plain jars on a stable classpath, not
# from inside a Spring Boot fat jar — so unpack it first (app.jar + lib/).
COPY --from=build /app/target/*.jar /tmp/app.jar
RUN java -Djarmode=tools -jar /tmp/app.jar extract --destination /app --force \
    && rm /tmp/app.jar

# Training run: boot the context and exit as soon as it's refreshed, writing app.aot.
# There's no database at build time, so Flyway is switched off here; Hibernate doesn't
# connect at boot anyway (allow_jdbc_metadata_access=false in application.properties),
# and the startup runners that do query never execute with spring.context.exit=onRefresh.
RUN java -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh \
        -jar app.jar --spring.flyway.enabled=false \
    && chown -R everychurch:everychurch /app
USER everychurch

# App Runner probes whatever port the service is configured for (see
# apprunner.yaml / the service's PORT env var) — SERVER_PORT lets that stay
# configurable rather than baking 8080 in twice.
ENV SERVER_PORT=8080
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java -XX:AOTCache=app.aot -jar app.jar --server.port=${SERVER_PORT}"]
