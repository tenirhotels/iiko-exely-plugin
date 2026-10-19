package kz.tenir.tv.system

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import kz.tenir.tv.core.apps.Scaling

/**
 * Проверка совместимости приставки: сеть (Ethernet/Wi-Fi), режимы экрана и HDR,
 * аппаратные декодеры 4K (H.264/HEVC/VP9/AV1). Показывается в сервисном меню.
 */
object Diagnostics {

    fun report(context: Context): String = buildString {
        appendLine("Устройство: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}")
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        appendLine("Память: ${mem.totalMem / (1024 * 1024)} МБ, куча приложения ${am.memoryClass} МБ (large ${am.largeMemoryClass})")
        appendLine()
        appendLine("Сеть: ${network(context)}")
        appendLine()
        display(context, this)
        appendLine()
        appendLine("Аппаратные декодеры:")
        codecs().forEach { appendLine("  $it") }
        appendLine()
        appendLine("Домашний экран по умолчанию: ${if (BootReceiver.isDefaultHome(context)) "TENIR TV" else "другой"}")
        appendLine("Владелец устройства: ${if (DeviceOwner.isDeviceOwner(context)) "да" else "нет"}")
        appendLine("Право «поверх окон» (автозапуск на Android 10+): ${if (Settings.canDrawOverlays(context)) "да" else "нет"}")
    }

    fun network(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return "нет подключения"
        val caps = cm.getNetworkCapabilities(n) ?: return "нет данных о подключении"
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильная"
            else -> "другая"
        }
        val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val down = caps.linkDownstreamBandwidthKbps
        return "$type, интернет ${if (internet) "подтверждён" else "не подтверждён"}" +
            if (down > 0) ", канал ~${down / 1000} Мбит/с" else ""
    }

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let(cm::getNetworkCapabilities) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    @Suppress("DEPRECATION")
    private fun display(context: Context, sb: StringBuilder) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d: Display = wm.defaultDisplay
        val real = DisplayMetrics().also(d::getRealMetrics)
        sb.appendLine("Экран (интерфейс): ${real.widthPixels}×${real.heightPixels}, системная плотность ${real.densityDpi} dpi")
        sb.appendLine("TENIR TV: холст ${Scaling.DESIGN_WIDTH_DP}×${Scaling.DESIGN_HEIGHT_DP} dp, плотность ${Scaling.densityDpiFor(real.widthPixels, real.heightPixels)} dpi")
        val mode = d.mode
        sb.appendLine("Режим: ${mode.physicalWidth}×${mode.physicalHeight} @ ${"%.0f".format(mode.refreshRate)} Гц")
        val modes = d.supportedModes.map { "${it.physicalWidth}×${it.physicalHeight}@${"%.0f".format(it.refreshRate)}" }.distinct()
        sb.appendLine("Поддерживаемые режимы: ${modes.joinToString(", ")}")
        val uhd = d.supportedModes.any { it.physicalWidth >= 3840 }
        sb.appendLine("Вывод 4K UHD: ${if (uhd) "да" else "нет"}")
        run {
            val hdr = d.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
            val names = hdr.map {
                when (it) {
                    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                    Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                    Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                    4 -> "HDR10+"
                    else -> "тип $it"
                }
            }
            sb.appendLine("HDR: ${if (names.isEmpty()) "нет" else names.joinToString(", ")}")
        }
    }

    private val MIMES = linkedMapOf(
        "video/avc" to "H.264",
        "video/hevc" to "HEVC",
        "video/x-vnd.on2.vp9" to "VP9",
        "video/av01" to "AV1",
    )

    fun codecs(): List<String> {
        val infos = try {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder }
        } catch (e: RuntimeException) {
            return listOf("список кодеков недоступен: ${e.message}")
        }
        return MIMES.map { (mime, name) ->
            val hw = infos.filter { info -> info.supportedTypes.any { it.equals(mime, true) } && isHardware(info) }
            if (hw.isEmpty()) return@map "$name: аппаратного декодера нет"
            val uhd = hw.any { supports4k(it, mime) }
            "$name: ${hw.first().name}${if (uhd) ", 4K@30 — да" else ", 4K — нет"}"
        }
    }

    private fun isHardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated
        val n = info.name.lowercase()
        return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw."))
    }

    private fun supports4k(info: MediaCodecInfo, mime: String): Boolean = try {
        val caps = info.getCapabilitiesForType(mime).videoCapabilities
        caps != null && caps.areSizeAndRateSupported(3840, 2160, 30.0)
    } catch (e: RuntimeException) {
        false
    }
}
