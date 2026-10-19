package kz.tenir.tv.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import kz.tenir.tv.AppContainer
import kz.tenir.tv.TenirApp
import kz.tenir.tv.core.apps.Scaling
import kz.tenir.tv.core.content.LocalizedText
import java.util.Locale

/**
 * Общая основа экранов.
 * 1. Масштаб: плотность подбирается так, что экран = 960×540 dp. Разметка в dp одинаково выглядит
 *    в 720p, Full HD и 4K; растровые изображения декодируются под реальные пиксели.
 * 2. Язык интерфейса из контента или настроек телевизора.
 * 3. Без системных панелей; Back закрывает экран (на главном — игнорируется).
 */
abstract class BaseTvActivity : Activity() {
    protected val app: AppContainer by lazy { TenirApp.container(this) }
    protected var appliedLanguage: String = "ru"
        private set

    protected val lang: String get() = appliedLanguage

    override fun attachBaseContext(newBase: Context) {
        val container = TenirApp.container(newBase)
        appliedLanguage = container.language
        val real = DisplayMetrics()
        @Suppress("DEPRECATION")
        (newBase.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(real)
        val dpi = Scaling.densityDpiFor(real.widthPixels, real.heightPixels)
        val conf = Configuration(newBase.resources.configuration)
        conf.densityDpi = dpi
        conf.fontScale = 1f
        conf.screenWidthDp = Math.round(maxOf(real.widthPixels, real.heightPixels) * 160f / dpi)
        conf.screenHeightDp = Math.round(minOf(real.widthPixels, real.heightPixels) * 160f / dpi)
        conf.smallestScreenWidthDp = conf.screenHeightDp
        conf.setLocale(Locale(appliedLanguage))
        super.attachBaseContext(newBase.createConfigurationContext(conf))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    override fun onResume() {
        super.onResume()
        if (app.language != appliedLanguage) {
            recreate()
            return
        }
        // после прикрепления окна: фокус, запрошенный в onCreate/onStart, мог не встать
        window.decorView.post { ensureFocus() }
    }

    private var focusPlaced = false

    /**
     * Первый показ экрана: фокус на [initialFocus], даже если система уже отдала его первому элементу сверху.
     * Дальше — только если фокус потерян (элемент удалён при обновлении контента).
     */
    private fun ensureFocus() {
        if (focusPlaced && window.decorView.findFocus() != null) return
        val v = initialFocus() ?: return
        focusPlaced = v.requestFocus()
    }

    /** Элемент, получающий фокус при открытии экрана (пульт без фокуса — «мёртвый» экран). */
    protected open fun initialFocus(): View? = null

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // requestFocus до прикрепления окна (и в режиме касания на части прошивок) не срабатывает
        if (hasFocus) ensureFocus()
    }

    fun t(text: LocalizedText): String = text.get(lang)

    /** Цвет акцента из контента (золото TENIR по умолчанию). */
    val accent: Int get() = Ui.parseColor(app.hotel.hotel.accentColor, Ui.GOLD)

    fun notice(text: String) = Notice.show(this, text)
}
