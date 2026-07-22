# ---------- Build stage: assemble the Ktor fat jar ----------
# JDK 25: the Gradle daemon runs on JDK 25 (gradle/gradle-daemon-jvm.properties) because
# chupacabra-core is Java 25 bytecode. Gradle 9.5 supports running on JDK 25.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
# Copy the whole repo (server depends on :core, :shared-api, :generation).
COPY . .
# installDist (not a fat jar): the application distribution keeps every dependency as its own jar,
# so each Flyway plugin's META-INF/services stays intact and migrations run. A shadow fat jar
# merges service files last-wins and silently drops flyway-core's migration resolver.
RUN ./gradlew --no-daemon :server:installDist

# ---------- Typst stage: fetch the static musl binary ----------
FROM debian:bookworm-slim AS typst
ARG TYPST_VERSION=v0.14.2
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates xz-utils \
    && curl -sL "https://github.com/typst/typst/releases/download/${TYPST_VERSION}/typst-x86_64-unknown-linux-musl.tar.xz" \
       | tar -xJ --strip-components=1 -C /usr/local/bin typst-x86_64-unknown-linux-musl/typst

# ---------- Runtime stage: JRE + typst ----------
# JDK 25 JRE: chupacabra-core (a server dependency) ships Java 25 bytecode, so the
# runtime must be >= 25.
FROM eclipse-temurin:25-jre
# No font packages needed: the typst release binary embeds its default fonts
# (Libertinus Serif, New Computer Modern) — exactly what the generators use.
COPY --from=typst /usr/local/bin/typst /usr/local/bin/typst
# The application distribution: bin/server start script + lib/*.jar (each dependency separate).
COPY --from=build /src/server/build/install/server /app

ENV PORT=8080
EXPOSE 8080
# Cloud Run / Fly set $PORT; the app reads it (Application.kt). JVM args (MaxRAMPercentage) are
# baked into the start script via applicationDefaultJvmArgs.
ENTRYPOINT ["/app/bin/server"]
