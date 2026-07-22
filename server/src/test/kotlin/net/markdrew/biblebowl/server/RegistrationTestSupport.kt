package net.markdrew.biblebowl.server

import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import net.markdrew.biblebowl.api.ContactInfoDto
import net.markdrew.biblebowl.api.Gender
import net.markdrew.biblebowl.api.ParticipantDto
import net.markdrew.biblebowl.api.RegistrationDto
import net.markdrew.biblebowl.api.RegistrationUpdateResponse
import net.markdrew.biblebowl.api.ShirtSize

/**
 * Parses a registration-mutation response, unwrapping the updated registration from the
 * [RegistrationUpdateResponse] wrapper (which also carries the returning-candidate list).
 */
internal suspend fun HttpResponse.regBody(): RegistrationDto = body<RegistrationUpdateResponse>().registration

// --- Test conveniences bridging the person/participation split -----------------------------
// A registration bucket's leaf is a ParticipantDto(person, participation) since the person-centric
// rewrite. These read-only shims map the pre-split field names the assertions use to their new home
// (identity on the person, per-season facts on the participation). The participant id — what scores
// and tester ids reference — is participation.id, the old "roster entry" id.
internal val ParticipantDto.id: String get() = participation.id
internal val ParticipantDto.name: String get() = person.name
internal val ParticipantDto.birthdate: String? get() = person.birthdate
internal val ParticipantDto.gender: Gender? get() = person.gender
internal val ParticipantDto.firstSeasonYear: String? get() = person.firstSeasonYear
/** A contestant participant always carries a claim code (the old RosterEntryDto contract). */
internal val ParticipantDto.claimCode: String get() = person.claimCode!!
internal val ParticipantDto.contact: ContactInfoDto? get() = person.contact
internal val ParticipantDto.shirtSize: ShirtSize? get() = participation.shirtSize
internal val ParticipantDto.positions: List<String> get() = participation.positions
internal val ParticipantDto.tribeLeaderWilling: Boolean get() = participation.tribeLeaderWilling
