package kz.tenir.tv.core

import kz.tenir.tv.core.cloud.CloudClient
import kz.tenir.tv.core.cloud.CloudOccupancySource
import kz.tenir.tv.core.cloud.Heartbeat
import kz.tenir.tv.core.content.HubSettings
import kz.tenir.tv.core.guest.GuestSession
import kz.tenir.tv.core.guest.GuestState
import kz.tenir.tv.core.guest.HubErrorKind
import kz.tenir.tv.core.util.HttpResult
import kz.tenir.tv.core.util.HttpTransport
import kz.tenir.tv.core.util.MemoryTextStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.TimeZone

/** Подменное облако: регистрирует, отвечает на heartbeat заготовкой. */
class FakeCloud : HttpTransport {
    var heartbeat = """{"paired": false, "pairing_code": "715685", "content": {"version": 0}}"""
    var heartbeatCode = 200
    var down = false
    val posts = ArrayList<Triple<String, String, Map<String, String>>>()
    var registered = 0

    override fun get(url: String, headers: Map<String, String>) = HttpResult(404, null)

    override fun post(url: String, json: String, headers: Map<String, String>): HttpResult {
        if (down) throw IOException("no route to host")
        posts += Triple(url, json, headers)
        return when {
            url.endsWith("/register") -> {
                registered++
                HttpResult(201, """{"device_id": "d1", "device_key": "key$registered", "pairing_code": "715685"}""")
            }
            url.endsWith("/heartbeat") ->
                if (headers["Authorization"] == "Bearer key$registered") HttpResult(heartbeatCode, heartbeat)
                else HttpResult(401, """{"detail": "ключ"}""")
            else -> HttpResult(200, """{"ok": true}""")
        }
    }

    fun paired(guest: String?) {
        heartbeat = """{"paired": true, "room": "305", "property": {"code": "9305", "name": "TENIR"},
            "config": {"language": "en", "servicePin": "4826", "pollSeconds": 45},
            "guest": ${guest ?: "null"}, "content": {"version": 7, "etag": "\"abc\""},
            "messages": [{"id": 3, "text": {"ru": "Бассейн закрыт", "en": "Pool closed"}, "expires_at": null}],
            "commands": [{"id": 9, "kind": "reload_content"}]}"""
    }
}

class CloudTest {
    private val clock = FakeClock(1_000_000)
    private val tz = TimeZone.getTimeZone("Asia/Almaty")
    private val device = { Triple("X96 Max", "11", "0.2.0") }

    private fun source(cloud: FakeCloud, store: MemoryTextStore, beats: MutableList<Heartbeat>) =
        CloudOccupancySource(CloudClient(cloud, "http://cloud.local:8090/", store),
            { Triple(JSONObject().put("app_version", "0.2.0"), 0L, "home") }, device) { beats += it }

    @Test
    fun unpairedDeviceRegistersOnceAndShowsCode() {
        val cloud = FakeCloud()
        val store = MemoryTextStore()
        val beats = ArrayList<Heartbeat>()
        val s = GuestSession(source(cloud, store, beats), clock, store, HubSettings(), tz)
        assertEquals(GuestState.Vacant, s.poll())
        s.poll()
        assertEquals(1, cloud.registered)
        assertEquals("http://cloud.local:8090/api/tv/v1/register", cloud.posts[0].first)
        assertEquals("715685", beats.last().pairingCode)
        assertEquals(false, beats.last().paired)
        assertEquals("key1", store.read(CloudClient.KEY))
    }

    @Test
    fun pairedGuestConfigMessagesCommands() {
        val cloud = FakeCloud()
        cloud.paired("""{"status": "checked_in", "stay_id": "S1", "first_name": "АЙГЕРИМ", "last_name": "С",
            "planned_out": "2099-01-01T12:00:00"}""")
        val store = MemoryTextStore()
        val beats = ArrayList<Heartbeat>()
        val s = GuestSession(source(cloud, store, beats), clock, store, HubSettings(), tz)
        val g = s.poll() as GuestState.Present
        assertEquals("АЙГЕРИМ", g.firstName)
        assertEquals("305", g.room)
        val hb = beats.last()
        assertEquals("en", hb.language)
        assertEquals("4826", hb.servicePin)
        assertEquals(45, hb.pollSeconds)
        assertEquals(7L, hb.contentVersion)
        assertEquals("Pool closed", hb.messages[0].text.get("en"))
        assertEquals("reload_content", hb.commands[0].kind)
        val body = JSONObject(cloud.posts.last().second)
        assertEquals("home", body.getString("screen"))
        assertEquals("0.2.0", body.getJSONObject("status").getString("app_version"))
    }

    @Test
    fun cloudWithoutHubDataHidesGuestImmediatelyWithoutCheckout() {
        val cloud = FakeCloud()
        cloud.paired("""{"status": "checked_in", "stay_id": "S1", "first_name": "Айгерим", "last_name": "С"}""")
        val store = MemoryTextStore()
        val checkouts = ArrayList<String>()
        val s = GuestSession(source(cloud, store, ArrayList()), clock, store, HubSettings(), tz) { checkouts += it }
        assertTrue(s.poll() is GuestState.Present)
        cloud.paired(null) // облако: хаб недоступен дольше допустимого
        assertEquals(GuestState.Unknown, s.poll())
        assertEquals(HubErrorKind.STALE, s.status.lastErrorKind)
        assertTrue(checkouts.isEmpty())
        // выезд: облако отдаёт свободный номер → данные очищаются, событие выезда
        cloud.paired("""{"status": "free", "stay_id": null}""")
        assertEquals(GuestState.Vacant, s.poll())
        assertEquals(listOf("S1"), checkouts)
    }

    @Test
    fun revokedKeyReRegisters() {
        val cloud = FakeCloud()
        val store = MemoryTextStore()
        store.write(CloudClient.KEY, "old-key")
        val beats = ArrayList<Heartbeat>()
        GuestSession(source(cloud, store, beats), clock, store, HubSettings(), tz).poll()
        assertEquals(1, cloud.registered)
        assertEquals("key1", store.read(CloudClient.KEY))
        assertEquals("715685", beats.last().pairingCode)
    }

    @Test
    fun cloudDownKeepsGuestWithinMaxAge() {
        val cloud = FakeCloud()
        cloud.paired("""{"status": "checked_in", "stay_id": "S1", "first_name": "Айгерим"}""")
        val store = MemoryTextStore()
        val s = GuestSession(source(cloud, store, ArrayList()), clock, store, HubSettings(maxAgeMinutes = 10), tz)
        s.poll()
        cloud.down = true
        clock.now += 5 * 60_000
        assertTrue(s.poll() is GuestState.Present)
        assertEquals(HubErrorKind.NETWORK, s.status.lastErrorKind)
        clock.now += 6 * 60_000
        assertEquals(GuestState.Unknown, s.poll())
    }

    @Test
    fun deviceConfigCloudMode() {
        val c = DeviceConfig.parse("""{"cloudUrl": "https://tv.tenir.kz"}""")
        assertTrue(c.isCloud)
        assertEquals(c, DeviceConfig.parse(c.toJson()))
        assertNull(Heartbeat.parse("""{"paired": true, "guest": null}""").guest)
    }

    /**
     * Сквозная проверка с настоящим сервисом: TENIR_CLOUD_E2E_URL=http://127.0.0.1:8090.
     * Без переменной пропускается.
     */
    @Test
    fun endToEndWithRealCloud() {
        val url = System.getenv("TENIR_CLOUD_E2E_URL")
        Assume.assumeTrue(url != null)
        val http = object : HttpTransport {
            override fun get(url: String, headers: Map<String, String>) = send("GET", url, null, headers)
            override fun post(url: String, json: String, headers: Map<String, String>) = send("POST", url, json, headers)
            fun send(method: String, u: String, body: String?, headers: Map<String, String>): HttpResult {
                val c = URL(u).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
                c.requestMethod = method
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    c.outputStream.use { it.write(body.toByteArray()) }
                }
                val stream = if (c.responseCode >= 400) c.errorStream else c.inputStream
                return HttpResult(c.responseCode, stream?.use { it.readBytes().toString(Charsets.UTF_8) },
                    c.headerFields.filterKeys { it != null }.mapValues { it.value.first() })
            }
        }
        val store = MemoryTextStore()
        val client = CloudClient(http, url!!, store)
        val hb = client.heartbeat(JSONObject().put("app_version", "e2e"), 0, "home", device)
        assertEquals(false, hb.paired)
        assertEquals(6, hb.pairingCode!!.length)
        val content = http.get(client.contentUrl, client.authHeaders())
        assertEquals(200, content.code)
        assertTrue(content.header("ETag") != null)
        println("E2E pairing_code=${hb.pairingCode}")
    }
}
