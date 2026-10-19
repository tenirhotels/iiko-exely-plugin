package kz.tenir.tv.core.weather

import kz.tenir.tv.core.content.WeatherLocation
import kz.tenir.tv.core.util.Clock
import kz.tenir.tv.core.util.HttpTransport
import kz.tenir.tv.core.util.TextStore
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

enum class WeatherCondition { CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, STORM;

    companion object {
        /** Коды WMO, которые отдаёт Open-Meteo. */
        fun fromWmo(code: Int): WeatherCondition = when (code) {
            0, 1 -> CLEAR
            2 -> PARTLY_CLOUDY
            3 -> CLOUDY
            45, 48 -> FOG
            51, 53, 55, 56, 57 -> DRIZZLE
            61, 63, 65, 66, 67, 80, 81, 82 -> RAIN
            71, 73, 75, 77, 85, 86 -> SNOW
            95, 96, 99 -> STORM
            else -> CLOUDY
        }
    }
}

data class Weather(val temperatureC: Double, val condition: WeatherCondition, val isDay: Boolean, val fetchedAtMillis: Long) {
    /** «−4°» с типографским минусом. */
    fun temperatureText(): String {
        val t = Math.round(temperatureC).toInt()
        return when {
            t < 0 -> "−${-t}°"
            else -> "$t°"
        }
    }
}

/**
 * Погода из Open-Meteo (без ключа API). Последний ответ кешируется: публичные данные,
 * показываются не старше [maxAgeMillis] — дальше блок погоды скрывается.
 */
class WeatherRepository(
    private val http: HttpTransport,
    private val store: TextStore,
    private val clock: Clock,
    private val maxAgeMillis: Long = 3 * 60 * 60 * 1000L,
) {
    fun cached(): Weather? {
        val raw = store.read(KEY) ?: return null
        val w = try {
            val o = JSONObject(raw)
            Weather(
                o.getDouble("t"), WeatherCondition.valueOf(o.getString("c")), o.optBoolean("d", true), o.getLong("at"),
            )
        } catch (e: Exception) {
            return null
        }
        return if (clock.nowMillis() - w.fetchedAtMillis <= maxAgeMillis) w else null
    }

    /** Блокирующий; null при ошибке (остаётся кеш). */
    fun refresh(location: WeatherLocation): Weather? {
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code,is_day&timezone=auto",
            location.latitude, location.longitude,
        )
        val res = try {
            http.get(url)
        } catch (e: IOException) {
            return null
        }
        if (!res.isSuccess || res.body == null) return null
        val w = parseOpenMeteo(res.body, clock.nowMillis()) ?: return null
        store.write(
            KEY,
            JSONObject().put("t", w.temperatureC).put("c", w.condition.name).put("d", w.isDay)
                .put("at", w.fetchedAtMillis).toString(),
        )
        return w
    }

    companion object {
        const val KEY = "weather.json"

        fun parseOpenMeteo(body: String, now: Long): Weather? = try {
            val cur = JSONObject(body).getJSONObject("current")
            Weather(
                cur.getDouble("temperature_2m"),
                WeatherCondition.fromWmo(cur.optInt("weather_code", 3)),
                cur.optInt("is_day", 1) == 1,
                now,
            )
        } catch (e: JSONException) {
            null
        }
    }
}
