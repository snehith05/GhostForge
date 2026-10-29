package dev.ghostforge.core.spec

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The only sanctioned way to turn untrusted bytes into an [AppSpec].
 *
 * Nothing outside this file -- not the generator, not the transport, not the
 * runtime -- may construct an [AppSpec] from received data. Holding an
 * [Admission.Admitted] is therefore proof that a spec cleared both the
 * structural and semantic stages.
 *
 * Rejection happens in three ordered stages, cheapest first:
 *   1. size, before the parser is handed anything
 *   2. shape, by strict deserialization (unknown keys and unknown
 *      field/component types both fail here)
 *   3. meaning, by [SpecValidator]
 */
object SpecGate {

    /**
     * Strict by construction. Every relaxation below is load-bearing:
     *  - `ignoreUnknownKeys = false` closes the world: a spec carrying an
     *    unrecognised key is rejected rather than silently narrowed, so a
     *    newer-schema document cannot be partially executed by an older build.
     *  - `isLenient = false` refuses unquoted keys and other sloppy JSON.
     *  - `allowStructuredMapKeys = false` and no `coerceInputValues` keep
     *    type confusion out.
     */
    private val json: Json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        allowStructuredMapKeys = false
        coerceInputValues = false
        allowSpecialFloatingPointValues = false
        classDiscriminator = "type"
        encodeDefaults = true
        prettyPrint = false
    }

    fun admit(document: String): Admission {
        val size = document.toByteArray(Charsets.UTF_8).size
        if (size > Limits.MAX_SPEC_BYTES) {
            return reject(
                ErrorCode.MALFORMED, "$",
                "document is $size bytes, limit is ${Limits.MAX_SPEC_BYTES}",
            )
        }

        val spec = try {
            json.decodeFromString(AppSpec.serializer(), document)
        } catch (ex: SerializationException) {
            // Deliberately coarse. The parser's own message is the useful
            // signal for the M3 repair loop; classifying it further would mean
            // matching on message text, which breaks across library versions.
            return reject(ErrorCode.MALFORMED, "$", ex.message ?: "unparseable document")
        } catch (ex: IllegalArgumentException) {
            return reject(ErrorCode.MALFORMED, "$", ex.message ?: "illegal value in document")
        }

        val report = SpecValidator.validate(spec)
        return if (report.isValid) Admission.Admitted(spec) else Admission.Rejected(report)
    }

    /** Serializes an admitted spec. Not canonical yet -- see M4 for hashing. */
    fun encode(spec: AppSpec): String = json.encodeToString(AppSpec.serializer(), spec)

    private fun reject(code: ErrorCode, path: String, detail: String): Admission.Rejected =
        Admission.Rejected(ValidationReport(listOf(ValidationError(code, path, detail))))
}
