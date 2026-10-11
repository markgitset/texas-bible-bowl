package net.markdrew.biblebowl.server

/**
 * Which build of the server this process is running, reported by `GET /health` alongside the
 * frontend's own stamp in the site footer. The two normally match — a production promotion
 * deploys both halves from one commit — so a mismatch means someone deployed a half directly.
 *
 * The value arrives as the `TBB_VERSION` environment variable, baked into the image by
 * `server/Dockerfile`'s build arg: the deploy paths pass `--build-arg TBB_VERSION=...`
 * (`tools/deploy-staging.sh`, and `deploy-production.yml`, which passes the `prod-YYYYMMDD-HHMM`
 * tag it is about to apply). Deliberately NOT a build-time Gradle constant: generating one would
 * put `git describe` output into the configuration cache of every `:server:test` run, so every
 * commit would force a full reconfiguration — see the note in the root `build.gradle.kts`.
 *
 * [UNKNOWN] when nothing set it: local `:server:run`, tests, and an emergency hand-rolled
 * `fly deploy` that forgot the build arg.
 */
object BuildVersion {

    const val UNKNOWN: String = "unknown"

    /** Read once at class init — the environment can't change under a running process. */
    val current: String = System.getenv("TBB_VERSION")?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN
}
