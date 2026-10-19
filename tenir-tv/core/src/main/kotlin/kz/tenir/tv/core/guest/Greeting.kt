package kz.tenir.tv.core.guest

import kz.tenir.tv.core.content.NameFormat

data class Greeting(
    /** Верхняя строка: «Добрый вечер». */
    val overline: String,
    /** Крупная строка: имя гостя или «Добро пожаловать». */
    val headline: String,
    /** «Номер 305» — только при подтверждённом проживании. */
    val roomLine: String?,
    val personal: Boolean,
)

object Greetings {

    private class Phrases(
        val morning: String, val day: String, val evening: String, val night: String,
        val welcome: String, val room: String,
    )

    private val PHRASES = mapOf(
        "ru" to Phrases("Доброе утро", "Добрый день", "Добрый вечер", "Доброй ночи", "Добро пожаловать", "Номер"),
        "en" to Phrases("Good morning", "Good afternoon", "Good evening", "Good night", "Welcome", "Room"),
        "kk" to Phrases("Қайырлы таң", "Қайырлы күн", "Қайырлы кеш", "Қайырлы түн", "Қош келдіңіз", "Нөмір"),
    )

    fun build(
        lang: String,
        hourOfDay: Int,
        state: GuestState,
        format: NameFormat,
        showName: Boolean,
        showRoom: Boolean,
        welcomeOverride: String? = null,
    ): Greeting {
        val p = PHRASES[lang] ?: PHRASES.getValue("ru")
        val overline = when (hourOfDay) {
            in 5..11 -> p.morning
            in 12..17 -> p.day
            in 18..23 -> p.evening
            else -> p.night
        }
        val welcome = welcomeOverride?.takeIf { it.isNotBlank() } ?: p.welcome
        if (state !is GuestState.Present) return Greeting(overline, welcome, null, false)
        val name = if (showName) displayName(state.firstName, state.lastName, format) else null
        val room = if (showRoom && state.room.isNotBlank()) "${p.room} ${state.room}" else null
        return if (name != null) Greeting(overline, name, room, true) else Greeting(overline, welcome, room, room != null)
    }

    fun displayName(first: String?, last: String?, format: NameFormat): String? {
        val f = normalizeCase(first)
        val l = normalizeCase(last)
        return when (format) {
            NameFormat.NONE -> null
            NameFormat.FIRST_NAME -> f ?: l
            NameFormat.FULL_NAME -> listOfNotNull(f, l).joinToString(" ").ifEmpty { null }
        }
    }

    /** PMS часто хранит «IVANOV» или «иванов»: приводим к «Иванов», сохраняя дефисы и апострофы. */
    fun normalizeCase(s: String?): String? {
        val t = s?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        if (t.isEmpty()) return null
        val mixed = t.any { it.isUpperCase() } && t.any { it.isLowerCase() }
        if (mixed) return t
        val sb = StringBuilder(t.length)
        var startOfWord = true
        for (ch in t) {
            sb.append(if (startOfWord) ch.uppercaseChar() else ch.lowercaseChar())
            startOfWord = ch == ' ' || ch == '-' || ch == '\''
        }
        return sb.toString()
    }
}
