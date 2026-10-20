package kz.tenir.tv

import android.view.View
import android.widget.TextView
import kz.tenir.tv.core.DeviceConfig
import kz.tenir.tv.core.guest.GuestState
import kz.tenir.tv.ui.home.HomeActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Приставка под управлением TENIR TV Cloud: подключение по коду, гость, сообщения, команды. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestTenirApp::class, qualifiers = "w960dp-h540dp-land-television-xhdpi")
class CloudModeTest {
    private val app get() = TenirApp.container(RuntimeEnvironment.getApplication())

    @Before
    fun setUp() {
        FakeNet.reset()
        Tv.remoteMode()
    }

    private fun text(a: HomeActivity, id: Int) = a.findViewById<TextView>(id).text.toString()

    @Test
    fun unpairedDeviceShowsPairingCode() {
        app.saveDeviceConfig(DeviceConfig(cloudUrl = "http://cloud.test"))
        val a = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        Tv.waitFor(what = "код подключения") { text(a, R.id.greetingHeadline) == "715 685" }
        assertEquals("Подключение к TENIR TV Cloud", text(a, R.id.greetingOverline))
        assertEquals(View.GONE, a.findViewById<View>(R.id.demoBadge).visibility)
        assertTrue(FakeNet.requests.contains("POST http://cloud.test/api/tv/v1/register"))
        Tv.snapshot(a, "home-1080p-cloud-pairing")
    }

    @Test
    fun pairedDeviceShowsGuestMessageAndRunsCommand() {
        FakeNet.cloudHeartbeat = """{"paired": true, "room": "305", "property": {"code": "9305", "name": "TENIR"},
            "config": {"language": "", "servicePin": "4826", "pollSeconds": 60},
            "guest": {"status": "checked_in", "stay_id": "S1", "first_name": "Дана", "last_name": "К",
                      "planned_out": "2099-01-01T12:00:00"},
            "content": {"version": 5, "etag": "\"v5\""},
            "messages": [{"id": 1, "text": {"ru": "Бассейн закрыт до 14:00", "en": "Pool closed"}, "expires_at": null}],
            "commands": [{"id": 77, "kind": "refresh"}]}"""
        app.saveDeviceConfig(DeviceConfig(cloudUrl = "http://cloud.test"))
        val a = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        Tv.waitFor(what = "гость из облака") { text(a, R.id.greetingHeadline) == "Дана" }
        assertEquals("Номер 305", text(a, R.id.roomLine))
        Tv.waitFor(what = "сообщение") { a.findViewById<View>(R.id.messageBox).visibility == View.VISIBLE }
        assertEquals("Бассейн закрыт до 14:00", text(a, R.id.messageBox))
        Tv.waitFor(what = "подтверждение команды") { synchronized(FakeNet.acks) { FakeNet.acks.any { it.startsWith("77 ") } } }
        assertTrue(FakeNet.acks.first().contains("\"ok\":true"))
        Tv.waitFor(what = "контент из облака") { app.hotel.hotel.name == "TENIR Cloud" }
        assertEquals("4826", app.deviceConfig.config.servicePin) // PIN приходит из облака
        assertTrue(FakeNet.requests.contains("http://cloud.test/api/tv/v1/content"))
        Tv.settle(400)
        Tv.snapshot(a, "home-1080p-cloud-message")

        // облако перестало подтверждать гостя (хаб недоступен) → имя скрывается сразу
        FakeNet.cloudHeartbeat = """{"paired": true, "room": "305", "guest": null, "content": {"version": 5}}"""
        app.refreshAll()
        Tv.waitFor(what = "имя скрыто") { app.guest.value == GuestState.Unknown }
    }
}
