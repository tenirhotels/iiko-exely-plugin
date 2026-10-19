package kz.tenir.tv.core

import kz.tenir.tv.core.content.HubSettings
import kz.tenir.tv.core.content.NameFormat
import kz.tenir.tv.core.guest.Greetings
import kz.tenir.tv.core.guest.GuestSession
import kz.tenir.tv.core.guest.GuestState
import kz.tenir.tv.core.guest.HubClient
import kz.tenir.tv.core.guest.HubConnection
import kz.tenir.tv.core.guest.HubErrorKind
import kz.tenir.tv.core.guest.HubException
import kz.tenir.tv.core.guest.HubOccupancySource
import kz.tenir.tv.core.util.Clock
import kz.tenir.tv.core.util.HttpResult
import kz.tenir.tv.core.util.HttpTransport
import kz.tenir.tv.core.util.MemoryTextStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Calendar
import java.util.TimeZone

class FakeClock(var now: Long) : Clock {
    override fun nowMillis() = now
}

/** Подменный Hotel Hub: отвечает JSON по пути, может «упасть». */
class FakeHub : HttpTransport {
    var rooms = """{"rooms": []}"""
    val guests = HashMap<String, String>()
    var down = false
    var code = 200
    val requests = ArrayList<String>()
    var lastAuth: String? = null

    override fun get(url: String, headers: Map<String, String>): HttpResult {
        requests += url
        lastAuth = headers["Authorization"]
        if (down) throw IOException("connect timed out")
        if (code != 200) return HttpResult(code, """{"error":"x"}""")
        return when {
            url.endsWith("/rooms") -> HttpResult(200, rooms)
            url.contains("/stays/") && url.endsWith("/guests") -> {
                val id = url.substringAfter("/stays/").substringBefore("/guests")
                HttpResult(200, guests[id] ?: """{"guests": []}""")
            }
            else -> HttpResult(404, """{"error":"нет"}""")
        }
    }

    fun room(label: String, status: String, stay: String?, plannedOut: String? = "2026-10-22T12:00:00") {
        val stayJson = if (stay == null) "null" else "\"$stay\""
        val outJson = if (plannedOut == null) "null" else "\"$plannedOut\""
        rooms = """{"rooms": [
            {"room": "101", "room_id": "83001", "status": "free", "booking": null, "stay_id": null,
             "planned_in": null, "planned_out": null, "updated_at": null},
            {"room": "$label", "room_id": "83305", "status": "$status", "booking": "1234-5", "stay_id": $stayJson,
             "planned_in": "2026-10-18T14:00:00", "planned_out": $outJson, "updated_at": "2026-10-18T14:05:00"}
        ]}"""
    }
}

class GuestSessionTest {
    private val tz: TimeZone = TimeZone.getTimeZone("Asia/Almaty")

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long {
        val c = Calendar.getInstance(tz)
        c.clear()
        c.set(y, mo - 1, d, h, mi)
        return c.timeInMillis
    }

    private val hub = FakeHub()
    private val clock = FakeClock(millis(2026, 10, 19, 20, 0))
    private val store = MemoryTextStore()
    private val checkouts = ArrayList<String>()

    private fun session(settings: HubSettings = HubSettings()): GuestSession {
        val client = HubClient(hub, HubConnection("http://hub.local:8000/", "9305", "secret"))
        return GuestSession(HubOccupancySource(client, "305"), clock, store, settings, tz) { checkouts += it }
    }

    @Test
    fun checkedInGuestIsShownWithNameAndRoom() {
        hub.room("305", "checked_in", "S1")
        hub.guests["S1"] = """{"guests": [
            {"role": "customer", "last_name": "ТОО Ромашка", "first_name": null},
            {"role": "guest", "last_name": "САПАРОВА", "first_name": "АЙГЕРИМ"}]}"""
        val s = session().poll() as GuestState.Present
        assertEquals("305", s.room)
        assertEquals("АЙГЕРИМ", s.firstName)
        assertEquals("Bearer secret", hub.lastAuth)
        assertEquals("http://hub.local:8000/v1/properties/9305/rooms", hub.requests.first())
        val g = Greetings.build("ru", 20, s, NameFormat.FIRST_NAME, showName = true, showRoom = true)
        assertEquals("Добрый вечер", g.overline)
        assertEquals("Айгерим", g.headline)
        assertEquals("Номер 305", g.roomLine)
        assertTrue(g.personal)
    }

    @Test
    fun bookedRoomIsNotPersonal() {
        hub.room("305", "booked", "S2")
        hub.guests["S2"] = """{"guests": [{"role": "guest", "last_name": "Иванов", "first_name": "Иван"}]}"""
        assertEquals(GuestState.Vacant, session().poll())
        assertFalse(hub.requests.any { it.contains("/guests") }) // имена ожидаемых гостей даже не запрашиваются
    }

    @Test
    fun roomMatchedByPmsIdToo() {
        hub.room("305", "checked_in", "S1")
        val client = HubClient(hub, HubConnection("http://hub", "9305", "t"))
        val s = GuestSession(HubOccupancySource(client, "83305"), clock, store, HubSettings(), tz).poll()
        assertTrue(s is GuestState.Present)
    }

    @Test
    fun checkoutClearsPreviousGuestAndFiresEvent() {
        hub.room("305", "checked_in", "S1")
        hub.guests["S1"] = """{"guests": [{"role": "guest", "last_name": "Сапарова", "first_name": "Айгерим"}]}"""
        val session = session()
        assertTrue(session.poll() is GuestState.Present)

        hub.room("305", "free", null, null)
        assertEquals(GuestState.Vacant, session.poll())
        assertEquals(listOf("S1"), checkouts)
        assertNull(store.read(GuestSession.KEY_LAST_STAY))
    }

    @Test
    fun newGuestNeverSeesPreviousName() {
        hub.room("305", "checked_in", "S1")
        hub.guests["S1"] = """{"guests": [{"role": "guest", "last_name": "Сапарова", "first_name": "Айгерим"}]}"""
        val session = session()
        session.poll()

        hub.room("305", "checked_in", "S2")
        hub.guests["S2"] = """{"guests": [{"role": "guest", "last_name": "Smith", "first_name": "John"}]}"""
        val s = session.poll() as GuestState.Present
        assertEquals("John", s.firstName)
        assertEquals(listOf("S1"), checkouts)
    }

    @Test
    fun checkoutDetectedAfterRestart() {
        store.write(GuestSession.KEY_LAST_STAY, "S-old") // стоянка до перезагрузки приставки
        hub.room("305", "free", null, null)
        assertEquals(GuestState.Vacant, session().poll())
        assertEquals(listOf("S-old"), checkouts)
    }

    @Test
    fun hubDownKeepsGuestOnlyWithinMaxAge() {
        hub.room("305", "checked_in", "S1")
        hub.guests["S1"] = """{"guests": [{"role": "guest", "last_name": "Сапарова", "first_name": "Айгерим"}]}"""
        val session = session(HubSettings(maxAgeMinutes = 10))
        session.poll()

        hub.down = true
        clock.now += 5 * 60_000
        assertTrue(session.poll() is GuestState.Present)
        assertEquals(HubErrorKind.NETWORK, session.status.lastErrorKind)

        clock.now += 6 * 60_000 // 11 минут без подтверждения
        assertEquals(GuestState.Unknown, session.poll())
        assertEquals(GuestState.Unknown, session.visible())
        assertTrue(checkouts.isEmpty()) // недоступность хаба — не выезд

        hub.down = false
        assertTrue(session.poll() is GuestState.Present)
    }

    @Test
    fun revokedTokenHidesGuestImmediately() {
        hub.room("305", "checked_in", "S1")
        val session = session()
        session.poll()
        hub.code = 401
        assertEquals(GuestState.Unknown, session.poll())
        assertEquals(HubErrorKind.AUTH, session.status.lastErrorKind)
    }

    @Test
    fun personalDataHiddenAfterCheckoutHourEvenIfPmsStillCheckedIn() {
        hub.room("305", "checked_in", "S1", plannedOut = "2026-10-19T12:00:00")
        hub.guests["S1"] = """{"guests": [{"role": "guest", "last_name": "Сапарова", "first_name": "Айгерим"}]}"""
        clock.now = millis(2026, 10, 19, 11, 30)
        val session = session(HubSettings(checkoutHour = 12, maxAgeMinutes = 60))
        assertTrue(session.poll() is GuestState.Present)
        clock.now = millis(2026, 10, 19, 12, 0)
        assertEquals(GuestState.Vacant, session.visible())
    }

    @Test
    fun missingGuestsScopeShowsRoomWithoutName() {
        hub.room("305", "checked_in", "S1")
        val forbiddenGuests = object : HttpTransport {
            override fun get(url: String, headers: Map<String, String>) =
                if (url.endsWith("/guests")) HttpResult(403, """{"error":"нет права guests"}""") else hub.get(url, headers)
        }
        val client = HubClient(forbiddenGuests, HubConnection("http://hub", "9305", "t"))
        val s = GuestSession(HubOccupancySource(client, "305"), clock, store, HubSettings(), tz).poll() as GuestState.Present
        assertNull(s.firstName)
        val g = Greetings.build("ru", 9, s, NameFormat.FIRST_NAME, showName = true, showRoom = true)
        assertEquals("Добро пожаловать", g.headline)
        assertEquals("Номер 305", g.roomLine)
    }

    @Test
    fun unknownRoomIsNotPersonal() {
        hub.room("999", "checked_in", "S1")
        val session = session()
        assertEquals(GuestState.Vacant, session.poll())
        assertEquals(false, session.status.roomFound)
    }

    @Test
    fun rateLimitIsReportedAsError() {
        hub.code = 429
        try {
            HubClient(hub, HubConnection("http://hub", "9305", "t")).rooms()
            throw AssertionError("ожидалась ошибка")
        } catch (e: HubException) {
            assertEquals(HubErrorKind.RATE_LIMIT, e.kind)
        }
    }

    @Test
    fun noSourceMeansUnknown() {
        val s = GuestSession(null, clock, store, HubSettings(), tz)
        assertEquals(GuestState.Unknown, s.poll())
    }
}
