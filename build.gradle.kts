// Root build — plugins declared here (apply false) so subprojects can apply them
// from the version catalog without re-declaring versions.
plugins {
    alias(libs.plugins.git.version)
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}

// ── Build version ───────────────────────────────────────────────────────────────────────
// :writeVersionFile writes the git-derived version of this checkout to build/version.txt.
// tools/deploy-web.sh reads it and bakes it into the Hugo footer (params.siteVersion), so
// every deployed page names the build it came from.
//
// `git describe` semantics: the tag at HEAD if there is one, else "<last tag>-<n>-g<hash>",
// else a bare hash — plus a ".dirty" suffix when the tree has uncommitted changes. The
// plugin counts lightweight tags as well as annotated ones, so the historic prod-* tags
// resolve too; deploy-production.yml creates annotated ones from 2026-10 on, which is what
// plain `git describe` (no --tags) needs.
//
// $TBB_VERSION overrides it. The production promotion passes the prod-YYYYMMDD-HHMM tag name
// it is about to apply, so the deployed site shows that exact tag even though the tag itself
// is pushed only after both halves deploy successfully.
//
// Holding the closure is free; CALLING it shells out to git, and the plugin feeds that output
// into the configuration cache as an input. So the call sits inside the task's configuration
// block, which Gradle evaluates only when the task is in the graph — otherwise every commit
// would invalidate the configuration cache of every build in the repo. Don't hoist it, and
// don't set `version` from it for the same reason.
@Suppress("UNCHECKED_CAST")
val gitVersion = extra["gitVersion"] as groovy.lang.Closure<String>

val writeVersionFile by tasks.registering {
    group = "build"
    description = "Writes the git-derived build version to build/version.txt"
    val buildVersion = providers.environmentVariable("TBB_VERSION").orNull
        ?.trim()?.takeIf { it.isNotEmpty() }
        ?: gitVersion().trim()
    val outFile = layout.buildDirectory.file("version.txt")
    inputs.property("buildVersion", buildVersion)
    outputs.file(outFile)
    doLast {
        outFile.get().asFile.writeText("$buildVersion\n")
        logger.lifecycle("Build version: $buildVersion")
    }
}
