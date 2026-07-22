package net.markdrew.biblebowl.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads an Int that may arrive as a JSON number (the current form) or a quoted string (the legacy
 * form). [SeasonDto.eventYear] was a wire string before it became an Int; season rows persisted by
 * the older server still carry `"eventYear":"2027"` in their payload JSON, and an old client may
 * still PUT it that way — this tolerates both on read. It always *writes* a number, so the wire and
 * newly-stored payloads are clean ints.
 */
object FlexibleIntSerializer : KSerializer<Int> {
    override val descriptor = PrimitiveSerialDescriptor("FlexibleInt", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
    override fun deserialize(decoder: Decoder): Int =
        if (decoder is JsonDecoder) {
            val primitive = decoder.decodeJsonElement().jsonPrimitive
            primitive.intOrNull ?: primitive.content.toInt()
        } else {
            decoder.decodeInt()
        }
}
