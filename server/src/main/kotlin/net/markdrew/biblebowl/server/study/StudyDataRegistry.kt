package net.markdrew.biblebowl.server.study

import java.util.concurrent.ConcurrentHashMap
import net.markdrew.biblebowl.model.StudySet
import net.markdrew.biblebowl.server.esv.EsvPassageService

/**
 * Hands out one memoized [StudyDataService] per study set, so the routes can serve any allowlisted
 * set — the current season's by default, another cycle's for durable off-year links — while each
 * set's indexed text is still built at most once per process (and its ESV chapters cached forever in
 * Postgres after the first build).
 *
 * Callers gate which sets may reach [forSet] (routes restrict to [net.markdrew.biblebowl.model.StandardStudySet]
 * slugs — an arbitrary set would let anonymous requests spend the ESV licence budget).
 *
 * [fixed] short-circuits every lookup to a single pre-built service: test fixtures with tiny mock
 * study sets, where the requested slug intentionally differs from the fixture's.
 */
class StudyDataRegistry(
    private val esv: EsvPassageService?,
    private val annotationCache: AnnotationCache? = null,
    private val fixed: StudyDataService? = null,
) {
    private val services = ConcurrentHashMap<String, StudyDataService>()

    /** Mirrors [StudyDataService.isConfigured]: true when ESV-backed endpoints can serve. */
    val isConfigured: Boolean get() = fixed?.isConfigured ?: (esv?.isConfigured == true)

    /** The service for [set], built on first use; null when no ESV service is available at all. */
    fun forSet(set: StudySet): StudyDataService? =
        fixed ?: esv?.let { services.getOrPut(set.simpleName) { StudyDataService(it, set, annotationCache) } }

    companion object {
        /** A registry that serves [service] for every requested set — test fixtures. */
        fun fixed(service: StudyDataService): StudyDataRegistry = StudyDataRegistry(esv = null, fixed = service)
    }
}
