package kz.tenir.tv

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import kz.tenir.tv.core.util.HttpResult
import kz.tenir.tv.core.util.HttpTransport
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.io.File
import java.io.IOException
import java.time.Duration

/** Подменная сеть: погода и Hotel Hub отвечают заготовками, остальное недоступно. */
object FakeNet : HttpTransport {
    @Volatile var hubDown = false
    /** Ответ облака на heartbeat (null — облако не отвечает). */
    @Volatile var cloudHeartbeat: String? = """{"paired": false, "pairing_code": "715685", "content": {"version": 0}}"""
    val acks = ArrayList<String>()
    @Volatile var rooms = """{"rooms": []}"""
    val guests = HashMap<String, String>()
    val requests = ArrayList<String>()

    fun reset() {
        hubDown = false
        rooms = """{"rooms": []}"""
        guests.clear()
        synchronized(requests) { requests.clear() }
        cloudHeartbeat = """{"paired": false, "pairing_code": "715685", "content": {"version": 0}}"""
        synchronized(acks) { acks.clear() }
    }

    override fun get(url: String, headers: Map<String, String>): HttpResult {
        synchronized(requests) { requests += url }
        return when {
            url.startsWith("https://api.open-meteo.com/") ->
                HttpResult(200, """{"current": {"temperature_2m": -4.3, "weather_code": 73, "is_day": 0}}""")
            url.contains("/v1/properties/") -> {
                if (hubDown) throw IOException("connect timed out")
                when {
                    url.endsWith("/rooms") -> HttpResult(200, rooms)
                    url.endsWith("/guests") -> {
                        val id = url.substringAfter("/stays/").substringBefore("/guests")
                        HttpResult(200, guests[id] ?: """{"guests": []}""")
                    }
                    else -> HttpResult(404, """{"error": "нет"}""")
                }
            }
            url.startsWith("http://cloud.test/api/tv/v1/content") ->
                HttpResult(200, """{"version": 5, "hotel": {"name": "TENIR Cloud"}}""", mapOf("ETag" to "\"v5\""))
            else -> throw IOException("нет сети в тесте: $url")
        }
    }

    override fun post(url: String, json: String, headers: Map<String, String>): HttpResult {
        synchronized(requests) { requests += "POST $url" }
        return when {
            url == "http://cloud.test/api/tv/v1/register" ->
                HttpResult(201, """{"device_id": "d1", "device_key": "k1", "pairing_code": "715685"}""")
            url == "http://cloud.test/api/tv/v1/heartbeat" ->
                cloudHeartbeat?.let { HttpResult(200, it) } ?: throw IOException("облако недоступно")
            url.startsWith("http://cloud.test/api/tv/v1/commands/") -> {
                synchronized(acks) { acks += url.substringAfter("/commands/").substringBefore("/ack") + " " + json }
                HttpResult(200, """{"ok": true}""")
            }
            else -> throw IOException("нет сети в тесте: $url")
        }
    }

    fun room(label: String, status: String, stay: String?) {
        val s = if (stay == null) "null" else "\"$stay\""
        rooms = """{"rooms": [{"room": "$label", "room_id": "83305", "status": "$status", "stay_id": $s,
            "planned_in": "2026-10-18T14:00:00", "planned_out": "2099-01-01T12:00:00"}]}"""
    }
}

class TestTenirApp : TenirApp() {
    override fun onCreate() {
        // в Robolectric у сети нет флага INTERNET — включаем, как у реального Ethernet
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
        super.onCreate()
    }

    override fun createContainer() = AppContainer(this, FakeNet)
}

object Tv {
    /** Пульт, а не сенсор: вне режима касания фокус виден и ставится программно, как на приставке. */
    fun remoteMode() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
    }

    /** Прогон главного потока и фоновых задач, пока условие не выполнится. */
    fun waitFor(timeoutMs: Long = 5000, what: String = "условие", cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            if (cond()) return
            Thread.sleep(30)
        }
        throw AssertionError("не дождались: $what")
    }

    fun settle(ms: Long = 1200) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            Thread.sleep(20)
        }
    }

    /**
     * Нажатие кнопки пульта. Перемещение фокуса стрелками в Android делает ViewRootImpl:
     * если никто не обработал клавишу, ищется следующий фокус по направлению — повторяем это здесь.
     */
    fun press(activity: Activity, keyCode: Int) {
        val handled = activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        if (!handled) {
            val dir = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
                KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                else -> 0
            }
            if (dir != 0) activity.window.decorView.findFocus()?.focusSearch(dir)?.requestFocus(dir)
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
    }

    fun snapshot(activity: Activity, name: String): File {
        val root = activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        // плавные переходы изображений отсчитываются от первого кадра: рисуем, ждём, рисуем снова
        root.draw(Canvas(bmp))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        root.draw(Canvas(bmp))
        val dir = File(System.getProperty("tenir.screenshots") ?: "build/screenshots").apply { mkdirs() }
        val f = File(dir, "$name.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    /** «Установить» приложение с точкой запуска Android TV. */
    fun installTvApp(pkg: String, label: String) {
        val pm = shadowOf(RuntimeEnvironment.getApplication().packageManager)
        val app = ApplicationInfo().apply { packageName = pkg; enabled = true; nonLocalizedLabel = label }
        pm.installPackage(PackageInfo().apply { packageName = pkg; applicationInfo = app })
        val component = ComponentName(pkg, "$pkg.MainActivity")
        pm.addActivityIfNotPresent(component)
        pm.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        })
        pm.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        })
    }
}
