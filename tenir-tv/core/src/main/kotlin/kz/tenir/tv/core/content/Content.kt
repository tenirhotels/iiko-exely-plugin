package kz.tenir.tv.core.content

/**
 * Текст на нескольких языках: в JSON — строка (одна на все языки) или объект {"ru": "...", "en": "..."}.
 */
class LocalizedText(private val values: Map<String, String>) {

    fun get(lang: String): String =
        values[lang] ?: values[ANY] ?: values[FALLBACK] ?: values["en"] ?: values.values.firstOrNull() ?: ""

    val isBlank: Boolean get() = values.values.all { it.isBlank() }

    override fun equals(other: Any?) = other is LocalizedText && other.values == values
    override fun hashCode() = values.hashCode()
    override fun toString() = values.toString()

    companion object {
        const val ANY = "*"
        const val FALLBACK = "ru"
        val EMPTY = LocalizedText(emptyMap())
        fun of(text: String) = LocalizedText(mapOf(ANY to text))
    }
}

/** Публичный контент главного экрана. Управляется с сервера, кешируется на устройстве. */
data class HotelContent(
    val version: Long,
    val hotel: HotelInfo,
    val defaultLanguage: String,
    val languages: List<String>,
    val display: DisplaySettings,
    val homeTiles: List<String>,
    val banners: List<Banner>,
    val news: List<NewsItem>,
    val restaurant: Restaurant?,
    val apps: List<AppTile>,
    val weather: WeatherLocation?,
    val hub: HubSettings,
) {
    fun newsById(id: String?): NewsItem? = news.firstOrNull { it.id == id }
    fun appById(id: String?): AppTile? = apps.firstOrNull { it.id == id }
}

data class HotelInfo(
    val name: String,
    val tagline: LocalizedText,
    val logoUrl: String?,
    val backgroundUrl: String?,
    val accentColor: String?,
    val welcome: LocalizedText,
    val phone: String?,
    val wifiSsid: String?,
    val wifiPassword: String?,
    val info: LocalizedText,
)

enum class NameFormat { FIRST_NAME, FULL_NAME, NONE;

    companion object {
        fun parse(s: String?): NameFormat = when (s?.lowercase()) {
            "full", "full_name" -> FULL_NAME
            "none", "hidden", "off" -> NONE
            else -> FIRST_NAME
        }
    }
}

data class DisplaySettings(
    val showClock: Boolean = true,
    val showWeather: Boolean = true,
    val showGuestName: Boolean = true,
    val guestNameFormat: NameFormat = NameFormat.FIRST_NAME,
    val showRoomNumber: Boolean = true,
    val bannerIntervalSeconds: Int = 9,
    val use24h: Boolean = true,
    val timezone: String = "Asia/Almaty",
    /** Заголовки без засечек (по умолчанию — с засечками). */
    val headlineSans: Boolean = false,
    /** Затемнение фона главного экрана, 0–100 (70 — как в макете). */
    val backgroundDim: Int = 70,
)

enum class ActionType { NONE, NEWS, RESTAURANT, APP, APPS, INFO;

    companion object {
        fun parse(s: String?): ActionType =
            values().firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) } ?: NONE
    }
}

data class Action(val type: ActionType, val target: String? = null) {
    companion object { val NONE = Action(ActionType.NONE) }
}

data class Banner(
    val id: String,
    val title: LocalizedText,
    val subtitle: LocalizedText,
    val label: LocalizedText,
    val imageUrl: String?,
    val action: Action,
)

data class NewsItem(
    val id: String,
    val title: LocalizedText,
    val summary: LocalizedText,
    val body: LocalizedText,
    val imageUrl: String?,
    val date: String?,
)

data class Restaurant(
    val name: LocalizedText,
    val description: LocalizedText,
    val hours: LocalizedText,
    val orderInfo: LocalizedText,
    val imageUrl: String?,
    val currency: String,
    val categories: List<MenuCategory>,
)

data class MenuCategory(val id: String, val name: LocalizedText, val items: List<MenuItem>)

data class MenuItem(
    val id: String,
    val name: LocalizedText,
    val description: LocalizedText,
    val price: Double?,
    val imageUrl: String?,
    val tags: List<String>,
)

/** Плитка внешнего приложения: показывается, только если один из пакетов установлен и запускается. */
data class AppTile(
    val id: String,
    val title: String,
    val packages: List<String>,
    val color: String?,
)

data class WeatherLocation(val latitude: Double, val longitude: Double, val label: LocalizedText)

/** Поведение интеграции с Hotel Hub, задаётся с сервера контента. */
data class HubSettings(
    val pollSeconds: Int = 60,
    /** Сколько минут после последнего подтверждения хабом ещё можно показывать имя гостя. */
    val maxAgeMinutes: Int = 10,
    /** Час дня выезда, после которого персональные данные скрываются (как в Hotel Hub, 12:00; 24 — весь день). */
    val checkoutHour: Int = 12,
    /** Пакеты, данные которых очищаются при выезде гостя (нужен режим владельца устройства). */
    val clearAppsOnCheckout: List<String> = emptyList(),
)

object Tiles {
    const val RESTAURANT = "restaurant"
    const val NEWS = "news"
    const val APPS = "apps"
    const val INFO = "info"
}
