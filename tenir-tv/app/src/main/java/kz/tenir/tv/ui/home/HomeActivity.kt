package kz.tenir.tv.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kz.tenir.tv.R
import kz.tenir.tv.core.apps.KeySequenceDetector
import kz.tenir.tv.core.content.Action
import kz.tenir.tv.core.content.ActionType
import kz.tenir.tv.core.content.Banner
import kz.tenir.tv.core.content.HotelContent
import kz.tenir.tv.core.content.Tiles
import kz.tenir.tv.core.guest.GuestState
import kz.tenir.tv.core.guest.Greetings
import kz.tenir.tv.core.weather.Weather
import kz.tenir.tv.core.weather.WeatherCondition
import kz.tenir.tv.data.LaunchResult
import kz.tenir.tv.ui.BaseTvActivity
import kz.tenir.tv.ui.Focus
import kz.tenir.tv.ui.Ui
import kz.tenir.tv.ui.apps.AppsActivity
import kz.tenir.tv.ui.info.InfoActivity
import kz.tenir.tv.ui.news.NewsActivity
import kz.tenir.tv.ui.restaurant.RestaurantActivity
import kz.tenir.tv.ui.service.ServiceActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Главный экран TENIR TV (категория HOME): приветствие гостя, время, погода, баннеры и плитки.
 * Все данные приходят из AppContainer; здесь только отображение и навигация пультом.
 */
class HomeActivity : BaseTvActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val subscriptions = ArrayList<() -> Unit>()
    private lateinit var tiles: LinearLayout
    private lateinit var banner: FrameLayout

    private var banners: List<Banner> = emptyList()
    private var bannerIndex = 0
    private var bannerFocused = false
    private var bannerFrontIsA = true
    private var renderedContentVersion = -1L
    private var renderedContent: HotelContent? = null

    private val secret = KeySequenceDetector(
        listOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        ),
    )

    private val clockTick = object : Runnable {
        override fun run() {
            renderClock()
            val now = System.currentTimeMillis()
            handler.postDelayed(this, 60_000 - now % 60_000 + 50)
        }
    }

    private val bannerTick = object : Runnable {
        override fun run() {
            if (!bannerFocused && banners.size > 1) showBanner(bannerIndex + 1)
            handler.postDelayed(this, app.hotel.display.bannerIntervalSeconds * 1000L)
        }
    }

    private val packagesChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            renderTiles(app.hotel)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        tiles = findViewById(R.id.tiles)
        banner = findViewById(R.id.banner)
        val radius = Ui.dp(this, 16f).toFloat()
        banner.background = Ui.rounded(Ui.color(this, R.color.surface), radius)
        Ui.clipRounded(banner, radius)
        Focus.apply(banner, accent, radius, scale = 1.025f) { has ->
            bannerFocused = has
        }
        banner.setOnClickListener { banners.getOrNull(bannerIndex)?.let { runAction(it.action) } }
        banner.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN || banners.size < 2) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { showBanner(bannerIndex + 1); true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { showBanner(bannerIndex - 1); true }
                else -> false
            }
        }
        findViewById<TextView>(R.id.demoBadge).background =
            Ui.rounded(0, Ui.dp(this, 4f).toFloat(), Ui.dp(this, 1f), accent)
        findViewById<TextView>(R.id.offline).background =
            Ui.rounded(Ui.withAlpha(0xFFB3261E.toInt(), 0.85f), Ui.dp(this, 12f).toFloat())
    }

    override fun onStart() {
        super.onStart()
        subscriptions += app.content.observe { snap ->
            if (app.language != lang) {
                recreate()
                return@observe
            }
            renderContent(snap.content)
        }
        subscriptions += app.guest.observe { renderGreeting(it) }
        subscriptions += app.weather.observe { renderWeather(it) }
        subscriptions += app.online.observe { findViewById<View>(R.id.offline).visibility = if (it) View.GONE else View.VISIBLE }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        registerReceiver(packagesChanged, filter)
        handler.post(clockTick)
        handler.postDelayed(bannerTick, app.hotel.display.bannerIntervalSeconds * 1000L)
    }

    override fun onResume() {
        super.onResume()
        // вернулись из Netflix/YouTube: приложение могли удалить или обновить
        renderedContent?.let { renderTiles(it) }
        renderGreeting(app.guest.value)
        findViewById<View>(R.id.demoBadge).visibility = if (app.deviceConfig.config.demoMode) View.VISIBLE else View.GONE
    }

    override fun onStop() {
        super.onStop()
        subscriptions.forEach { it() }
        subscriptions.clear()
        try {
            unregisterReceiver(packagesChanged)
        } catch (e: IllegalArgumentException) {
            // не был зарегистрирован
        }
        handler.removeCallbacksAndMessages(null)
    }

    /** Лаунчер: Back на главном экране ничего не закрывает. Секретная комбинация открывает сервисное меню. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && secret.onKey(event.keyCode, event.eventTime)) {
            startActivity(Intent(this, ServiceActivity::class.java))
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // кнопка HOME при открытом главном экране — вернуть фокус к началу
        tiles.getChildAt(0)?.requestFocus()
    }

    override fun initialFocus(): View? = tiles.getChildAt(0)

    // --- отображение ---

    private fun renderContent(c: HotelContent) {
        val sameContent = renderedContent === c
        renderedContent = c
        findViewById<TextView>(R.id.hotelName).text = c.hotel.name.uppercase(Locale(lang))
        findViewById<TextView>(R.id.tagline).text = t(c.hotel.tagline)
        val logo = findViewById<ImageView>(R.id.logo)
        if (c.hotel.logoUrl != null) {
            app.images.load(c.hotel.logoUrl, logo, getDrawable(R.drawable.ic_tenir_mark))
        } else {
            logo.setImageResource(R.drawable.ic_tenir_mark)
            logo.setColorFilter(accent)
        }
        app.images.load(c.hotel.backgroundUrl, findViewById(R.id.background), Ui.placeholder(this))
        findViewById<View>(R.id.clockBlock).visibility = if (c.display.showClock) View.VISIBLE else View.GONE
        findViewById<View>(R.id.roomRule).setBackgroundColor(accent)
        findViewById<TextView>(R.id.greetingOverline).setTextColor(accent)
        findViewById<TextView>(R.id.bannerLabel).setTextColor(accent)
        renderWeather(app.weather.value)
        renderGreeting(app.guest.value)
        renderClock()
        if (!sameContent || renderedContentVersion != c.version) {
            renderedContentVersion = c.version
            banners = c.banners
            banner.visibility = if (banners.isEmpty()) View.INVISIBLE else View.VISIBLE
            banner.isFocusable = banners.isNotEmpty()
            renderDots()
            showBanner(0, animate = false)
            renderTiles(c)
        }
    }

    private fun renderGreeting(state: GuestState) {
        val c = app.hotel
        val hour = Calendar.getInstance(app.timeZone).get(Calendar.HOUR_OF_DAY)
        val g = Greetings.build(
            lang, hour, state, c.display.guestNameFormat, c.display.showGuestName, c.display.showRoomNumber,
            welcomeOverride = t(c.hotel.welcome),
        )
        findViewById<TextView>(R.id.greetingOverline).text = g.overline
        findViewById<TextView>(R.id.greetingHeadline).text = g.headline
        findViewById<View>(R.id.roomRow).visibility = if (g.roomLine != null) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.roomLine).text = g.roomLine ?: ""
        findViewById<TextView>(R.id.greetingNote).setText(if (g.personal) R.string.home_note_personal else R.string.home_note)
    }

    private fun renderClock() {
        val c = app.hotel
        val now = Date()
        val locale = Locale(lang)
        val time = SimpleDateFormat(if (c.display.use24h) "HH:mm" else "h:mm a", locale).apply { timeZone = app.timeZone }
        val date = SimpleDateFormat("EEEE, d MMMM", locale).apply { timeZone = app.timeZone }
        findViewById<TextView>(R.id.time).text = time.format(now)
        findViewById<TextView>(R.id.date).text = date.format(now).replaceFirstChar { it.uppercase(locale) }
    }

    private fun renderWeather(w: Weather?) {
        val block = findViewById<View>(R.id.weatherBlock)
        val c = app.hotel
        if (w == null || !c.display.showWeather || c.weather == null) {
            block.visibility = View.GONE
            return
        }
        block.visibility = View.VISIBLE
        findViewById<TextView>(R.id.weatherTemp).text = w.temperatureText()
        val label = t(c.weather!!.label)
        val cond = getString(conditionText(w.condition))
        findViewById<TextView>(R.id.weatherLabel).text = if (label.isBlank()) cond else "$label · $cond"
        findViewById<ImageView>(R.id.weatherIcon).apply {
            setImageResource(conditionIcon(w.condition, w.isDay))
            setColorFilter(accent)
        }
    }

    private fun renderDots() {
        val dots = findViewById<LinearLayout>(R.id.bannerDots)
        dots.removeAllViews()
        if (banners.size < 2) return
        val size = Ui.dp(this, 6f)
        banners.indices.forEach { _ ->
            dots.addView(View(this).apply { background = getDrawable(R.drawable.dot)?.mutate() },
                LinearLayout.LayoutParams(size, size).apply { marginStart = Ui.dp(this@HomeActivity, 7f) })
        }
    }

    private fun showBanner(index: Int, animate: Boolean = true) {
        if (banners.isEmpty()) return
        bannerIndex = ((index % banners.size) + banners.size) % banners.size
        val b = banners[bannerIndex]
        findViewById<TextView>(R.id.bannerLabel).apply {
            text = t(b.label)
            visibility = if (b.label.isBlank) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.bannerTitle).text = t(b.title)
        findViewById<TextView>(R.id.bannerSubtitle).text = t(b.subtitle)
        val dots = findViewById<LinearLayout>(R.id.bannerDots)
        for (i in 0 until dots.childCount) {
            dots.getChildAt(i).alpha = if (i == bannerIndex) 1f else 0.35f
            dots.getChildAt(i).background.setTint(if (i == bannerIndex) accent else 0xFFFFFFFF.toInt())
        }
        val a = findViewById<ImageView>(R.id.bannerImage)
        val b2 = findViewById<ImageView>(R.id.bannerImageNext)
        val front = if (bannerFrontIsA) a else b2
        val back = if (bannerFrontIsA) b2 else a
        if (!animate) {
            front.alpha = 1f
            back.alpha = 0f
            app.images.load(b.imageUrl, front, Ui.placeholder(this, bannerIndex))
            return
        }
        // плавная смена: следующий кадр грузится в скрытый слой и проявляется поверх текущего
        bannerFrontIsA = !bannerFrontIsA
        back.animate().cancel()
        front.animate().cancel()
        back.alpha = 0f
        app.images.load(b.imageUrl, back, Ui.placeholder(this, bannerIndex)) {
            back.animate().alpha(1f).setDuration(450).start()
            front.animate().alpha(0f).setDuration(450).start()
        }
    }

    private fun renderTiles(c: HotelContent) {
        val focusedId = tiles.findFocus()?.tag as? String
        tiles.removeAllViews()
        val resolved = app.visibleApps().associateBy { it.tile.id }
        val specs = c.homeTiles.mapNotNull { id ->
            when (id) {
                Tiles.RESTAURANT -> c.restaurant?.let {
                    TileSpec(id, t(it.name).ifBlank { getString(R.string.tile_restaurant) },
                        getString(R.string.tile_restaurant_sub), R.drawable.ic_restaurant, it.imageUrl, weight = 1.7f) {
                        startActivity(Intent(this, RestaurantActivity::class.java))
                    }
                }
                Tiles.NEWS -> if (c.news.isEmpty()) null else TileSpec(id, getString(R.string.tile_news),
                    getString(R.string.tile_news_sub), R.drawable.ic_news) {
                    startActivity(Intent(this, NewsActivity::class.java))
                }
                Tiles.APPS -> TileSpec(id, getString(R.string.tile_apps), getString(R.string.tile_apps_sub), R.drawable.ic_apps) {
                    startActivity(Intent(this, AppsActivity::class.java))
                }
                Tiles.INFO -> TileSpec(id, getString(R.string.tile_info), getString(R.string.tile_info_sub), R.drawable.ic_info) {
                    startActivity(Intent(this, InfoActivity::class.java))
                }
                else -> resolved[id]?.let { ra ->
                    val bannerArt = app.launcher.banner(ra.packageName)
                    TileSpec(id, ra.tile.title, getString(R.string.tile_app_sub), R.drawable.ic_play,
                        appArt = bannerArt ?: app.launcher.icon(ra.packageName), appArtIsBanner = bannerArt != null,
                        color = Ui.parseColor(ra.tile.color, Ui.color(this, R.color.surface_hi))) {
                        launch(ra.packageName, ra.tile.title)
                    }
                }
            }
        }
        val gap = Ui.dp(this, 14f)
        // широкая плитка ресторана ужимается, когда плиток много (подписи не должны обрезаться)
        val wide = if (specs.size >= 6) 1.3f else 1.7f
        specs.forEachIndexed { i, spec ->
            val weight = if (spec.weight > 1f) wide else spec.weight
            val v = TileFactory.build(this, spec, accent) { url, iv -> app.images.load(url, iv, Ui.placeholder(this, 1)) }
            v.tag = spec.id
            v.id = View.generateViewId()
            tiles.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight).apply {
                if (i < specs.size - 1) marginEnd = gap
            })
        }
        val restore = (0 until tiles.childCount).map(tiles::getChildAt).firstOrNull { it.tag == focusedId }
        if (restore != null) restore.requestFocus() else if (window.decorView.findFocus() == null) tiles.getChildAt(0)?.requestFocus()
    }

    private fun runAction(a: Action) {
        when (a.type) {
            ActionType.RESTAURANT -> if (app.hotel.restaurant != null) {
                startActivity(Intent(this, RestaurantActivity::class.java))
            } else {
                notice(getString(R.string.section_unavailable))
            }
            ActionType.NEWS -> startActivity(Intent(this, NewsActivity::class.java).putExtra(NewsActivity.EXTRA_ID, a.target))
            ActionType.APPS -> startActivity(Intent(this, AppsActivity::class.java))
            ActionType.INFO -> startActivity(Intent(this, InfoActivity::class.java))
            ActionType.APP -> {
                val tile = app.hotel.appById(a.target)
                val pkg = app.visibleApps().firstOrNull { it.tile.id == a.target }?.packageName ?: a.target
                if (pkg != null) launch(pkg, tile?.title ?: pkg)
            }
            ActionType.NONE -> {}
        }
    }

    private fun launch(pkg: String, title: String) {
        when (val r = app.launcher.launch(pkg)) {
            LaunchResult.Started -> {}
            LaunchResult.NotInstalled -> {
                notice(getString(R.string.launch_not_installed, title))
                renderTiles(app.hotel)
            }
            is LaunchResult.Failed -> {
                app.event("Запуск $pkg: ${r.reason}")
                notice(getString(R.string.launch_failed, title))
            }
        }
    }

    companion object {
        fun conditionText(c: WeatherCondition) = when (c) {
            WeatherCondition.CLEAR -> R.string.weather_clear
            WeatherCondition.PARTLY_CLOUDY -> R.string.weather_partly_cloudy
            WeatherCondition.CLOUDY -> R.string.weather_cloudy
            WeatherCondition.FOG -> R.string.weather_fog
            WeatherCondition.DRIZZLE -> R.string.weather_drizzle
            WeatherCondition.RAIN -> R.string.weather_rain
            WeatherCondition.SNOW -> R.string.weather_snow
            WeatherCondition.STORM -> R.string.weather_storm
        }

        fun conditionIcon(c: WeatherCondition, day: Boolean) = when (c) {
            WeatherCondition.CLEAR -> if (day) R.drawable.ic_w_clear else R.drawable.ic_w_night
            WeatherCondition.PARTLY_CLOUDY -> R.drawable.ic_w_partly
            WeatherCondition.CLOUDY -> R.drawable.ic_w_cloud
            WeatherCondition.FOG -> R.drawable.ic_w_fog
            WeatherCondition.DRIZZLE, WeatherCondition.RAIN -> R.drawable.ic_w_rain
            WeatherCondition.SNOW -> R.drawable.ic_w_snow
            WeatherCondition.STORM -> R.drawable.ic_w_storm
        }
    }
}
