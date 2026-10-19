package kz.tenir.tv

import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import kz.tenir.tv.core.DeviceConfig
import kz.tenir.tv.core.guest.GuestState
import kz.tenir.tv.ui.home.HomeActivity
import kz.tenir.tv.ui.restaurant.RestaurantActivity
import kz.tenir.tv.ui.service.ServiceActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Главный экран на «телевизоре» Full HD (1920×1080, xhdpi). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestTenirApp::class, qualifiers = "w960dp-h540dp-land-television-xhdpi")
class HomeActivityTest {

    private val app get() = TenirApp.container(RuntimeEnvironment.getApplication())

    @Before
    fun setUp() {
        FakeNet.reset()
        Tv.remoteMode()
    }

    private fun launch(): HomeActivity {
        val a = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        Tv.waitFor(what = "демо-гость") { app.guest.value is GuestState.Present }
        Tv.settle()
        return a
    }

    private fun text(a: HomeActivity, id: Int) = a.findViewById<TextView>(id).text.toString()

    @Test
    fun demoGuestGreetingClockWeatherAndTiles() {
        val a = launch()
        assertEquals("Айгерим", text(a, R.id.greetingHeadline))
        assertEquals("Номер 305", text(a, R.id.roomLine))
        assertEquals("TENIR", text(a, R.id.hotelName))
        assertTrue(text(a, R.id.time).matches(Regex("\\d{2}:\\d{2}")))
        Tv.waitFor(what = "погода") { a.findViewById<View>(R.id.weatherBlock).visibility == View.VISIBLE }
        assertEquals("−4°", text(a, R.id.weatherTemp))
        assertTrue(text(a, R.id.weatherLabel).startsWith("Шымбулак"))
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.demoBadge).visibility)

        val tiles = a.findViewById<android.widget.LinearLayout>(R.id.tiles)
        val ids = (0 until tiles.childCount).map { tiles.getChildAt(it).tag }
        // Netflix и YouTube не установлены — их плиток нет
        assertEquals(listOf("restaurant", "news", "apps", "info"), ids)
        Tv.snapshot(a, "home-1080p")
    }

    @Test
    fun remoteNavigationAcrossTilesAndBanner() {
        val a = launch()
        val tiles = a.findViewById<android.widget.LinearLayout>(R.id.tiles)
        assertEquals("restaurant", a.window.decorView.findFocus()?.tag) // стартовый фокус — первая плитка

        Tv.press(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("news", a.window.decorView.findFocus()?.tag)
        Tv.press(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        Tv.press(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("info", a.window.decorView.findFocus()?.tag)
        Tv.press(a, KeyEvent.KEYCODE_DPAD_RIGHT) // край строки: фокус остаётся
        assertEquals("info", a.window.decorView.findFocus()?.tag)

        Tv.press(a, KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(R.id.banner, a.window.decorView.findFocus()?.id)
        val first = text(a, R.id.bannerTitle)
        Tv.press(a, KeyEvent.KEYCODE_DPAD_RIGHT) // на баннере стрелки листают слайды
        assertFalse(first == text(a, R.id.bannerTitle))
        Tv.settle(800)
        Tv.snapshot(a, "home-1080p-banner-focus")

        Tv.press(a, KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(a.window.decorView.findFocus()?.parent === tiles)

        // фокус хорошо заметен: плитка увеличена и поднята
        val focused = a.window.decorView.findFocus()!!
        Tv.settle(400)
        assertTrue(focused.scaleX > 1.05f)
        assertNotNull((focused as android.widget.FrameLayout).foreground)
    }

    @Test
    fun okOpensRestaurantAndBackIsIgnoredOnHome() {
        val a = launch()
        Tv.press(a, KeyEvent.KEYCODE_BACK)
        assertFalse(a.isFinishing)

        Tv.press(a, KeyEvent.KEYCODE_DPAD_CENTER)
        val next = shadowOf(a).nextStartedActivity
        assertEquals(RestaurantActivity::class.java.name, next.component?.className)
    }

    @Test
    fun secretSequenceOpensServiceMenu() {
        val a = launch()
        listOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        ).forEach { Tv.press(a, it) }
        assertEquals(ServiceActivity::class.java.name, shadowOf(a).nextStartedActivity?.component?.className)
    }

    @Test
    fun installedNetflixAndYoutubeGetTilesAndLaunch() {
        Tv.installTvApp("com.netflix.ninja", "Netflix")
        Tv.installTvApp("com.google.android.youtube.tv", "YouTube")
        val a = launch()
        val tiles = a.findViewById<android.widget.LinearLayout>(R.id.tiles)
        val ids = (0 until tiles.childCount).map { tiles.getChildAt(it).tag }
        assertEquals(listOf("restaurant", "netflix", "youtube", "news", "apps", "info"), ids)
        Tv.snapshot(a, "home-1080p-apps")

        tiles.getChildAt(1).requestFocus()
        Tv.press(a, KeyEvent.KEYCODE_DPAD_CENTER)
        val started = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals("com.netflix.ninja", started?.component?.packageName ?: started?.`package`)
    }

    @Test
    fun hubGuestShownThenClearedOnCheckout() {
        FakeNet.room("305", "checked_in", "S1")
        FakeNet.guests["S1"] = """{"guests": [{"role": "guest", "first_name": "JOHN", "last_name": "SMITH"}]}"""
        app.saveDeviceConfig(DeviceConfig(room = "305", hubUrl = "http://hub.test", propertyCode = "9305",
            hubToken = "t", demoMode = false))
        val a = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        Tv.waitFor(what = "гость из хаба") { text(a, R.id.greetingHeadline) == "John" }
        assertEquals(View.GONE, a.findViewById<View>(R.id.demoBadge).visibility)

        FakeNet.room("305", "free", null) // выезд
        app.refreshAll()
        Tv.waitFor(what = "очистка после выезда") { app.guest.value == GuestState.Vacant }
        Tv.settle(300)
        assertEquals("Добро пожаловать", text(a, R.id.greetingHeadline))
        assertEquals(View.GONE, a.findViewById<View>(R.id.roomRow).visibility)
        assertTrue(app.events().any { it.contains("Выезд: стоянка S1") })
    }

    @Test
    fun hubUnavailableShowsGenericGreetingAndKeepsWorking() {
        FakeNet.hubDown = true
        app.saveDeviceConfig(DeviceConfig(room = "305", hubUrl = "http://hub.test", propertyCode = "9305",
            hubToken = "t", demoMode = false))
        val a = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        Tv.waitFor(what = "ошибка хаба") { app.sessionStatus.lastError != null }
        Tv.settle(300)
        assertEquals("Добро пожаловать", text(a, R.id.greetingHeadline))
        assertEquals(View.GONE, a.findViewById<View>(R.id.roomRow).visibility)
        assertNull(app.sessionStatus.roomFound)
        // интерфейс жив: навигация и разделы работают
        Tv.press(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("news", a.window.decorView.findFocus()?.tag)
        Tv.snapshot(a, "home-1080p-hub-down")
    }
}

/** Тот же экран в 4K UHD (3840×2160, xxxhdpi): композиция в dp та же, пиксели вдвое плотнее. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestTenirApp::class, qualifiers = "w960dp-h540dp-land-television-xxxhdpi")
class HomeActivity4kTest {
    @Test
    fun rendersAt4k() {
        FakeNet.reset()
        Tv.remoteMode()
        val a = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        val app = TenirApp.container(a)
        Tv.waitFor { app.guest.value is GuestState.Present }
        Tv.settle()
        val root = a.window.decorView
        assertEquals(3840, root.width)
        assertEquals(2160, root.height)
        assertEquals(4.0f, a.resources.displayMetrics.density, 0.01f)
        val tiles = a.findViewById<View>(R.id.tiles)
        assertEquals(128 * 4, tiles.height) // 128 dp
        Tv.snapshot(a, "home-4k")
    }
}

/** 720p-приставка (1280×720, mdpi): плотность пересчитывается, холст остаётся 960×540 dp. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestTenirApp::class, qualifiers = "w1280dp-h720dp-land-television-mdpi")
class HomeActivity720pTest {
    @Test
    fun scalesDesignCanvas() {
        FakeNet.reset()
        val a = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        Tv.settle()
        assertEquals(1280, a.window.decorView.width)
        // 1280 px / (213 dpi / 160) = 961.5 dp: целая плотность даёт ±2 dp к холсту 960
        val widthDp = a.window.decorView.width / a.resources.displayMetrics.density
        assertEquals(960f, widthDp, 2f)
        Tv.snapshot(a, "home-720p")
    }
}
