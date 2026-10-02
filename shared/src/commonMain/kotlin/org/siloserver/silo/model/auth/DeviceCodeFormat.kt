package org.siloserver.silo.model.auth

/**
 * How device sign-in codes are shown and read. The server issues eight digits
 * (`4821-7730`); older servers issue eight letters and digits (`ABCD-EFGH`).
 * Both are shown grouped 4+4 with a space and typed without separators; the
 * server ignores spaces and dashes on lookup.
 */
object DeviceCodeFormat {
    /** Maximum characters in a typed code, separators excluded. */
    const val LENGTH = 8

    /** `4821-7730` → `4821 7730`. Anything that isn't eight characters is shown as sent, dashes as spaces. */
    fun display(userCode: String): String {
        val compact = normalize(userCode)
        return if (compact.length == LENGTH) {
            compact.substring(0, 4) + " " + compact.substring(4)
        } else {
            userCode.trim().replace('-', ' ')
        }
    }

    /** Strips spaces and dashes and uppercases, the form the server compares. */
    fun normalize(input: String): String =
        input.filter { it.isLetterOrDigit() }.uppercase()

    /** One character per word, so a screen reader reads `4 8 2 1 7 7 3 0`, not a number. */
    fun spoken(userCode: String): String = normalize(userCode).toList().joinToString(" ")

    /**
     * The typed-URL form of the verification address: `silo.example.com/activate`,
     * without the scheme (an `http://` address keeps it, since the browser
     * needs it). Falls back to the complete URI without its query.
     */
    fun activateText(verificationUri: String, verificationUriComplete: String): String {
        val raw = verificationUri.takeIf { it.isNotBlank() }
            ?: verificationUriComplete.substringBefore('?')
        val trimmed = raw.trim().trimEnd('/')
        return if (trimmed.startsWith("https://", ignoreCase = true)) trimmed.substring("https://".length) else trimmed
    }

    /** `silo.example.com` from a verification address, for "Can't reach …" copy. */
    fun host(url: String): String =
        url.trim().substringAfter("://").substringBefore('/').substringBefore('?')
}
