package kz.tenir.tv.system

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kz.tenir.tv.ui.home.HomeActivity
import java.util.concurrent.Executor

/**
 * Администратор устройства. Права владельца устройства выдаются один раз при установке:
 *   adb shell dpm set-device-owner kz.tenir.tv/.system.TenirDeviceAdmin
 * Тогда TENIR TV закрепляется как домашний экран и может очищать данные приложений при выезде гостя.
 */
class TenirDeviceAdmin : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        DeviceOwner.pinHomeActivity(context)
    }
}

object DeviceOwner {
    private const val TAG = "TenirOwner"

    fun admin(context: Context) = ComponentName(context, TenirDeviceAdmin::class.java)

    private fun dpm(context: Context) = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun isDeviceOwner(context: Context): Boolean = try {
        dpm(context).isDeviceOwnerApp(context.packageName)
    } catch (e: RuntimeException) {
        false
    }

    /** Закрепить HomeActivity как домашний экран (только владелец устройства). */
    fun pinHomeActivity(context: Context): Boolean {
        if (!isDeviceOwner(context)) return false
        return try {
            val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm(context).addPersistentPreferredActivity(
                admin(context), filter, ComponentName(context, HomeActivity::class.java),
            )
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "не удалось закрепить домашний экран", e)
            false
        }
    }

    /** Перезагрузка приставки по команде облака (только владелец устройства). */
    fun reboot(context: Context): Boolean {
        if (!isDeviceOwner(context)) return false
        return try {
            dpm(context).reboot(admin(context))
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "перезагрузка не удалась", e)
            false
        }
    }

    /**
     * Очистка данных приложений предыдущего гостя (вход в Netflix, YouTube и т. п.).
     * Android 9+ и права владельца устройства; иначе возвращает причину отказа.
     */
    fun clearAppData(context: Context, packages: List<String>, done: (String) -> Unit) {
        if (packages.isEmpty()) return
        if (Build.VERSION.SDK_INT < 28) {
            done("очистка приложений требует Android 9+")
            return
        }
        if (!isDeviceOwner(context)) {
            done("очистка приложений: TENIR TV не владелец устройства")
            return
        }
        val direct = Executor { it.run() }
        for (pkg in packages) {
            try {
                dpm(context).clearApplicationUserData(admin(context), pkg, direct) { p, ok ->
                    done("$p: ${if (ok) "данные очищены" else "очистка не удалась"}")
                }
            } catch (e: RuntimeException) {
                done("$pkg: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
}
