package kz.tenir.tv.core

import kz.tenir.tv.core.guest.HubConnection
import kz.tenir.tv.core.util.nullIfBlank
import org.json.JSONException
import org.json.JSONObject

/**
 * Настройки конкретного телевизора (не контент): адрес хаба, токен, номер комнаты, адрес контента.
 * Задаются в сервисном меню или файлом device.json при установке.
 */
data class DeviceConfig(
    val room: String = "",
    val hubUrl: String = "",
    val propertyCode: String = "",
    val hubToken: String = "",
    val contentUrl: String = "",
    /** Демо-данные гостя, пока интеграция с хабом не подтверждена. */
    val demoMode: Boolean = true,
    val servicePin: String = DEFAULT_PIN,
    /** Пусто — язык из контента. */
    val language: String = "",
) {
    val hubConnection: HubConnection?
        get() = if (hubUrl.isNotBlank() && propertyCode.isNotBlank() && hubToken.isNotBlank()) {
            HubConnection(hubUrl.trim(), propertyCode.trim(), hubToken.trim())
        } else {
            null
        }

    val isHubReady: Boolean get() = hubConnection != null && room.isNotBlank()

    fun toJson(): String = JSONObject()
        .put("room", room).put("hubUrl", hubUrl).put("propertyCode", propertyCode).put("hubToken", hubToken)
        .put("contentUrl", contentUrl).put("demoMode", demoMode).put("servicePin", servicePin)
        .put("language", language)
        .toString(2)

    companion object {
        const val DEFAULT_PIN = "0000"

        /** Пустые и отсутствующие поля берутся из [base]. */
        fun parse(json: String, base: DeviceConfig = DeviceConfig()): DeviceConfig {
            val o = try {
                JSONObject(json)
            } catch (e: JSONException) {
                throw IllegalArgumentException("device.json: не JSON", e)
            }
            fun s(k: String, d: String) = if (o.isNull(k)) d else o.optString(k).nullIfBlank() ?: d
            return DeviceConfig(
                room = s("room", base.room),
                hubUrl = s("hubUrl", base.hubUrl),
                propertyCode = s("propertyCode", base.propertyCode),
                hubToken = s("hubToken", base.hubToken),
                contentUrl = s("contentUrl", base.contentUrl),
                demoMode = if (o.has("demoMode")) o.optBoolean("demoMode", base.demoMode) else base.demoMode,
                servicePin = s("servicePin", base.servicePin),
                language = s("language", base.language),
            )
        }
    }
}
