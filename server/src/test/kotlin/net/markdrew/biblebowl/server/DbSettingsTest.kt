package net.markdrew.biblebowl.server

import net.markdrew.biblebowl.server.data.DbSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DbSettingsTest {

    @Test
    fun parsesANeonStyleConnectionString() {
        val s = DbSettings.fromEnv(
            rawUrl = "postgresql://tbb_user:secretpw@ep-cool-name-123.us-east-2.aws.neon.tech/biblebowl?sslmode=require",
            envUser = null, envPassword = null,
        )
        assertEquals(
            "jdbc:postgresql://ep-cool-name-123.us-east-2.aws.neon.tech/biblebowl?sslmode=require",
            s.jdbcUrl,
        )
        assertEquals("tbb_user", s.user)
        assertEquals("secretpw", s.password)
    }

    @Test
    fun preservesAnExplicitPort() {
        val s = DbSettings.fromEnv(rawUrl = "postgres://u:p@db.example.com:6543/app", envUser = null, envPassword = null)
        assertEquals("jdbc:postgresql://db.example.com:6543/app", s.jdbcUrl)
    }

    @Test
    fun envCredentialsOverrideEmbeddedOnes() {
        val s = DbSettings.fromEnv(
            rawUrl = "postgresql://embedded:embeddedpw@host/db",
            envUser = "override", envPassword = "overridepw",
        )
        assertEquals("override", s.user)
        assertEquals("overridepw", s.password)
    }

    @Test
    fun passesThroughAJdbcUrlWithSeparateCredentials() {
        val s = DbSettings.fromEnv(
            rawUrl = "jdbc:postgresql://localhost:5432/biblebowl",
            envUser = "biblebowl", envPassword = "biblebowl-dev",
        )
        assertEquals("jdbc:postgresql://localhost:5432/biblebowl", s.jdbcUrl)
        assertEquals("biblebowl", s.user)
        assertEquals("biblebowl-dev", s.password)
    }

    @Test
    fun handlesAUrlWithoutEmbeddedCredentials() {
        val s = DbSettings.fromEnv(rawUrl = "postgresql://host/db", envUser = null, envPassword = null)
        assertEquals("jdbc:postgresql://host/db", s.jdbcUrl)
        assertNull(s.user)
        assertNull(s.password)
    }
}
