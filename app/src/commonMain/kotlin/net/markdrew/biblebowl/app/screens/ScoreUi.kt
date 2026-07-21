package net.markdrew.biblebowl.app.screens

import net.markdrew.biblebowl.api.Division
import net.markdrew.biblebowl.api.rounds
import net.markdrew.biblebowl.model.Round

/** All six rounds in test-day order (rounds 1–5, then Power). */
internal val allRounds: List<Round> = Division.ADULT.rounds

/** Short round label: "R1"–"R5", or "Power". */
internal fun roundLabel(r: Round): String = if (r == Round.POWER) "Power" else "R${r.number}"
