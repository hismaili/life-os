# syntax=docker/dockerfile:1

# --- Build stage -------------------------------------------------------
# Compiles with the Maven wrapper so the image always uses the version
# pinned in backend/.mvn/wrapper, matching local `./mvnw` builds.
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline

COPY src/ src/
RUN ./mvnw -B clean package -DskipTests

# --- Runtime stage -------------------------------------------------------
# Tests run in CI (mvn verify), not at image build time, so the runtime
# stage only needs a JRE, not a full JDK.
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

RUN groupadd --system lifeos && useradd --system --gid lifeos --no-create-home lifeos
COPY --from=build /workspace/target/lifeos-backend-*.jar app.jar
RUN chown lifeos:lifeos app.jar
USER lifeos

# The image runs as a headless REST/CLI-provisioning service, not an
# interactive shell prompt.
ENV SPRING_SHELL_INTERACTIVE_ENABLED=false
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
