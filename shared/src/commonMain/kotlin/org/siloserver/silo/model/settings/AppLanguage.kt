package org.siloserver.silo.model.settings

/**
 * The app's interface language, `ui.app_language` (settings contract
 * revision 27): which languages this build ships, and how a stored or system
 * language tag maps onto one of them.
 */
object AppLanguage {
    const val KEY = SettingKeys.UI_APP_LANGUAGE

    /** Contract revision that introduced the key. */
    const val MIN_CONTRACT_REVISION = 28

    private const val SETTINGS_API_VERSION = 1

    /**
     * Languages this build has resources for, as BCP 47 tags. `es` carries
     * only the App language picker's own strings until the generated
     * translation catalog replaces this list with its output.
     */
    val SHIPPED: List<String> = listOf("en", "es")

    fun isSupported(capabilities: SettingsContractCapabilities): Boolean =
        capabilities.apiVersion == SETTINGS_API_VERSION &&
            capabilities.manifestRevision >= MIN_CONTRACT_REVISION &&
            capabilities.supportsBatchedEffective

    /**
     * The shipped language [tags] resolve to, or null. Tags are tried in
     * order, as Android tries a locale list: the first that matches wins.
     *
     * A regional tag matches its language (`es-MX` → `es`, `pt-PT` →
     * `pt-BR`). Chinese matches by script: `zh`, `zh-Hans`, `zh-CN` and
     * `zh-SG` are Simplified, while `zh-Hant`, `zh-TW`, `zh-HK` and `zh-MO`
     * match only a shipped Traditional tag. The pseudo-locales `en-XA` and
     * `ar-XB` never match.
     */
    fun match(tags: List<String>, shipped: List<String> = SHIPPED): String? =
        tags.firstNotNullOfOrNull { match(it, shipped) }

    fun match(tag: String, shipped: List<String> = SHIPPED): String? {
        val wanted = Tag.parse(tag) ?: return null
        if (wanted.region in PSEUDO_REGIONS) return null
        val candidates = if (shipped === SHIPPED) shippedTags else parsed(shipped)
        candidates.firstOrNull { (_, s) -> s == wanted }?.let { return it.first }
        return candidates.firstOrNull { (_, s) ->
            s.language == wanted.language &&
                (wanted.language != "zh" || s.chineseScript() == wanted.chineseScript())
        }?.first
    }

    private fun parsed(tags: List<String>) = tags.mapNotNull { s -> Tag.parse(s)?.let { s to it } }

    /** Parsed once: `Accept-Language` matches on every request. */
    private val shippedTags by lazy { parsed(SHIPPED) }

    private val PSEUDO_REGIONS = setOf("XA", "XB")
    private val TRADITIONAL_REGIONS = setOf("TW", "HK", "MO")

    private data class Tag(val language: String, val script: String?, val region: String?) {
        fun chineseScript(): String = script ?: if (region in TRADITIONAL_REGIONS) "Hant" else "Hans"

        companion object {
            fun parse(tag: String): Tag? {
                val parts = tag.trim().split('-', '_').filter { it.isNotEmpty() }
                val language = parts.firstOrNull()?.lowercase()?.takeIf { it.all(Char::isLetter) } ?: return null
                val rest = parts.drop(1)
                val script = rest.firstOrNull { it.length == 4 && it.all(Char::isLetter) }
                    ?.lowercase()?.replaceFirstChar { it.uppercaseChar() }
                val region = rest.firstOrNull {
                    (it.length == 2 && it.all(Char::isLetter)) || (it.length == 3 && it.all(Char::isDigit))
                }?.uppercase()
                return Tag(language, script, region)
            }
        }
    }
}

/**
 * Decides what to do with the system per-app language when the active
 * profile's `ui.app_language` is known, or when the system value may have
 * changed.
 *
 * On Android 13+ the system per-app language and the app's own choice are one
 * value, so the app keeps a small app-local [Ledger]:
 *
 * - A profile override is applied while its profile is active. The value it
 *   replaced becomes [Ledger.baseline], and the app "owns" the system value.
 * - A profile without an override gets the baseline back, if the app still
 *   owns the value; otherwise the app touches nothing.
 * - A system value that differs from [Ledger.observed] is a choice the user
 *   made in Settings → Apps → Silo → Language. It is never overwritten: when it
 *   is "follow device" or a shipped language it is written back as this
 *   device's value for the active profile, and from then on it is that
 *   profile's override like any other ([choose]).
 *
 * Tags are `LocaleList.toLanguageTags()` strings; "" means follow the device.
 */
object AppLocaleReconciler {

    /**
     * @property observed the system value the app last saw or set.
     * @property baseline the value to restore when the app owns the current
     *   one (it applied an override); null when the value is the user's own.
     * @property applying set while an apply is in flight. A ledger left like
     *   this by a crash means the system may still hold the old value, which
     *   is not a user choice.
     */
    data class Ledger(val observed: String, val baseline: String?, val applying: Boolean = false)

    /**
     * @property apply tags to set as the per-app language, or null for no call.
     *   The caller persists [ledger] with `applying = true` before the call.
     * @property ledger the ledger to persist once done.
     * @property writeBack the value to save as this device's `ui.app_language`
     *   for the active profile ("" saves null, Match device), or null for none.
     */
    data class Decision(val apply: String?, val ledger: Ledger, val writeBack: String? = null)

    /**
     * @param current the per-app value the platform reports now.
     * @param override the profile's value already matched to a shipped
     *   language ([AppLanguage.match]), or null for none.
     * @param ledger the persisted ledger, or null on first run.
     */
    fun reconcile(current: String, override: String?, ledger: Ledger?): Decision {
        val prior = ledger ?: Ledger(observed = current, baseline = null)
        if (!prior.applying && current != prior.observed) {
            // Only the first tag is saved, so only the first tag decides.
            val eligible = current.isEmpty() || AppLanguage.match(current.substringBefore(',')) != null
            // A pseudo-locale or a language this build lacks: leave it alone, unsaved.
            if (!eligible) return Decision(apply = null, ledger = Ledger(observed = current, baseline = null))
            // The platform already holds it, so there is nothing to apply.
            return choose(current, before = prior.observed, ledger = prior).copy(apply = null)
        }
        // After an interrupted apply, aim at what the ledger recorded rather
        // than at what the platform still reports.
        val held = prior.observed
        val (target, baseline) = when {
            override != null ->
                if (AppLanguage.match(held.split(',')) == override) held to prior.baseline
                else override to (prior.baseline ?: held)
            prior.baseline != null -> prior.baseline to null
            else -> held to null
        }
        return Decision(apply = target.takeIf { it != current }, ledger = Ledger(target, baseline))
    }

    /**
     * The user chose [chosen] for the active profile on this device (the
     * picker, or system settings). It becomes that profile's device value, so
     * the next profile without an override gets the earlier value back, as
     * after any override. Match device ("") follows the device and owns
     * nothing.
     *
     * @param before the system value just before the choice.
     */
    fun choose(chosen: String, before: String, ledger: Ledger?): Decision = Decision(
        apply = chosen.takeIf { it != before },
        ledger = Ledger(observed = chosen, baseline = if (chosen.isEmpty()) null else ledger?.baseline ?: before),
        writeBack = chosen.substringBefore(','),
    )
}
