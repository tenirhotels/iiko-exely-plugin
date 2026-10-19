package kz.tenir.tv.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable

data class InstalledApp(val packageName: String, val label: String, val leanback: Boolean)

sealed class LaunchResult {
    object Started : LaunchResult()
    object NotInstalled : LaunchResult()
    class Failed(val reason: String) : LaunchResult()
}

/**
 * Проверка и запуск внешних приложений штатными средствами Android:
 * сначала точка запуска Android TV (LEANBACK_LAUNCHER), затем обычная (LAUNCHER).
 * Видимость пакетов на Android 11+ обеспечивает блок <queries> в манифесте.
 */
class AppLauncher(private val context: Context) {
    private val pm: PackageManager = context.packageManager

    fun launchIntent(pkg: String): Intent? = try {
        pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
    } catch (e: RuntimeException) {
        null
    }

    fun isLaunchable(pkg: String): Boolean {
        if (!isEnabled(pkg)) return false
        return launchIntent(pkg) != null
    }

    private fun isEnabled(pkg: String): Boolean = try {
        pm.getApplicationInfo(pkg, 0).enabled
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun launch(pkg: String): LaunchResult {
        if (!isEnabled(pkg)) return LaunchResult.NotInstalled
        val intent = launchIntent(pkg) ?: return LaunchResult.NotInstalled
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(intent)
            LaunchResult.Started
        } catch (e: ActivityNotFoundException) {
            LaunchResult.NotInstalled
        } catch (e: SecurityException) {
            LaunchResult.Failed(e.message ?: "нет доступа")
        } catch (e: RuntimeException) {
            LaunchResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Баннер Android TV (320×180) или иконка приложения — берутся из самого установленного пакета. */
    fun banner(pkg: String): Drawable? = try {
        val intent = launchIntent(pkg)
        val activityBanner = intent?.let { pm.getActivityBanner(it) }
        activityBanner ?: pm.getApplicationBanner(pkg)
    } catch (e: Exception) {
        null
    }

    fun icon(pkg: String): Drawable? = try {
        pm.getApplicationIcon(pkg)
    } catch (e: Exception) {
        null
    }

    /** Все запускаемые приложения, кроме самого лаунчера. */
    fun installedApps(): List<InstalledApp> {
        val result = LinkedHashMap<String, InstalledApp>()
        fun collect(category: String, leanback: Boolean) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(category)
            val list: List<ResolveInfo> = try {
                pm.queryIntentActivities(q, 0)
            } catch (e: RuntimeException) {
                emptyList()
            }
            for (ri in list) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg == context.packageName || result.containsKey(pkg)) continue
                val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { pkg }
                result[pkg] = InstalledApp(pkg, label, leanback)
            }
        }
        collect(Intent.CATEGORY_LEANBACK_LAUNCHER, true)
        collect(Intent.CATEGORY_LAUNCHER, false)
        return result.values.sortedWith(compareBy<InstalledApp> { !it.leanback }.thenBy { it.label.lowercase() })
    }
}
