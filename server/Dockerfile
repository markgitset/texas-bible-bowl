# ---------- Build stage: assemble the Ktor fat jar ----------
FROM eclipse-temurin:23-jdk AS build
WORKDIR /src
# Copy the whole repo (server depends on :core, :shared-api, :generation).
COPY . .
RUN ./gradlew --no-daemon :server:buildFatJar

# ---------- Typst stage: fetch the static musl binary ----------
FROM debian:bookworm-slim AS typst
ARG TYPST_VERSION=v0.14.2
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates xz-utils \
    && curl -sL "https://github.com/typst/typst/releases/download/${TYPST_VERSION}/typst-x86_64-unknown-linux-musl.tar.xz" \
       | tar -xJ --strip-components=1 -C /usr/local/bin typst-x86_64-unknown-linux-musl/typst

# ---------- Runtime stage: JRE + typst ----------
FROM eclipse-temurin:23-jre
# No font packages needed: the typst release binary embeds its default fonts
# (Libertinus Serif, New Computer Modern) — exactly what the generators use.
COPY --from=typst /usr/local/bin/typst /usr/local/bin/typst
COPY --from=build /src/server/build/libs/*-all.jar /app/server.jar

ENV PORT=8080
EXPOSE 8080
# Cloud Run sets $PORT; the app reads it (Application.kt).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/server.jar"]
