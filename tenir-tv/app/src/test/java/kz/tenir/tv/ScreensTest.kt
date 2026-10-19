package kz.tenir.tv

import android.content.Intent
import android.view.KeyEvent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kz.tenir.tv.ui.apps.AppsActivity
import kz.tenir.tv.ui.info.InfoActivity
import kz.tenir.tv.ui.news.NewsActivity
import kz.tenir.tv.ui.restaurant.RestaurantActivity
import kz.tenir.tv.ui.service.ServiceActivity
import org.junit.Assert.assertEquals
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestTenirApp::class, qualifiers = "w960dp-h540dp-land-television-xhdpi")
class ScreensTest {

    @Before
    fun setUp() {
        FakeNet.reset()
        Tv.remoteMode()
    }

    @Test
    fun restaurantMenuFollowsCategoryFocus() {
        val a = Robolectric.buildActivity(RestaurantActivity::class.java).setup().get()
        Tv.settle()
        val items = a.findViewById<LinearLayout>(R.id.items)
        val firstItem = { ((items.getChildAt(0) as LinearLayout).getChildAt(0) as LinearLayout).getChildAt(0) as TextView }
        assertEquals("Сырники со сметаной", firstItem().text.toString())
        assertEquals("Ресторан Tenir", a.findViewById<TextView>(R.id.title).text.toString())
        Tv.snapshot(a, "restaurant-1080p")

        Tv.press(a, KeyEvent.KEYCODE_DPAD_DOWN) // категория «Основные блюда»
        assertEquals("Бешбармак", firstItem().text.toString())
        Tv.press(a, KeyEvent.KEYCODE_DPAD_RIGHT) // в список блюд
        assertTrue(a.window.decorView.findFocus()?.parent === items)
        Tv.press(a, KeyEvent.KEYCODE_DPAD_DOWN)
        Tv.press(a, KeyEvent.KEYCODE_DPAD_LEFT) // обратно к выбранной категории
        assertEquals("Основные блюда", (a.window.decorView.findFocus() as TextView).text.toString())

        Tv.press(a, KeyEvent.KEYCODE_BACK)
        assertTrue(a.isFinishing)
    }

    @Test
    fun newsOpensRequestedArticle() {
        val intent = Intent(RuntimeEnvironment.getApplication(), NewsActivity::class.java)
            .putExtra(NewsActivity.EXTRA_ID, "sauna")
        val a = Robolectric.buildActivity(NewsActivity::class.java, intent).setup().get()
        Tv.settle()
        assertEquals("Сауна и купель", a.findViewById<TextView>(R.id.title).text.toString())
        Tv.press(a, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("Акклиматизация на высоте", a.findViewById<TextView>(R.id.title).text.toString())
        Tv.snapshot(a, "news-1080p")
    }

    @Test
    fun infoShowsWifiAndReception() {
        val a = Robolectric.buildActivity(InfoActivity::class.java).setup().get()
        Tv.settle()
        val cards = a.findViewById<LinearLayout>(R.id.cards)
        assertEquals(2, cards.childCount)
        Tv.snapshot(a, "info-1080p")
    }

    @Test
    fun appsGridLaunchesInstalledApp() {
        Tv.installTvApp("com.google.android.youtube.tv", "YouTube")
        Tv.installTvApp("org.videolan.vlc", "VLC")
        val a = Robolectric.buildActivity(AppsActivity::class.java).setup().get()
        Tv.settle()
        val grid = a.findViewById<LinearLayout>(R.id.grid)
        val row = grid.getChildAt(0) as LinearLayout
        assertEquals("VLC", row.getChildAt(0).contentDescription) // по алфавиту
        Tv.press(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        Tv.press(a, KeyEvent.KEYCODE_DPAD_CENTER)
        val started = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals("com.google.android.youtube.tv", started?.component?.packageName)
        Tv.snapshot(a, "apps-1080p")
    }

    @Test
    fun serviceMenuRequiresPinAndSavesConfig() {
        val a = Robolectric.buildActivity(ServiceActivity::class.java).setup().get()
        val pin = a.findViewById<EditText>(R.id.pin)
        pin.setText("1111")
        a.findViewById<android.view.View>(R.id.pinOk).performClick()
        assertEquals(android.view.View.GONE, a.findViewById<android.view.View>(R.id.settingsPanel).visibility)
        pin.setText("0000")
        a.findViewById<android.view.View>(R.id.pinOk).performClick()
        assertEquals(android.view.View.VISIBLE, a.findViewById<android.view.View>(R.id.settingsPanel).visibility)

        a.findViewById<EditText>(R.id.f_room).setText("412")
        a.findViewById<android.view.View>(R.id.b_save).performClick()
        assertEquals("412", TenirApp.container(a).deviceConfig.config.room)
        Tv.settle(1500)
        Tv.snapshot(a, "service-1080p")
    }
}
