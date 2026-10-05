package ai.localstudio.app

object IsoScriptCodes {
    /**
     * `ru` → `rus_Cyrl`, `crs` → `crs_Latn`: the ISO 639-3 code from
     * [java.util.Locale] and the script from ICU's CLDR likely-subtags data —
     * both shipped with Android, nothing hand-typed (see [MadladLanguages]' own
     * doc comment on why fabricated codes are worse than none). Null when ICU
     * has no script for the language. Macrolanguages come out as their
     * macrolanguage code (`ara`, `zho`), not FLORES' specific variety (`arb`).
     */
    fun of(code: String): String? = runCatching {
        val likely = android.icu.util.ULocale.addLikelySubtags(android.icu.util.ULocale(code.replace('-', '_')))
        val iso3 = likely.toLocale().isO3Language
        val script = likely.script
        if (iso3.isNullOrEmpty() || script.isNullOrEmpty()) null else "${iso3}_$script"
    }.getOrNull()
}
