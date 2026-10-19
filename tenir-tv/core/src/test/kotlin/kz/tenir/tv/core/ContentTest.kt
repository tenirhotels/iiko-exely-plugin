package kz.tenir.tv.core

import kz.tenir.tv.core.apps.AppResolver
import kz.tenir.tv.core.apps.KeySequenceDetector
import kz.tenir.tv.core.apps.Scaling
import kz.tenir.tv.core.content.ActionType
import kz.tenir.tv.core.content.ContentException
import kz.tenir.tv.core.content.ContentParser
import kz.tenir.tv.core.content.ContentRepository
import kz.tenir.tv.core.content.ContentSource
import kz.tenir.tv.core.content.NameFormat
import kz.tenir.tv.core.content.RefreshResult
import kz.tenir.tv.core.guest.Greetings
import kz.tenir.tv.core.guest.GuestState
import kz.tenir.tv.core.util.HttpResult
import kz.tenir.tv.core.util.HttpTransport
import kz.tenir.tv.core.util.LocalStamp
import kz.tenir.tv.core.util.MemoryTextStore
import kz.tenir.tv.core.weather.WeatherCondition
import kz.tenir.tv.core.weather.WeatherRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class ContentTest {

    private val demo: String by lazy {
        // демо-контент из ресурсов приложения: тест проверяет именно то, что попадёт в APK
        listOf("../app/src/main/assets/content-demo.json", "app/src/main/assets/content-demo.json")
            .map(::File).first { it.exists() }.readText()
    }

    @Test
    fun bundledDemoContentIsValid() {
        val c = ContentParser.parse(demo)
        assertEquals("TENIR", c.hotel.name)
        assertEquals(3, c.banners.size)
        assertEquals(ActionType.RESTAURANT, c.banners[0].action.type)
        assertNotNull(c.newsById(c.banners[1].action.target))
        assertEquals(3, c.restaurant!!.categories.size)
        assertEquals("Бешбармак", c.restaurant!!.categories[1].items[0].name.get("ru"))
        assertEquals("Shymbulak", c.weather!!.label.get("en"))
        assertEquals(listOf("ru", "en", "kk"), c.languages)
        assertEquals(12, c.hub.checkoutHour)
        assertTrue(c.homeTiles.containsAll(listOf("restaurant", "netflix", "youtube")))
    }

    @Test
    fun localizedTextFallsBack() {
        val c = ContentParser.parse(demo)
        val item = c.restaurant!!.categories[0].items[0]
        assertEquals("Farm cottage cheese, berry sauce", item.description.get("en"))
        assertEquals("Домашний творог, ягодный соус", item.description.get("kk")) // нет казахского → русский
    }

    @Test
    fun invalidItemsAreSkippedNotFatal() {
        val c = ContentParser.parse(
            """{"hotel": {"name": "T"}, "banners": [{"id": "x"}, {"title": "ok"}, 5],
                "apps": [{"id": "a"}, {"id": "b", "packages": ["com.b"]}],
                "weather": {"latitude": 999, "longitude": 1},
                "display": {"bannerIntervalSeconds": 1, "guestNameFormat": "full"}}""",
        )
        assertEquals(1, c.banners.size)
        assertEquals(listOf("b"), c.apps.map { it.id })
        assertNull(c.weather)
        assertEquals(3, c.display.bannerIntervalSeconds)
        assertEquals(NameFormat.FULL_NAME, c.display.guestNameFormat)
        assertEquals(ContentParser.DEFAULT_TILES, c.homeTiles)
    }

    @Test(expected = ContentException::class)
    fun documentWithoutHotelNameRejected() {
        ContentParser.parse("""{"hotel": {"name": " "}}""")
    }

    private class Server : HttpTransport {
        var body: String? = null
        var code = 200
        var etag: String? = null
        var down = false
        val sentHeaders = ArrayList<Map<String, String>>()
        override fun get(url: String, headers: Map<String, String>): HttpResult {
            sentHeaders += headers
            if (down) throw IOException("no route to host")
            if (etag != null && headers["If-None-Match"] == etag) return HttpResult(304, null)
            return HttpResult(code, body, if (etag != null) mapOf("ETag" to etag!!) else emptyMap())
        }
    }

    @Test
    fun repositoryFallsBackAndCaches() {
        val server = Server()
        val store = MemoryTextStore()
        val clock = FakeClock(1000)
        val repo = ContentRepository(server, store, clock) { demo }

        assertEquals(ContentSource.BUNDLED, repo.loadLocal().source)
        assertTrue(repo.refresh("") is RefreshResult.NotConfigured)

        server.down = true
        assertTrue(repo.refresh("https://cms/tv.json") is RefreshResult.Failed)

        server.down = false
        server.body = """{"version": 7, "hotel": {"name": "TENIR Server"}}"""
        server.etag = "\"v7\""
        val r = repo.refresh("https://cms/tv.json") as RefreshResult.Updated
        assertEquals("TENIR Server", r.snapshot.content.hotel.name)

        // перезапуск: контент берётся из кеша, без сети
        val repo2 = ContentRepository(server, store, clock) { demo }
        val local = repo2.loadLocal()
        assertEquals(ContentSource.CACHE, local.source)
        assertEquals(7L, local.content.version)

        assertTrue(repo2.refresh("https://cms/tv.json") is RefreshResult.NotModified)
        assertEquals("\"v7\"", server.sentHeaders.last()["If-None-Match"])

        // сломанный ответ не затирает кеш
        server.etag = null
        server.body = "<html>502</html>"
        assertTrue(repo2.refresh("https://cms/tv.json") is RefreshResult.Failed)
        assertEquals("TENIR Server", ContentRepository(server, store, clock) { demo }.loadLocal().content.hotel.name)
    }

    @Test
    fun corruptCacheFallsBackToBundled() {
        val store = MemoryTextStore()
        store.write(ContentRepository.KEY_BODY, "{oops")
        val repo = ContentRepository(Server(), store, FakeClock(0)) { demo }
        assertEquals(ContentSource.BUNDLED, repo.loadLocal().source)
        assertNull(store.read(ContentRepository.KEY_BODY))
    }

    @Test
    fun appTilesOnlyForInstalledPackages() {
        val c = ContentParser.parse(demo)
        val installed = setOf("com.google.android.youtube.tv", "com.android.settings")
        val r = AppResolver.resolve(c.apps) { it in installed }
        assertEquals(listOf("youtube"), r.map { it.tile.id })
        assertEquals("com.google.android.youtube.tv", r[0].packageName)
        assertTrue(AppResolver.resolve(c.apps) { false }.isEmpty())
    }

    @Test
    fun weatherParsing() {
        val w = WeatherRepository.parseOpenMeteo(
            """{"current": {"time": "2026-10-19T20:00", "temperature_2m": -4.4, "weather_code": 73, "is_day": 0}}""", 5,
        )!!
        assertEquals(WeatherCondition.SNOW, w.condition)
        assertEquals("−4°", w.temperatureText())
        assertNull(WeatherRepository.parseOpenMeteo("""{"error": true}""", 5))

        val store = MemoryTextStore()
        val clock = FakeClock(0)
        val repo = WeatherRepository(object : HttpTransport {
            override fun get(url: String, headers: Map<String, String>) =
                HttpResult(200, """{"current": {"temperature_2m": 12.6, "weather_code": 0, "is_day": 1}}""")
        }, store, clock, maxAgeMillis = 1000)
        val c = ContentParser.parse(demo)
        assertEquals("13°", repo.refresh(c.weather!!)!!.temperatureText())
        assertNotNull(repo.cached())
        clock.now = 5000
        assertNull(repo.cached()) // устаревшая погода не показывается
    }

    @Test
    fun greetingsWithoutGuest() {
        val g = Greetings.build("en", 8, GuestState.Unknown, NameFormat.FIRST_NAME, true, true)
        assertEquals("Good morning", g.overline)
        assertEquals("Welcome", g.headline)
        assertNull(g.roomLine)
        assertEquals("Қайырлы түн", Greetings.build("kk", 2, GuestState.Vacant, NameFormat.FIRST_NAME, true, true).overline)
        assertEquals("Добро пожаловать в TENIR",
            Greetings.build("ru", 13, GuestState.Vacant, NameFormat.FIRST_NAME, true, true, "Добро пожаловать в TENIR").headline)
    }

    @Test
    fun nameFormatting() {
        assertEquals("Анна-Мария", Greetings.normalizeCase("АННА-МАРИЯ"))
        assertEquals("McDonald", Greetings.normalizeCase("McDonald"))
        assertEquals("O'Brien", Greetings.normalizeCase("o'brien"))
        assertEquals("Иван Петров", Greetings.displayName("ИВАН", "ПЕТРОВ", NameFormat.FULL_NAME))
        assertEquals("Петров", Greetings.displayName(null, "петров", NameFormat.FIRST_NAME))
        assertNull(Greetings.displayName("Иван", "Петров", NameFormat.NONE))
    }

    @Test
    fun personalNameHiddenWhenDisabledByServer() {
        val p = GuestState.Present("305", "S1", "Айгерим", "Сапарова", null, 0)
        val g = Greetings.build("ru", 20, p, NameFormat.FIRST_NAME, showName = false, showRoom = true)
        assertEquals("Добро пожаловать", g.headline)
        assertEquals("Номер 305", g.roomLine)
    }

    @Test
    fun scalingKeepsDesignCanvas() {
        assertEquals(320, Scaling.densityDpiFor(1920, 1080))
        assertEquals(640, Scaling.densityDpiFor(3840, 2160))
        assertEquals(213, Scaling.densityDpiFor(1280, 720))
        assertEquals(320, Scaling.densityDpiFor(1920, 1200)) // 16:10 — по ширине
    }

    @Test
    fun secretKeySequence() {
        val up = 19; val down = 20; val left = 21; val right = 22
        val d = KeySequenceDetector(listOf(up, up, down, down, left, right, left, right), timeoutMillis = 1000)
        var t = 0L
        val keys = listOf(up, up, up, down, down, left, right, left)
        keys.forEach { assertEquals(false, d.onKey(it, t)); t += 100 }
        assertTrue(d.onKey(right, t)) // «вверх» лишний раз не ломает набор
        listOf(up, up, down).forEach { d.onKey(it, t) }
        t += 5000 // пауза — набор сначала
        listOf(down, left, right, left).forEach { assertEquals(false, d.onKey(it, t)) }
        assertEquals(false, d.onKey(right, t))
    }

    @Test
    fun deviceConfigMerge() {
        val base = DeviceConfig(room = "305", servicePin = "1234")
        val c = DeviceConfig.parse(
            """{"hubUrl": "http://hotelhub.local:8000", "propertyCode": "9305", "hubToken": "t", "demoMode": false}""", base,
        )
        assertEquals("305", c.room)
        assertEquals("1234", c.servicePin)
        assertTrue(c.isHubReady)
        assertEquals(c, DeviceConfig.parse(c.toJson()))
        assertNull(DeviceConfig(room = "1").hubConnection)
    }

    @Test
    fun localStampParsing() {
        val s = LocalStamp.parse("2026-10-22T12:00:00")!!
        assertEquals(LocalStamp(2026, 10, 22, 12, 0), s)
        assertEquals(LocalStamp(2026, 10, 22, 23, 59), LocalStamp.parse("2026-10-22")!!.atHour(24))
        assertNull(LocalStamp.parse("вчера"))
    }
}
