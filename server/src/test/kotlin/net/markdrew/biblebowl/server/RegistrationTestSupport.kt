package net.markdrew.biblebowl.server

import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import net.markdrew.biblebowl.api.RegistrationDto
import net.markdrew.biblebowl.api.RegistrationUpdateResponse

/**
 * Parses a registration-mutation response, unwrapping the updated registration from the
 * [RegistrationUpdateResponse] wrapper (which also carries the returning-candidate list).
 */
internal suspend fun HttpResponse.regBody(): RegistrationDto = body<RegistrationUpdateResponse>().registration
