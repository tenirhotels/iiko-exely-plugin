package kz.tenir.tv.core.util

import java.io.IOException
import java.util.Calendar
import java.util.TimeZone

/** Транспорт HTTP. На устройстве — HttpURLConnection, в тестах — подмена. Сетевые ошибки — IOException. */
interface HttpTransport {
    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResult

    /** POST с телом JSON (облако TENIR TV Cloud). */
    @Throws(IOException::class)
    fun post(url: String, json: String, headers: Map<String, String> = emptyMap()): HttpResult =
        throw IOException("POST не поддерживается этим транспортом")
}

class HttpResult(val code: Int, val body: String?, headers: Map<String, String> = emptyMap()) {
    private val lowerHeaders = headers.mapKeys { it.key.lowercase() }
    fun header(name: String): String? = lowerHeaders[name.lowercase()]
    val isSuccess: Boolean get() = code in 200..299
}

/** Небольшое хранилище строк по ключу (файлы приложения / SharedPreferences). */
interface TextStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun delete(key: String)
}

class MemoryTextStore : TextStore {
    private val map = HashMap<String, String>()
    override fun read(key: String): String? = synchronized(map) { map[key] }
    override fun write(key: String, value: String) { synchronized(map) { map[key] = value } }
    override fun delete(key: String) { synchronized(map) { map.remove(key) } }
}

interface Clock {
    fun nowMillis(): Long
}

object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/**
 * Местное время без смещения (так Hotel Hub отдаёт даты стоянок). java.time не используется:
 * приложение должно работать на приставках с Android 5–7.
 */
data class LocalStamp(val year: Int, val month: Int, val day: Int, val hour: Int = 0, val minute: Int = 0) :
    Comparable<LocalStamp> {

    override fun compareTo(other: LocalStamp): Int = key().compareTo(other.key())

    private fun key(): Long = (((year * 100L + month) * 100 + day) * 100 + hour) * 100 + minute

    fun atHour(h: Int): LocalStamp = if (h >= 24) copy(hour = 23, minute = 59) else copy(hour = h, minute = 0)

    companion object {
        private val ISO = Regex("""^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2}))?.*""")

        fun parse(s: String?): LocalStamp? {
            if (s.isNullOrBlank()) return null
            val m = ISO.matchEntire(s.trim()) ?: return null
            val g = m.groupValues
            return LocalStamp(
                g[1].toInt(), g[2].toInt(), g[3].toInt(),
                g[4].toIntOrNull() ?: 0, g[5].toIntOrNull() ?: 0,
            )
        }

        fun of(millis: Long, tz: TimeZone): LocalStamp {
            val c = Calendar.getInstance(tz)
            c.timeInMillis = millis
            return LocalStamp(
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE),
            )
        }
    }
}

fun String?.nullIfBlank(): String? = if (this == null || this.isBlank()) null else this.trim()
