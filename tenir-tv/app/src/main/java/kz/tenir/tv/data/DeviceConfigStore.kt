package kz.tenir.tv.data

import android.content.Context
import android.util.Log
import kz.tenir.tv.core.DeviceConfig
import java.io.File

/**
 * Настройки телевизора в приватных SharedPreferences.
 * Массовая установка: положить device.json в /sdcard/Android/data/kz.tenir.tv/files/ (adb push) —
 * при запуске файл применяется и удаляется, чтобы токен не лежал в общей памяти.
 */
class DeviceConfigStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("device", Context.MODE_PRIVATE)

    @Volatile
    var config: DeviceConfig = load()
        private set

    var lastImportMessage: String? = null
        private set

    private fun load(): DeviceConfig {
        val raw = prefs.getString(KEY, null) ?: return DeviceConfig()
        return try {
            DeviceConfig.parse(raw)
        } catch (e: IllegalArgumentException) {
            DeviceConfig()
        }
    }

    fun save(c: DeviceConfig) {
        prefs.edit().putString(KEY, c.toJson()).apply()
        config = c
    }

    /** true — найден и применён новый device.json. */
    fun importProvisioningFile(): Boolean {
        val dir = context.getExternalFilesDir(null) ?: return false
        val f = File(dir, FILE)
        if (!f.exists()) return false
        return try {
            val c = DeviceConfig.parse(f.readText(Charsets.UTF_8), config)
            save(c)
            lastImportMessage = "device.json применён (номер ${c.room.ifEmpty { "—" }})"
            true
        } catch (e: Exception) {
            lastImportMessage = "device.json не применён: ${e.message}"
            Log.w(TAG, lastImportMessage!!)
            false
        } finally {
            if (!f.delete()) Log.w(TAG, "не удалось удалить ${f.path}")
        }
    }

    companion object {
        private const val TAG = "TenirConfig"
        private const val KEY = "config"
        const val FILE = "device.json"
    }
}
