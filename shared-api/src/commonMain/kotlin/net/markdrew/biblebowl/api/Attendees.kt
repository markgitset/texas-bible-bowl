package net.markdrew.biblebowl.api

/**
 * One event attendee flattened from the registration desk (`GET /admin/registrations`) — the
 * shared row event-ops reports (the counts dashboard, the shirt-order report) aggregate over.
 * Every registered person appears exactly once, by the same convention as
 * [RegistrationDto.contestantCount] and [registrationFeeLines]: a congregation's desk row yields
 * its home team members, unassigned youth, members away on combo teams, and individual (adult)
 * contestants — all testers — plus its guests. Visiting members on its own teams are skipped;
 * their home congregation's registration carries them.
 *
 * Attendee types (Coach / Tester / Guest) overlap per person — an adult individual contestant may
 * also coach — so [tester] marks a role, not an exclusive category. Coach accounts aren't attendee
 * rows at all (they live in [RegistrationDeskRowDto.coaches]); tally them separately and never sum
 * them with these rows.
 */
data class AttendeeRow(
    val name: String,
    val congregationId: String,
    val congregationName: String,
    /** The registration's event-site pin ([RegistrationDto.siteId]); null until chosen. */
    val siteId: String?,
    /** True for a contestant (has a roster entry this season); false for a guest. */
    val tester: Boolean,
    val gender: Gender?,
    /** Null for an under-3 guest (no included t-shirt). */
    val shirtSize: ShirtSize?,
    /** The fee bracket by age, derived for every attendee (see [ageTierFor]). */
    val ageTier: AgeTier,
    /** School grade 3–12 for youth testers (per the season's cutoff); null for adults and guests. */
    val grade: Int? = null,
    /** The tester's own competition division; null for guests (and unparseable legacy birthdates). */
    val division: Division? = null,
    /** First-year (inexperienced) tester; always false for adults and guests. */
    val inexperienced: Boolean = false,
)

/** Flattens the desk's [rows] into one [AttendeeRow] per registered person (see [AttendeeRow]). */
fun deskAttendees(season: SeasonDto, rows: List<RegistrationDeskRowDto>): List<AttendeeRow> =
    rows.flatMap { row ->
        val reg = row.registration ?: return@flatMap emptyList<AttendeeRow>()
        fun tester(entry: RosterEntryDto) = AttendeeRow(
            name = entry.name,
            congregationId = row.congregation.id,
            congregationName = row.congregation.name,
            siteId = reg.siteId,
            tester = true,
            gender = entry.gender,
            shirtSize = entry.shirtSize,
            ageTier = season.ageTierFor(entry.birthdate),
            grade = entry.birthdate?.let { season.gradeForBirthdate(it) },
            division = entry.division(season),
            inexperienced = entry.isInexperienced(reg.seasonYear),
        )
        reg.teams.flatMap { team -> team.members.filter { it.congregationId == null } }.map(::tester) +
            reg.unassigned.map(::tester) +
            reg.awayMembers.map { tester(it.entry) } +
            reg.individuals.map(::tester) +
            reg.guests.map { guest ->
                AttendeeRow(
                    name = guest.name,
                    congregationId = row.congregation.id,
                    congregationName = row.congregation.name,
                    siteId = reg.siteId,
                    tester = false,
                    gender = guest.gender,
                    shirtSize = guest.shirtSize,
                    ageTier = season.ageTierFor(guest.birthdate),
                )
            }
    }

/** The graduating class: grade-12 testers, derived from the birthdate — no stored senior flag. */
fun List<AttendeeRow>.graduatingSeniors(): List<AttendeeRow> = filter { it.grade == 12 }
