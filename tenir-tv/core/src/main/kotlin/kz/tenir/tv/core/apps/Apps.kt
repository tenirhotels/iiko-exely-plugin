package kz.tenir.tv.core.apps

import kz.tenir.tv.core.content.AppTile

data class ResolvedApp(val tile: AppTile, val packageName: String)

object AppResolver {
    /**
     * Для каждой плитки — первый установленный пакет, у которого есть точка запуска.
     * Плитки без установленного приложения не показываются.
     */
    fun resolve(tiles: List<AppTile>, isLaunchable: (String) -> Boolean): List<ResolvedApp> =
        tiles.mapNotNull { tile -> tile.packages.firstOrNull(isLaunchable)?.let { ResolvedApp(tile, it) } }
}

/**
 * Секретная последовательность кнопок пульта (вход в сервисное меню).
 * Коды — любые Int (на устройстве KeyEvent.KEYCODE_*).
 */
class KeySequenceDetector(private val sequence: List<Int>, private val timeoutMillis: Long = 4000) {
    private val typed = ArrayList<Int>()
    private var lastAt = 0L

    /** true — последовательность набрана полностью (лишние нажатия перед ней не мешают). */
    fun onKey(code: Int, nowMillis: Long): Boolean {
        if (typed.isNotEmpty() && nowMillis - lastAt > timeoutMillis) typed.clear()
        lastAt = nowMillis
        typed += code
        if (typed.size > sequence.size) typed.removeAt(0)
        if (typed == sequence) {
            typed.clear()
            return true
        }
        return false
    }
}

/** Масштабирование: весь интерфейс рисуется на холсте 960×540 dp и растягивается на экран (720p, Full HD, 4K). */
object Scaling {
    const val DESIGN_WIDTH_DP = 960
    const val DESIGN_HEIGHT_DP = 540

    /** densityDpi, при которой экран widthPx×heightPx ровно вмещает холст. */
    fun densityDpiFor(widthPx: Int, heightPx: Int): Int {
        val w = maxOf(widthPx, heightPx)
        val h = minOf(widthPx, heightPx)
        val scale = minOf(w / DESIGN_WIDTH_DP.toFloat(), h / DESIGN_HEIGHT_DP.toFloat())
        return Math.round(scale * 160).coerceAtLeast(80)
    }
}
