# The server ships as the PREBUILT application distribution — run
#   ./gradlew :server:installDist
# before `fly deploy`. The Gradle/Kotlin build used to run inside this Dockerfile, but that
# put the whole compile (Gradle -Xmx3g + Kotlin daemon, zero build cache) on Fly's small
# shared depot builder, which OOM-killed the daemon and then wedged the builder so following
# deploys timed out ("Waiting for depot builder..." / "timed out connecting to machine").
# CI and tools/deploy-staging.sh build the dist on the runner (which has JDK 25 and the
# Gradle cache and has just compiled the server for :server:test anyway); the remote build
# is now a trivial file copy. Both deploy workflows and deploy-staging.sh run installDist
# immediately before deploying, so the dist can't be stale on those paths — only a manual
# `fly deploy` without the gradle step could ship an old dist.
#
# installDist (not a fat jar): the application distribution keeps every dependency as its own
# jar, so each Flyway plugin's META-INF/services stays intact and migrations run. A shadow fat
# jar merges service files last-wins and silently drops flyway-core's migration resolver.

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
# .dockerignore un-ignores exactly this path (build context is the repo root).
COPY server/build/install/server /app

ENV PORT=8080
EXPOSE 8080
# Cloud Run / Fly set $PORT; the app reads it (Application.kt). JVM args (MaxRAMPercentage) are
# baked into the start script via applicationDefaultJvmArgs.
ENTRYPOINT ["/app/bin/server"]
