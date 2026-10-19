package kz.tenir.tv

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kz.tenir.tv.core.DeviceConfig
import kz.tenir.tv.core.apps.AppResolver
import kz.tenir.tv.core.apps.ResolvedApp
import kz.tenir.tv.core.content.ContentRepository
import kz.tenir.tv.core.content.ContentSnapshot
import kz.tenir.tv.core.content.HotelContent
import kz.tenir.tv.core.content.RefreshResult
import kz.tenir.tv.core.guest.DemoOccupancySource
import kz.tenir.tv.core.guest.GuestSession
import kz.tenir.tv.core.guest.GuestState
import kz.tenir.tv.core.guest.HubClient
import kz.tenir.tv.core.guest.HubErrorKind
import kz.tenir.tv.core.guest.HubOccupancySource
import kz.tenir.tv.core.guest.OccupancySource
import kz.tenir.tv.core.util.HttpTransport
import kz.tenir.tv.core.util.SystemClock
import kz.tenir.tv.core.weather.Weather
import kz.tenir.tv.core.weather.WeatherRepository
import kz.tenir.tv.data.AndroidHttpTransport
import kz.tenir.tv.data.AppLauncher
import kz.tenir.tv.data.DeviceConfigStore
import kz.tenir.tv.data.FileTextStore
import kz.tenir.tv.image.ImageLoader
import kz.tenir.tv.system.DeviceOwner
import kz.tenir.tv.system.Diagnostics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Значение, за которым следит интерфейс. Подписчики вызываются в главном потоке. */
class Observable<T>(initial: T) {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<(T) -> Unit>()

    @Volatile
    var value: T = initial
        private set

    fun post(v: T) {
        main.post {
            value = v
            listeners.toList().forEach { it(v) }
        }
    }

    /** Сразу отдаёт текущее значение. Вернуть результат и вызвать в onStop. */
    fun observe(listener: (T) -> Unit): () -> Unit {
        listeners += listener
        listener(value)
        return { listeners -= listener }
    }
}

/**
 * Ручная сборка зависимостей и фоновая работа. Сетевые запросы — только в пуле [bg],
 * главный поток получает готовые значения через [Observable].
 */
class AppContainer(
    private val context: Context,
    httpOverride: HttpTransport? = null,
) {
    val version: String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }
    private val http: HttpTransport =
        httpOverride ?: AndroidHttpTransport("TENIR-TV/$version (Android ${Build.VERSION.RELEASE})")
    private val store = FileTextStore(context)
    private val bg: ScheduledExecutorService = Executors.newScheduledThreadPool(2) { r ->
        Thread(r, "tenir-bg").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    val deviceConfig = DeviceConfigStore(context)
    val launcher = AppLauncher(context)
    val images = ImageLoader(context)
    val contentRepo = ContentRepository(http, store, SystemClock) {
        context.assets.open("content-demo.json").use { it.readBytes().toString(Charsets.UTF_8) }
    }
    private val weatherRepo = WeatherRepository(http, store, SystemClock)

    val content = Observable(contentRepo.loadLocal())
    val weather = Observable<Weather?>(weatherRepo.cached())
    val guest = Observable<GuestState>(GuestState.Unknown)
    val online = Observable(Diagnostics.isOnline(context))

    @Volatile
    private var session: GuestSession = buildSession()
    private var hubTask: ScheduledFuture<*>? = null
    private var hubBackoff = 1
    private val log = ArrayDeque<String>()

    val hotel: HotelContent get() = content.value.content

    /** Язык интерфейса: из настроек телевизора, иначе из контента. */
    val language: String
        get() = deviceConfig.config.language.ifBlank { hotel.defaultLanguage }.ifBlank { "ru" }

    val timeZone: TimeZone get() = TimeZone.getTimeZone(hotel.display.timezone)

    val sessionStatus get() = session.status

    fun start() {
        if (deviceConfig.importProvisioningFile()) {
            event(deviceConfig.lastImportMessage ?: "device.json применён")
            session = buildSession()
        }
        DeviceOwner.pinHomeActivity(context)
        scheduleHub(0)
        bg.scheduleWithFixedDelay({ safe("content") { refreshContent() } }, 2, 15 * 60, TimeUnit.SECONDS)
        bg.scheduleWithFixedDelay({ safe("weather") { refreshWeather() } }, 3, 30 * 60, TimeUnit.SECONDS)
        // данные гостя устаревают и без новых опросов (хаб недоступен, наступил час выезда)
        bg.scheduleWithFixedDelay({ guest.post(session.visible()) }, 30, 30, TimeUnit.SECONDS)
        watchNetwork()
    }

    private fun buildSession(): GuestSession {
        val c = deviceConfig.config
        val source: OccupancySource? = when {
            c.demoMode -> DemoOccupancySource(c.room.ifBlank { "305" })
            c.isHubReady -> HubOccupancySource(HubClient(http, c.hubConnection!!), c.room)
            else -> null
        }
        return GuestSession(source, SystemClock, store, hotel.hub, timeZone) { previous ->
            onCheckout(previous)
        }
    }

    private fun onCheckout(previousStayId: String) {
        event("Выезд: стоянка $previousStayId закрыта, данные гостя очищены")
        guest.post(GuestState.Unknown)
        DeviceOwner.clearAppData(context, hotel.hub.clearAppsOnCheckout) { event(it) }
    }

    private fun scheduleHub(delaySec: Long) {
        hubTask?.cancel(false)
        hubTask = bg.schedule(Runnable {
            var next = hotel.hub.pollSeconds.toLong()
            safe("hub") {
                val s = session
                s.settings = hotel.hub
                guest.post(s.poll())
                val err = s.status.lastErrorKind
                hubBackoff = if (err == HubErrorKind.RATE_LIMIT || err == HubErrorKind.NETWORK) {
                    (hubBackoff * 2).coerceAtMost(8)
                } else {
                    1
                }
                next *= hubBackoff
            }
            scheduleHub(next)
        }, delaySec, TimeUnit.SECONDS)
    }

    fun refreshContent() {
        when (val r = contentRepo.refresh(deviceConfig.config.contentUrl)) {
            is RefreshResult.Updated -> {
                applyContent(r.snapshot)
                event("Контент обновлён: версия ${r.snapshot.content.version}")
            }
            is RefreshResult.Failed -> event("Контент не обновлён: ${r.reason}")
            else -> {}
        }
    }

    private fun applyContent(s: ContentSnapshot) {
        session.settings = s.content.hub
        content.post(s)
        images.prefetch(contentImages(s.content))
    }

    private fun contentImages(c: HotelContent): List<String?> =
        listOf(c.hotel.backgroundUrl, c.hotel.logoUrl, c.restaurant?.imageUrl) +
            c.banners.map { it.imageUrl } + c.news.map { it.imageUrl }

    fun refreshWeather() {
        val loc = hotel.weather ?: return
        weatherRepo.refresh(loc)?.let { weather.post(it) }
    }

    /** Все обновления сейчас (после смены настроек, восстановления сети). */
    fun refreshAll() {
        bg.execute { safe("content") { refreshContent() } }
        bg.execute { safe("weather") { refreshWeather() } }
        scheduleHub(0)
    }

    fun saveDeviceConfig(c: DeviceConfig) {
        deviceConfig.save(c)
        session.forget()
        session = buildSession()
        guest.post(GuestState.Unknown)
        refreshAll()
    }

    fun resetContentCache() {
        contentRepo.clearCache()
        images.clearDisk()
        applyContent(contentRepo.loadLocal())
    }

    fun visibleApps(): List<ResolvedApp> = AppResolver.resolve(hotel.apps, launcher::isLaunchable)

    fun events(): List<String> = synchronized(log) { log.toList() }

    fun event(text: String) {
        val line = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US).format(Date()) + "  " + text
        Log.i(TAG, text)
        synchronized(log) {
            log.addFirst(line)
            while (log.size > 50) log.removeLast()
        }
    }

    private inline fun safe(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            event("$what: ошибка ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun watchNetwork() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post {
                    val was = online.value
                    online.post(true)
                    if (!was) refreshAll()
                }
            }

            override fun onLost(network: Network) {
                main.postDelayed({ online.post(Diagnostics.isOnline(context)) }, 1500)
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
        } catch (e: RuntimeException) {
            Log.w(TAG, "нет доступа к состоянию сети", e)
        }
    }

    companion object {
        private const val TAG = "Tenir"
    }
}
