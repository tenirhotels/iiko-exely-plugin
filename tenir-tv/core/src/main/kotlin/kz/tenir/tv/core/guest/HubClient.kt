package kz.tenir.tv.core.guest

import kz.tenir.tv.core.util.HttpTransport
import kz.tenir.tv.core.util.nullIfBlank
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/** STALE — источник ответил, но подтверждённых данных о госте нет (облако не получает ответ хаба). */
enum class HubErrorKind { NETWORK, AUTH, FORBIDDEN, NOT_FOUND, RATE_LIMIT, SERVER, BAD_DATA, STALE }

class HubException(val kind: HubErrorKind, message: String) : Exception(message)

/** Строка из `GET /v1/properties/{code}/rooms` (право `occupancy`). */
data class HubRoom(
    val room: String,
    val roomId: String?,
    val status: String,
    val stayId: String?,
    val plannedIn: String?,
    val plannedOut: String?,
)

/** Строка из `GET /v1/properties/{code}/stays/{stay_id}/guests` (право `guests`). */
data class HubGuest(val role: String?, val firstName: String?, val lastName: String?)

data class HubConnection(val baseUrl: String, val propertyCode: String, val token: String)

/**
 * Клиент Hotel Hub API v1 (hotelhub/web/api.py), только чтение.
 * Телевизору нужны два маршрута: состояние номеров и имена гостей стоянки.
 */
class HubClient(private val http: HttpTransport, private val conn: HubConnection) {

    private val base = conn.baseUrl.trimEnd('/') + "/v1/properties/" + enc(conn.propertyCode)

    fun rooms(): List<HubRoom> {
        val o = get("$base/rooms")
        val arr = o.optJSONArray("rooms") ?: throw HubException(HubErrorKind.BAD_DATA, "rooms: нет поля rooms")
        val out = ArrayList<HubRoom>(arr.length())
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val room = r.str("room") ?: r.str("room_id") ?: continue
            out += HubRoom(
                room = room,
                roomId = r.str("room_id"),
                status = r.str("status") ?: "free",
                stayId = r.str("stay_id"),
                plannedIn = r.str("planned_in"),
                plannedOut = r.str("planned_out"),
            )
        }
        return out
    }

    fun guests(stayId: String): List<HubGuest> {
        val o = get("$base/stays/${enc(stayId)}/guests")
        val arr = o.optJSONArray("guests") ?: return emptyList()
        val out = ArrayList<HubGuest>(arr.length())
        for (i in 0 until arr.length()) {
            val g = arr.optJSONObject(i) ?: continue
            out += HubGuest(g.str("role"), g.str("first_name"), g.str("last_name"))
        }
        return out
    }

    private fun get(url: String): JSONObject {
        val res = try {
            http.get(url, mapOf("Authorization" to "Bearer ${conn.token}", "Accept" to "application/json"))
        } catch (e: IOException) {
            throw HubException(HubErrorKind.NETWORK, "Hotel Hub недоступен: ${e.message ?: e.javaClass.simpleName}")
        }
        val path = url.substringAfter("/v1/", url)
        when (res.code) {
            in 200..299 -> {}
            401 -> throw HubException(HubErrorKind.AUTH, "токен не принят (401)")
            403 -> throw HubException(HubErrorKind.FORBIDDEN, "у токена нет права для $path (403)")
            404 -> throw HubException(HubErrorKind.NOT_FOUND, "не найдено: $path (404)")
            429 -> throw HubException(HubErrorKind.RATE_LIMIT, "лимит запросов хаба (429)")
            else -> throw HubException(HubErrorKind.SERVER, "Hotel Hub вернул HTTP ${res.code}")
        }
        return try {
            JSONObject(res.body ?: "")
        } catch (e: JSONException) {
            throw HubException(HubErrorKind.BAD_DATA, "ответ хаба не JSON")
        }
    }

    private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).nullIfBlank()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
