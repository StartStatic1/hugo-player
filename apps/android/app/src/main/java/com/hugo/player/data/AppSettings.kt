
    /** Idioma da interface: "pt" (padrão) ou "en". */
    var language: String
        get() = if (isReady) prefs.getString("language", "pt") ?: "pt" else "pt"
        set(v) { prefs.edit().putString("language", if (v == "en") "en" else "pt").apply() }
    val LANGUAGES = listOf("pt" to "Português", "en" to "English")
}
