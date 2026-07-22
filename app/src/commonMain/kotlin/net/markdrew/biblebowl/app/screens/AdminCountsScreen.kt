package net.markdrew.biblebowl.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.markdrew.biblebowl.api.AgeTier
import net.markdrew.biblebowl.api.AttendeeRow
import net.markdrew.biblebowl.api.Division
import net.markdrew.biblebowl.api.Gender
import net.markdrew.biblebowl.api.RegistrationDeskResponse
import net.markdrew.biblebowl.api.RegistrationDeskRowDto
import net.markdrew.biblebowl.api.SeasonDto
import net.markdrew.biblebowl.api.deskAttendees
import net.markdrew.biblebowl.api.division
import net.markdrew.biblebowl.api.graduatingSeniors
import net.markdrew.biblebowl.api.isInexperienced
import net.markdrew.biblebowl.api.multiSite
import net.markdrew.biblebowl.api.siteFor
import net.markdrew.biblebowl.app.ui.LocalSeason
import net.markdrew.biblebowl.client.TbbApi

/**
 * Registration counts dashboard (replaces the 2026 workbook's `Counts` tab): totals and
 * per-site / per-congregation breakdowns by attendee type, gender, grade and age group, and
 * division + experience category, plus the graduating-seniors list (derived from grade 12 — no
 * stored flag). Read-only, computed client-side from the same desk payload the registration desk
 * shows, and gated the same way (event-wide REGISTRATION_MANAGE). Attendee types overlap per
 * person — a coach may also be a tester or a guest — so coach accounts are reported beside the
 * attendee total, never added to it. Compose port of the web AdminCountsScreen.
 */

/** The youth grades a tester can be in, for the by-grade columns. */
private val GRADES = 3..12

@Composable
fun AdminCountsScreen(api: TbbApi, onOpenDesk: () -> Unit) {
    val season = LocalSeason.current
    var data by remember { mutableStateOf<RegistrationDeskResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var siteFilter by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            data = api.registrationDesk()
        } catch (e: Throwable) {
            error = e.message
        }
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Registration counts", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        error?.let { Text("Could not load registration counts: $it", color = MaterialTheme.colorScheme.error) }

        val desk = data
        if (desk == null) {
            if (error == null) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (season.multiSite) SiteFilterPicker(season, siteFilter) { siteFilter = it }
            OutlinedButton(onClick = onOpenDesk) { Text("Registration desk") }
            SaveCsvButton("Counts CSV", { "tbb-counts-${desk.seasonYear}.csv" }) {
                countsCsv(season, desk, siteFilter)
            }
        }

        // Everything below respects the site filter, so picking a site gives the per-site counts.
        val rows = desk.rows.filter { row ->
            siteFilter == null || season.siteFor(row.registration?.siteId)?.id == siteFilter
        }
        val attendees = deskAttendees(season, rows)
        if (attendees.isEmpty()) {
            Text("No attendees registered yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }

        Headline(desk.seasonYear, rows, attendees)
        CongregationMatrix(season, rows, attendees)
        GenderSection(attendees)
        GradesAndAges(attendees)
        DivisionsSection(season, desk.seasonYear, rows, attendees)
        GraduatingSeniors(attendees)
    }
}

@Composable
private fun Headline(seasonYear: String, rows: List<RegistrationDeskRowDto>, attendees: List<AttendeeRow>) {
    val registered = rows.count { it.registration != null }
    val testers = attendees.count { it.tester }
    val coachAccounts = rows.filter { it.registration != null }
        .flatMap { it.coaches }.distinctBy { it.email }.size
    Text(
        "Season $seasonYear · $registered of ${rows.size} congregations registered · " +
            "${attendees.size} attendees — $testers testers, ${attendees.size - testers} guests · " +
            "$coachAccounts coach accounts",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "Attendee types overlap: a coach may also be a tester or a guest, so coach accounts are " +
            "shown beside the attendee total, not added to it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The division/tester/guest count cells shared by the congregation rows and the totals row. */
private fun countCells(attendees: List<AttendeeRow>): List<String> =
    (Division.entries.map { d -> attendees.count { it.division == d } } +
        listOf(attendees.count { it.tester }, attendees.count { !it.tester }, attendees.size))
        .map { it.toString() }

/** The workbook-style counts matrix: one row per registered congregation, totals at the bottom. */
@Composable
private fun CongregationMatrix(
    season: SeasonDto,
    rows: List<RegistrationDeskRowDto>,
    attendees: List<AttendeeRow>,
) {
    val byCongregation = attendees.groupBy { it.congregationId }
    SectionHeader("By congregation")
    ScrollTable(
        headers = listOf("Congregation") + Division.entries.map { it.displayName } +
            listOf("Testers", "Guests", "All"),
        rows = rows.filter { it.registration != null }.map { row ->
            val label = row.congregation.name +
                if (season.multiSite) " (${season.siteFor(row.registration?.siteId)?.name ?: "—"})" else ""
            label to countCells(byCongregation[row.congregation.id].orEmpty())
        } + ("All congregations" to countCells(attendees)),
        boldLastRow = true,
    )
}

@Composable
private fun GenderSection(attendees: List<AttendeeRow>) {
    SectionHeader("By gender")
    val unspecified = attendees.any { it.gender == null }
    fun cells(group: List<AttendeeRow>) = buildList {
        add(group.count { it.gender == Gender.MALE })
        add(group.count { it.gender == Gender.FEMALE })
        if (unspecified) add(group.count { it.gender == null })
        add(group.size)
    }.map { it.toString() }
    ScrollTable(
        headers = listOfNotNull("", "Male", "Female", "Unspecified".takeIf { unspecified }, "Total"),
        rows = listOf(
            "Testers" to cells(attendees.filter { it.tester }),
            "Guests" to cells(attendees.filter { !it.tester }),
            "All attendees" to cells(attendees),
        ),
        labelWidth = 110,
    )
}

@Composable
private fun GradesAndAges(attendees: List<AttendeeRow>) {
    SectionHeader("By grade & age group")
    val testers = attendees.filter { it.tester }
    ScrollTable(
        headers = listOf("") + GRADES.map { "Gr $it" } + "Adult",
        rows = listOf(
            "Testers" to (GRADES.map { g -> testers.count { it.grade == g } } +
                testers.count { it.division == Division.ADULT }).map { it.toString() },
        ),
        labelWidth = 80,
        cellWidth = 52,
    )
    val guests = attendees.filter { !it.tester }
    ScrollTable(
        headers = listOf("") + AgeTier.entries.map { it.displayName },
        rows = listOf(
            "Guests" to AgeTier.entries.map { tier -> guests.count { it.ageTier == tier }.toString() },
        ),
        labelWidth = 80,
        cellWidth = 80,
    )
}

/**
 * Testers per division split by experience bracket (item 4: individuals rank in their own
 * bracket, so each tester counts at their own division and experience), plus the season's
 * non-empty teams per bracket — the Adult division has neither teams nor an experience split.
 */
@Composable
private fun DivisionsSection(
    season: SeasonDto,
    seasonYear: String,
    rows: List<RegistrationDeskRowDto>,
    attendees: List<AttendeeRow>,
) {
    SectionHeader("By division & experience")
    val testers = attendees.filter { it.tester }
    ScrollTable(
        headers = listOf("", "Exp", "Inexp", "Testers"),
        rows = Division.entries.filter { it != Division.ADULT }.map { d ->
            val inDivision = testers.filter { it.division == d }
            d.displayName to listOf(
                inDivision.count { !it.inexperienced }.toString(),
                inDivision.count { it.inexperienced }.toString(),
                inDivision.size.toString(),
            )
        } + ("Adult" to listOf("—", "—", testers.count { it.division == Division.ADULT }.toString())),
        labelWidth = 110,
    )

    val teams = rows.mapNotNull { it.registration }.flatMap { it.teams }.filter { it.members.isNotEmpty() }
    if (teams.isEmpty()) return
    Text("Teams", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    ScrollTable(
        headers = listOf("", "Exp", "Inexp", "Teams"),
        rows = listOf(Division.JUNIOR, Division.SENIOR).map { d ->
            val inDivision = teams.filter { it.division(season) == d }
            d.displayName to listOf(
                inDivision.count { !it.isInexperienced(seasonYear) }.toString(),
                inDivision.count { it.isInexperienced(seasonYear) }.toString(),
                inDivision.size.toString(),
            )
        },
        labelWidth = 110,
    )
}

@Composable
private fun GraduatingSeniors(attendees: List<AttendeeRow>) {
    val seniors = attendees.graduatingSeniors().sortedBy { it.name.lowercase() }
    SectionHeader("Graduating seniors (${seniors.size})")
    Text(
        "Grade-12 contestants this season, derived from birthdates.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (seniors.isEmpty()) {
        Text("None yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    seniors.forEach {
        Text("${it.name} — ${it.congregationName}", style = MaterialTheme.typography.bodySmall)
    }
}

/** The by-congregation matrix (respecting the site filter), totals row last. */
private fun countsCsv(season: SeasonDto, desk: RegistrationDeskResponse, siteFilter: String?): String {
    val multiSite = season.multiSite
    val rows = desk.rows.filter { row ->
        siteFilter == null || season.siteFor(row.registration?.siteId)?.id == siteFilter
    }
    val attendees = deskAttendees(season, rows)
    val byCongregation = attendees.groupBy { it.congregationId }
    val header = listOfNotNull("Congregation", "Site".takeIf { multiSite }) +
        Division.entries.map { it.displayName } + listOf("Testers", "Guests", "Attendees")
    val body = rows.filter { it.registration != null }.map { row ->
        listOfNotNull(
            row.congregation.name,
            (season.siteFor(row.registration?.siteId)?.name ?: "").takeIf { multiSite },
        ) + countCells(byCongregation[row.congregation.id].orEmpty())
    }
    val total = listOfNotNull("TOTAL", "".takeIf { multiSite }) + countCells(attendees)
    return csvText(listOf(header) + body + listOf(total))
}
