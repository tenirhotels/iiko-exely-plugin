package kz.tenir.tv.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import kz.tenir.tv.ui.home.HomeActivity

/**
 * Автозапуск после включения приставки. Основной путь — TENIR TV выбран домашним экраном (HOME),
 * тогда система сама открывает его после загрузки. Приёмник — страховка для прошивок, где выбор
 * домашнего экрана сбрасывается: на Android 10+ фоновый запуск активности разрешён только с правом
 * «поверх других окон» (adb shell appops set kz.tenir.tv SYSTEM_ALERT_WINDOW allow).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in ACTIONS) return
        DeviceOwner.pinHomeActivity(context)
        if (isDefaultHome(context)) return
        if (Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(context)) {
            Log.i(TAG, "автозапуск пропущен: нет права SYSTEM_ALERT_WINDOW и TENIR TV не домашний экран")
            return
        }
        try {
            context.startActivity(
                Intent(context, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "автозапуск не удался", e)
        }
    }

    companion object {
        private const val TAG = "TenirBoot"
        private val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )

        fun isDefaultHome(context: Context): Boolean {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val ri = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            return ri?.activityInfo?.packageName == context.packageName
        }
    }
}
