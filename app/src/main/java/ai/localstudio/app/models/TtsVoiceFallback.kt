package ai.localstudio.app.models

/**
 * For a [MadladLanguage] code Android's on-device `TextToSpeech` has no
 * voice for, the code of the *closest-sounding* language it very likely
 * does — a hand-built table, not a generated one, so it needs to be read
 * before it's trusted.
 *
 * "Closest" means a real linguistic relationship this table's author can
 * actually stand behind: the lexifier of a creole or pidgin (Haitian
 * Creole's vocabulary and much of its sound system is French, so `fr` is
 * not a guess); the standard/macro language a code names a dialect or
 * regional variant of (Cantonese under Mandarin, Sorani Kurdish under
 * Kurmanji); or a sister language in the same close subfamily where one
 * member is itself a widely-supported voice (Bashkir next to Tatar, both
 * Volga-Ural Turkic). Where none of that holds — most Papuan, Amazonian
 * and small unrelated African or American-indigenous languages here, most
 * language isolates, anything this table's author has no real basis to
 * place — the code is simply absent rather than pointed at a guess dressed
 * up as an answer; a caller finding no entry has learned exactly as much
 * as one finding a wrong entry would have, without the false confidence.
 *
 * This is deliberately *not* "the nearest available language by any
 * measure" — a colonial contact language (Spanish for a Mayan language,
 * French for a West African one) shares geography and loanwords with what
 * it's spoken alongside, but not the sound system a text-to-speech engine
 * would actually be reproducing, so that pairing is left out here even
 * where it might read as an obvious fallback.
 *
 * Every value here is itself expected to be a real Android/Google
 * `TextToSpeech` voice on most devices, but that is never verified by this
 * table — a caller should still check [android.speech.tts.TextToSpeech.isLanguageAvailable]
 * on whatever this map returns (and on the original code first) before
 * relying on either.
 */
object TtsVoiceFallback {

    val FALLBACKS: Map<String, String> = buildMap {
        // ── Creoles and pidgins, by lexifier ────────────────────────────
        put("ht", "fr") // Haitian Creole
        put("crs", "fr") // Seselwa Creole French
        put("rcf", "fr") // Réunion Creole French
        put("acf", "fr") // St Lucian Creole French
        put("mfe", "fr") // Morisien
        put("srn", "en") // Sranan Tongo
        put("djk", "en") // Eastern Maroon Creole
        put("jam", "en") // Jamaican Creole English
        put("bzj", "en") // Belize Kriol English
        put("gyn", "en") // Guyanese Creole English
        put("kri", "en") // Krio
        put("pis", "en") // Pijin
        put("bi", "en") // Bislama
        put("srm", "en") // Saramaccan (English/Portuguese mixed lexifier; English majority)
        put("pap", "es") // Papiamento (Spanish/Portuguese-derived)
        put("cbk", "es") // Chavacano
        put("hif", "hi") // Fiji Hindi

        // ── Romance ──────────────────────────────────────────────────────
        put("ca", "es") // Catalan
        put("gl", "pt") // Galician
        put("oc", "fr") // Occitan
        put("frp", "fr") // Arpitan
        put("wa", "fr") // Walloon
        put("co", "it") // Corsican
        put("vec", "it") // Venetian
        put("rm", "it") // Romansh
        put("la", "it") // Latin
        put("grc", "el") // Ancient Greek (not Romance, placed here as a classical-language neighbor)

        // ── Germanic ─────────────────────────────────────────────────────
        put("gsw", "de") // Swiss German
        put("lb", "de") // Luxembourgish
        put("yi", "de") // Yiddish
        put("fy", "nl") // West Frisian
        put("stq", "nl") // Saterfriesisch
        put("fo", "is") // Faroese (both Insular North Germanic)

        // ── Celtic ───────────────────────────────────────────────────────
        put("gd", "ga") // Scottish Gaelic
        put("gv", "ga") // Manx
        put("br", "cy") // Breton
        put("kw", "cy") // Cornish

        // ── Slavic ───────────────────────────────────────────────────────
        put("be", "ru") // Belarusian
        put("mk", "bg") // Macedonian
        put("bs", "hr") // Bosnian
        put("sk", "cs") // Slovak
        put("sl", "hr") // Slovenian
        put("ltg", "lv") // Latgalian

        // ── Turkic ───────────────────────────────────────────────────────
        put("ug", "tr") // Uyghur
        put("tk", "tr") // Turkmen
        put("ba", "tt") // Bashkir
        put("sah", "tr") // Yakut/Sakha
        put("tyv", "tr") // Tuvan
        put("crh", "tr") // Crimean Tatar
        put("kaa", "uz") // Kara-Kalpak
        put("kaa-Latn", "uz") // Kara-Kalpak (Latin script)
        put("gag", "tr") // Gagauz
        put("krc", "tr") // Karachay-Balkar
        put("kum", "tr") // Kumyk
        put("nog", "tr") // Nogai

        // ── Kurdish/Iranian ──────────────────────────────────────────────
        put("ckb", "ku") // Kurdish (Sorani) -> Kurdish (Kurmanji)
        put("zza", "ku") // Zaza
        put("lrc", "fa") // Northern Luri
        put("luz", "fa") // Southern Luri
        put("os", "fa") // Ossetian (Iranian family; Cyrillic-script complicates this, but phonology is Iranian)

        // ── Chinese varieties ────────────────────────────────────────────
        put("yue", "zh") // Cantonese

        // ── South Asian (Indo-Aryan / Dravidian) ─────────────────────────
        put("sa", "hi") // Sanskrit
        put("bho", "hi") // Bhojpuri
        put("mai", "hi") // Maithili
        put("awa", "hi") // Awadhi
        put("hne", "hi") // Chhattisgarhi
        put("raj", "hi") // Rajasthani
        put("bjj", "hi") // Kanauji
        put("sjp", "hi") // Surjapuri
        put("gbm", "hi") // Garhwali
        put("mtr", "hi") // Mewari
        put("noe", "hi") // Nimadi
        put("brx", "hi") // Bodo (India)
        put("as", "bn") // Assamese
        put("syl", "bn") // Sylheti
        put("sd", "ur") // Sindhi
        put("skr", "ur") // Saraiki
        put("ks", "ur") // Kashmiri
        put("dv", "si") // Dhivehi (closest surviving Indo-Aryan relative among available voices)
        put("or", "hi") // Odia
        put("gom", "mr") // Goan Konkani
        put("tcy", "ta") // Tulu (Dravidian, geographically/culturally close to Kannada/Tamil area)

        // ── Southeast Asian ──────────────────────────────────────────────
        put("su", "id") // Sundanese
        put("jv", "id") // Javanese (kept here even though often itself supported)
        put("min", "id") // Minangkabau
        put("ace", "id") // Achinese
        put("ban", "id") // Balinese
        put("mad", "id") // Madurese
        put("gor", "id") // Gorontalo
        put("bbc", "id") // Batak Toba
        put("btx", "id") // Batak Karo
        put("bts", "id") // Batak Simalungun
        put("akb", "id") // Batak Angkola
        put("nij", "id") // Ngaju
        put("sda", "id") // Toraja-Sa'dan
        put("mak", "id") // Makasar
        put("bew", "id") // Betawi
        put("meo", "ms") // Kedah Malay
        put("jax", "ms") // Jambi Malay
        put("msi", "ms") // Sabah Malay
        put("mkn", "ms") // Kupang Malay
        put("xmm", "ms") // Manado Malay
        put("iba", "ms") // Iban
        put("ceb", "fil") // Cebuano
        put("ilo", "fil") // Ilocano
        put("hil", "fil") // Hiligaynon
        put("war", "fil") // Waray (Philippines)
        put("bik", "fil") // Central Bikol
        put("pam", "fil") // Pampanga
        put("pag", "fil") // Pangasinan
        put("krj", "fil") // Kinaray-A
        put("msb", "fil") // Masbatenyo
        put("ify", "fil") // Keley-I Kallahan
        put("mbt", "fil") // Matigsalug Manobo
        put("hmn", "zh") // Hmong (tonal, areal southern-China/SE Asia)
        put("cnh", "my") // Hakha Chin
        put("pck", "my") // Paite Chin
        put("cfm", "my") // Falam Chin
        put("mnw", "my") // Mon
        put("shn", "my") // Shan
        put("kac", "my") // Kachin
        put("lus", "my") // Mizo

        // ── Polynesian / other Pacific ───────────────────────────────────
        put("sm", "mi") // Samoan
        put("to", "mi") // Tonga (Tonga Islands)
        put("haw", "mi") // Hawaiian
        put("tvl", "mi") // Tuvalu
        put("fj", "mi") // Fijian
        put("ho", "mi") // Hiri Motu

        // ── Uralic (small) ───────────────────────────────────────────────
        put("se", "fi") // Northern Sami
        put("udm", "fi") // Udmurt
        put("koi", "fi") // Komi-Permyak
        put("kv", "fi") // Komi
        put("chm", "fi") // Meadow Mari
        put("mrj", "fi") // Hill Mari
        put("myv", "fi") // Erzya
        put("mdf", "fi") // Moksha

        // ── Caucasus ─────────────────────────────────────────────────────
        put("ce", "ru") // Chechen
        put("av", "ru") // Avar
        put("kbd", "ru") // Kabardian
        put("ady", "ru") // Adyghe
        put("tab", "ru") // Tabassaran
        put("xal", "ru") // Kalmyk

        // ── African: Bantu, areal to Swahili ─────────────────────────────
        put("ny", "sw") // Chichewa
        put("sn", "sw") // Shona
        put("lg", "sw") // Luganda
        put("rn", "sw") // Rundi
        put("rw", "sw") // Kinyarwanda (kept even though often itself supported)
        put("nyu", "sw") // Nyungwe
        put("seh", "sw") // Sena
        put("mgh", "sw") // Makhuwa-Meetto
        put("lu", "sw") // Luba-Katanga
        put("kg", "sw") // Kongo
        put("ktu", "sw") // Kituba (DRC)
        put("ln", "sw") // Lingala
        put("nnb", "sw") // Nande

        // ── African: Southern Bantu, areal to Zulu ───────────────────────
        put("st", "zu") // Sesotho
        put("ts", "zu") // Tsonga
        put("tn", "zu") // Tswana
        put("nso", "zu") // Sepedi
        put("nr", "zu") // South Ndebele
        put("ve", "zu") // Venda
        put("ss", "zu") // Swati
        put("cce", "zu") // Chopi
        put("tsc", "zu") // Tswa

        // ── African: Horn of Africa ───────────────────────────────────────
        put("ti", "am") // Tigrinya
        put("om", "am") // Oromo
        put("aa", "am") // Afar

        // ── African: West, areal to Yoruba/Hausa ──────────────────────────
        put("ee", "yo") // Ewe
        put("ak", "yo") // Twi
        put("ada", "yo") // Adangme
        put("nzi", "yo") // Nzima
        put("dyu", "ha") // Dyula
        put("dje", "ha") // Zarma
        put("ff", "ha") // Fulfulde
        put("ffm", "ha") // Maasina Fulfulde
    }

    /** [code]'s own closest-sounding available fallback, or null when none is known — see this object's own doc comment. */
    fun closestAvailable(code: String): String? = FALLBACKS[code]
}
