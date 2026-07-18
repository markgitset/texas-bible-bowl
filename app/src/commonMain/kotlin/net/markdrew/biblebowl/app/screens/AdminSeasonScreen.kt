package net.markdrew.biblebowl.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.SeasonDto
import net.markdrew.biblebowl.client.TbbApi
import net.markdrew.biblebowl.app.ui.LocalSeason
import net.markdrew.biblebowl.model.StandardStudySet

/**
 * Season parameters editor (docs/gui-redesign.md §5G) for SEASON_MANAGE holders. Saves are live
 * immediately on both halves: the app re-reads [LocalSeason] and the static site's params.js
 * patches its spans on the next page view. Entering a new event year starts the next season
 * (the prior year's record becomes history).
 *
 * The study material is picked from the standard 10-year rotation ([StandardStudySet]) — the
 * display name, book code, and chapter count all derive from the chosen set, which may span
 * several books (Joshua/Judges/Ruth) or partial chapters of multiple books (Life of Moses).
 */
@Composable
fun AdminSeasonScreen(api: TbbApi, onSaved: (SeasonDto) -> Unit) {
    val current = LocalSeason.current
    var draft by remember(current) { mutableStateOf(current) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        Column(
            // The fields scroll; the Save button below stays pinned and visible.
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Season settings",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "These values drive the public site and the app (dates, fees, the study material). " +
                    "Saving is live immediately. A new event year starts the next season.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Field("Event year (e.g. 2027)", draft.eventYear) { draft = draft.copy(eventYear = it) }
            Field("Event dates (e.g. April 2–4)", draft.eventDateRange) { draft = draft.copy(eventDateRange = it) }
            Field("Theme (TBD hides it)", draft.eventTheme) { draft = draft.copy(eventTheme = it) }
            StudySetDropdown(slug = draft.studySet) { set ->
                draft = draft.copy(
                    studySet = set.simpleName,
                    eventScripture = set.name,
                    bookCode = set.chapterRanges.first().start.book.name,
                    chapterCount = set.chapterCount,
                )
            }
            Field("Registration opens (yyyy-MM-dd, blank = not announced)", draft.registrationOpensOn ?: "") {
                draft = draft.copy(registrationOpensOn = it.trim().ifBlank { null })
            }
            Field("Registration closes (yyyy-MM-dd, blank = TBD)", draft.registrationClosesOn ?: "") {
                draft = draft.copy(registrationClosesOn = it.trim().ifBlank { null })
            }
            Field("Grade cutoff (yyyy-MM-dd, blank = Sept 1 before the event)", draft.gradeCutoffDate ?: "") {
                draft = draft.copy(gradeCutoffDate = it.trim().ifBlank { null })
            }
            Field("Scholarship deadline", draft.scholarshipDeadline) { draft = draft.copy(scholarshipDeadline = it) }
            DollarField("Fee — contestant, dollars (blank = TBD)", draft.priceContestantCents) {
                draft = draft.copy(priceContestantCents = it)
            }
            DollarField("Fee — volunteer/adult, dollars", draft.priceVolunteerCents) {
                draft = draft.copy(priceVolunteerCents = it)
            }
            DollarField("Fee — child ages 3–8, dollars", draft.priceChildCents) {
                draft = draft.copy(priceChildCents = it)
            }
            DollarField("Fee — extra t-shirt, dollars", draft.priceTshirtCents) {
                draft = draft.copy(priceTshirtCents = it)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = draft.feesTentative, onCheckedChange = { draft = draft.copy(feesTentative = it) })
                Text("Fees are tentative (subject to change)", Modifier.padding(start = 8.dp))
            }
            Field("Prior-year scholarship total", draft.scholarshipAmount) { draft = draft.copy(scholarshipAmount = it) }
            Field("TBB scholarship", draft.tbbScholarshipAmount) { draft = draft.copy(tbbScholarshipAmount = it) }
            Field("Mary Orbison scholarship", draft.maryOrbisonAmount) { draft = draft.copy(maryOrbisonAmount = it) }
            Field("Paul Hendrickson scholarship", draft.paulHendricksonAmount) {
                draft = draft.copy(paulHendricksonAmount = it)
            }
        }

        Button(
            onClick = {
                busy = true; message = null
                scope.launch {
                    try {
                        val saved = api.updateSeason(draft)
                        onSaved(saved)
                        isError = false
                        message = "Saved — live on the site and app."
                    } catch (e: Throwable) {
                        isError = true
                        message = "Save failed: ${e.message}"
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy && draft.eventYear.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.height(18.dp)) else Text("Save season")
        }

        message?.let {
            Text(
                it,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** Dropdown over the standard 10-year rotation; shows each set's name and chapter count. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudySetDropdown(slug: String, onPick: (net.markdrew.biblebowl.model.StudySet) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val currentSet = StandardStudySet.parseOrNull(slug) ?: StandardStudySet.DEFAULT

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = "${currentSet.name} (${currentSet.chapterCount} chapters)",
            onValueChange = {},
            readOnly = true,
            label = { Text("Study material") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            StandardStudySet.entries.forEach { standard ->
                DropdownMenuItem(
                    text = { Text("${standard.set.name} (${standard.set.chapterCount} chapters)") },
                    onClick = {
                        onPick(standard.set)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Dollar-amount editor over integer cents; keeps its own text so typing "12.50" isn't reformatted mid-keystroke. */
@Composable
private fun DollarField(label: String, initialCents: Int?, onChange: (Int?) -> Unit) {
    var text by remember { mutableStateOf(centsToText(initialCents)) }
    Field(label, text) {
        text = it
        onChange(textToCents(it))
    }
}

/** Renders integer cents as a dollar amount for editing: 8500 → "85", 1250 → "12.50", null → "". */
private fun centsToText(cents: Int?): String = when {
    cents == null -> ""
    cents % 100 == 0 -> "${cents / 100}"
    else -> "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
}

/** Parses a typed dollar amount to cents; blank/unparseable/negative → null (TBD). */
private fun textToCents(text: String): Int? {
    val dollars = text.trim().removePrefix("$").toDoubleOrNull() ?: return null
    if (dollars < 0) return null
    return (dollars * 100 + 0.5).toInt()
}
