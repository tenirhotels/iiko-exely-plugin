package kz.tenir.tv.core.cloud

import kz.tenir.tv.core.content.ContentParser
import kz.tenir.tv.core.content.LocalizedText
import kz.tenir.tv.core.guest.HubErrorKind
import kz.tenir.tv.core.guest.HubException
import kz.tenir.tv.core.guest.HubGuest
import kz.tenir.tv.core.guest.OccupancySource
import kz.tenir.tv.core.guest.RoomOccupancy
import kz.tenir.tv.core.util.HttpTransport
import kz.tenir.tv.core.util.TextStore
import kz.tenir.tv.core.util.nullIfBlank
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

data class CloudGuest(
    val status: String,
    val stayId: String?,
    val firstName: String?,
    val lastName: String?,
    val plannedOut: String?,
)

data class CloudMessage(val id: Long, val text: LocalizedText, val expiresAt: String?)

data class CloudCommand(val id: Long, val kind: String)

/** Ответ на heartbeat: всё, что облако сообщает приставке за один запрос. */
data class Heartbeat(
    val paired: Boolean,
    val pairingCode: String?,
    val room: String?,
    val propertyName: String?,
    val language: String?,
    val servicePin: String?,
    val pollSeconds: Int?,
    /** null — у облака нет подтверждённых данных о госте (хаб недоступен или номер не привязан). */
    val guest: CloudGuest?,
    val contentVersion: Long,
    val contentEtag: String?,
    val messages: List<CloudMessage>,
    val commands: List<CloudCommand>,
) {
    companion object {
        fun parse(body: String): Heartbeat {
            val o = try {
                JSONObject(body)
            } catch (e: JSONException) {
                throw HubException(HubErrorKind.BAD_DATA, "ответ облака не JSON")
            }
            val cfg = o.optJSONObject("config")
            val g = if (o.isNull("guest")) null else o.optJSONObject("guest")
            val c = o.optJSONObject("content")
            val msgs = ArrayList<CloudMessage>()
            o.optJSONArray("messages")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    val tmp = JSONObject().put("t", m.opt("text") ?: "")
                    msgs += CloudMessage(m.optLong("id"), ContentParser.text(tmp, "t"), m.str("expires_at"))
                }
            }
            val cmds = ArrayList<CloudCommand>()
            o.optJSONArray("commands")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    m.str("kind")?.let { cmds += CloudCommand(m.optLong("id"), it) }
                }
            }
            return Heartbeat(
                paired = o.optBoolean("paired", false),
                pairingCode = o.str("pairing_code"),
                room = o.str("room"),
                propertyName = o.optJSONObject("property")?.str("name"),
                language = cfg?.let { if (it.isNull("language")) null else it.optString("language") },
                servicePin = cfg?.str("servicePin"),
                pollSeconds = cfg?.let { if (it.has("pollSeconds")) it.optInt("pollSeconds") else null },
                guest = g?.let {
                    CloudGuest(it.str("status") ?: "unknown", it.str("stay_id"), it.str("first_name"),
                        it.str("last_name"), it.str("planned_out"))
                },
                contentVersion = c?.optLong("version", 0) ?: 0,
                contentEtag = c?.str("etag"),
                messages = msgs,
                commands = cmds,
            )
        }

        private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).nullIfBlank()
    }
}

/**
 * Клиент TENIR TV Cloud (/api/tv/v1). Ключ приставки выдаётся один раз при регистрации и хранится в [store];
 * при 401 (приставку удалили или перевыпустили ключ) регистрация повторяется — на экране появится новый код.
 */
class CloudClient(
    private val http: HttpTransport,
    baseUrl: String,
    private val store: TextStore,
) {
    private val base = normalizeUrl(baseUrl) + "/api/tv/v1"

    val contentUrl: String get() = "$base/content"

    val key: String? get() = store.read(KEY)

    fun authHeaders(): Map<String, String> = key?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()

    fun register(model: String, android: String, appVersion: String): String {
        val body = JSONObject().put("model", model).put("android", android).put("app_version", appVersion)
        val res = call { http.post("$base/register", body.toString()) }
        if (res.code != 201 && res.code != 200) throw HubException(kindOf(res.code), "регистрация: HTTP ${res.code}")
        val o = try {
            JSONObject(res.body ?: "")
        } catch (e: JSONException) {
            throw HubException(HubErrorKind.BAD_DATA, "ответ облака не JSON")
        }
        val k = o.optString("device_key").nullIfBlank() ?: throw HubException(HubErrorKind.BAD_DATA, "нет ключа")
        store.write(KEY, k)
        return o.optString("pairing_code")
    }

    /** status — состояние приставки (Diagnostics), screen — текущий экран. */
    fun heartbeat(status: JSONObject, contentVersion: Long, screen: String, device: () -> Triple<String, String, String>): Heartbeat {
        if (key == null) {
            val (model, android, version) = device()
            register(model, android, version)
        }
        val body = JSONObject().put("status", status).put("content_version", contentVersion).put("screen", screen)
        var res = call { http.post("$base/heartbeat", body.toString(), authHeaders()) }
        if (res.code == 401) {
            store.delete(KEY)
            val (model, android, version) = device()
            register(model, android, version)
            res = call { http.post("$base/heartbeat", body.toString(), authHeaders()) }
        }
        if (!res.isSuccess) throw HubException(kindOf(res.code), "облако вернуло HTTP ${res.code}")
        return Heartbeat.parse(res.body ?: "")
    }

    fun ack(id: Long, ok: Boolean, result: String) {
        val body = JSONObject().put("ok", ok).put("result", result)
        try {
            http.post("$base/commands/$id/ack", body.toString(), authHeaders())
        } catch (e: IOException) {
            // подтверждение не критично: команда уже выполнена
        }
    }

    fun forget() = store.delete(KEY)

    private inline fun call(block: () -> kz.tenir.tv.core.util.HttpResult) = try {
        block()
    } catch (e: IOException) {
        throw HubException(HubErrorKind.NETWORK, "TENIR TV Cloud недоступен: ${e.message ?: e.javaClass.simpleName}")
    }

    private fun kindOf(code: Int) = when (code) {
        401 -> HubErrorKind.AUTH
        403 -> HubErrorKind.FORBIDDEN
        404 -> HubErrorKind.NOT_FOUND
        429 -> HubErrorKind.RATE_LIMIT
        else -> HubErrorKind.SERVER
    }

    companion object {
        const val KEY = "cloud.key"

        /** «192.168.1.10:8090» → «http://192.168.1.10:8090»: в локальной сети схему часто не вводят. */
        fun normalizeUrl(raw: String): String {
            val t = raw.trim().trimEnd('/')
            return if (t.startsWith("http://") || t.startsWith("https://")) t else "http://$t"
        }
    }
}

/**
 * Гость из облака для существующей GuestSession: облако само опрашивает Hotel Hub и отдаёт данные только
 * этого номера. Каждый опрос — это heartbeat; остальное из ответа (команды, сообщения, контент) — в [onHeartbeat].
 */
class CloudOccupancySource(
    private val client: CloudClient,
    private val request: () -> Triple<JSONObject, Long, String>,
    private val device: () -> Triple<String, String, String>,
    private val onHeartbeat: (Heartbeat) -> Unit,
) : OccupancySource {

    override fun fetch(): RoomOccupancy {
        val (status, version, screen) = request()
        val hb = client.heartbeat(status, version, screen, device)
        onHeartbeat(hb)
        if (!hb.paired || hb.room == null) return RoomOccupancy(false, null, "unknown", null, null, emptyList())
        val g = hb.guest ?: throw HubException(HubErrorKind.STALE, "облако: нет подтверждённых данных о госте")
        val guests = if (g.firstName != null || g.lastName != null) listOf(HubGuest("guest", g.firstName, g.lastName))
        else emptyList()
        return RoomOccupancy(true, hb.room, g.status, g.stayId, g.plannedOut, guests)
    }
}
