# Multi-stage build for the TFM gRPC server.
# Build context must be the backend/ directory.

FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /build
# Copy poms first for better layer caching of dependency resolution.
COPY pom.xml .
COPY tfm-model/pom.xml tfm-model/
COPY tfm-adapter-darwin/pom.xml tfm-adapter-darwin/
COPY tfm-adapter-darwin-kafka/pom.xml tfm-adapter-darwin-kafka/
COPY tfm-adapter-bods/pom.xml tfm-adapter-bods/
COPY tfm-adapter-stops/pom.xml tfm-adapter-stops/
COPY tfm-adapter-gtfs/pom.xml tfm-adapter-gtfs/
COPY tfm-storage-postgres/pom.xml tfm-storage-postgres/
COPY tfm-proto/pom.xml tfm-proto/
COPY tfm-server/pom.xml tfm-server/
# Cache mount reuses ~/.m2 across builds so a pom change doesn't re-download everything.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests dependency:go-offline
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

FROM eclipse-temurin:25-jre-jammy
WORKDIR /app
# grpc_health_probe: a tiny static client for the container healthcheck (gRPC has no HTTP health
# endpoint). Pinned; CI publishes linux/amd64 (see DEPLOY.md), so the amd64 binary is correct.
ARG GRPC_HEALTH_PROBE_VERSION=v0.4.52
ADD https://github.com/grpc-ecosystem/grpc-health-probe/releases/download/${GRPC_HEALTH_PROBE_VERSION}/grpc_health_probe-linux-amd64 /bin/grpc_health_probe
RUN chmod +x /bin/grpc_health_probe
COPY --from=build /build/tfm-server/target/tfm-server.jar app.jar
# gRPC plaintext port (Envoy terminates gRPC-Web in front of it).
EXPOSE 9090
ENV GRPC_PORT=9090
# Exit on OOM rather than limp on: after an OutOfMemoryError any thread may have died mid-update
# (e.g. a feed consumer), so a clean restart (compose restart policy) is the only safe recovery.
ENTRYPOINT ["java", "-XX:+ExitOnOutOfMemoryError", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/app.jar"]
