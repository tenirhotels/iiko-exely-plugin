package kz.tenir.tv.core.content

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class ContentException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Разбор content.json. Формат описан в docs/content-format.md. Ошибочные элементы списков пропускаются,
 * без hotel.name весь документ отвергается (тогда остаётся прошлый кеш).
 */
object ContentParser {

    val DEFAULT_TILES = listOf(Tiles.RESTAURANT, "netflix", "youtube", Tiles.NEWS, Tiles.APPS, Tiles.INFO)

    val DEFAULT_APPS = listOf(
        AppTile("netflix", "Netflix", listOf("com.netflix.ninja", "com.netflix.mediaclient"), "#E50914"),
        AppTile(
            "youtube", "YouTube",
            listOf("com.google.android.youtube.tv", "com.google.android.youtube.googletv",
                "com.amazon.firetv.youtube", "com.google.android.youtube"),
            "#FF0033",
        ),
    )

    @Throws(ContentException::class)
    fun parse(json: String): HotelContent {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw ContentException("content.json: не JSON", e)
        }
        val hotelJson = root.optJSONObject("hotel") ?: throw ContentException("content.json: нет блока hotel")
        val name = hotelJson.optString("name").trim()
        if (name.isEmpty()) throw ContentException("content.json: пустое hotel.name")

        val languages = root.optJSONArray("languages").strings().ifEmpty { listOf("ru", "en", "kk") }
        val apps = root.optJSONArray("apps")?.let { arr -> arr.objects().mapNotNull(::app) } ?: DEFAULT_APPS
        return HotelContent(
            version = root.optLong("version", 0),
            hotel = HotelInfo(
                name = name,
                tagline = text(hotelJson, "tagline"),
                logoUrl = hotelJson.str("logo"),
                backgroundUrl = hotelJson.str("background"),
                accentColor = hotelJson.str("accentColor")?.takeIf { COLOR.matches(it) },
                welcome = text(hotelJson, "welcome"),
                phone = hotelJson.str("phone"),
                wifiSsid = hotelJson.optJSONObject("wifi")?.str("ssid"),
                wifiPassword = hotelJson.optJSONObject("wifi")?.str("password"),
                info = text(hotelJson, "info"),
            ),
            defaultLanguage = root.str("language")?.lowercase() ?: languages.first(),
            languages = languages,
            display = display(root.optJSONObject("display")),
            homeTiles = root.optJSONObject("home")?.optJSONArray("tiles").strings().ifEmpty { DEFAULT_TILES },
            banners = root.optJSONArray("banners").objects().mapNotNull(::banner),
            news = root.optJSONArray("news").objects().mapNotNull(::news),
            restaurant = root.optJSONObject("restaurant")?.let(::restaurant),
            apps = apps,
            weather = root.optJSONObject("weather")?.let(::weather),
            hub = hub(root.optJSONObject("hub")),
        )
    }

    private val COLOR = Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

    private fun display(o: JSONObject?): DisplaySettings {
        val d = DisplaySettings()
        if (o == null) return d
        return DisplaySettings(
            showClock = o.optBoolean("showClock", d.showClock),
            showWeather = o.optBoolean("showWeather", d.showWeather),
            showGuestName = o.optBoolean("showGuestName", d.showGuestName),
            guestNameFormat = NameFormat.parse(o.str("guestNameFormat")),
            showRoomNumber = o.optBoolean("showRoomNumber", d.showRoomNumber),
            bannerIntervalSeconds = o.optInt("bannerIntervalSeconds", d.bannerIntervalSeconds).coerceIn(3, 120),
            use24h = o.optBoolean("use24h", d.use24h),
            timezone = o.str("timezone") ?: d.timezone,
            headlineSans = o.str("headlineFont") == "sans",
            backgroundDim = o.optInt("backgroundDim", d.backgroundDim).coerceIn(0, 100),
        )
    }

    private fun hub(o: JSONObject?): HubSettings {
        val d = HubSettings()
        if (o == null) return d
        return HubSettings(
            pollSeconds = o.optInt("pollSeconds", d.pollSeconds).coerceIn(15, 3600),
            maxAgeMinutes = o.optInt("maxAgeMinutes", d.maxAgeMinutes).coerceIn(1, 24 * 60),
            checkoutHour = o.optInt("checkoutHour", d.checkoutHour).coerceIn(0, 24),
            clearAppsOnCheckout = o.optJSONArray("clearAppsOnCheckout").strings(),
        )
    }

    private fun banner(o: JSONObject): Banner? {
        val title = text(o, "title")
        val image = o.str("image")
        if (title.isBlank && image == null) return null
        return Banner(
            id = o.str("id") ?: title.get(LocalizedText.FALLBACK),
            title = title,
            subtitle = text(o, "subtitle"),
            label = text(o, "label"),
            imageUrl = image,
            action = action(o.optJSONObject("action")),
        )
    }

    private fun action(o: JSONObject?): Action =
        if (o == null) Action.NONE else Action(ActionType.parse(o.str("type")), o.str("target"))

    private fun news(o: JSONObject): NewsItem? {
        val title = text(o, "title")
        if (title.isBlank) return null
        return NewsItem(
            id = o.str("id") ?: title.get(LocalizedText.FALLBACK),
            title = title,
            summary = text(o, "summary"),
            body = text(o, "body"),
            imageUrl = o.str("image"),
            date = o.str("date"),
        )
    }

    private fun restaurant(o: JSONObject): Restaurant? {
        val name = text(o, "name")
        if (name.isBlank) return null
        return Restaurant(
            name = name,
            description = text(o, "description"),
            hours = text(o, "hours"),
            orderInfo = text(o, "orderInfo"),
            imageUrl = o.str("image"),
            currency = o.str("currency") ?: "₸",
            categories = o.optJSONArray("categories").objects().mapNotNull(::category),
        )
    }

    private fun category(o: JSONObject): MenuCategory? {
        val name = text(o, "name")
        if (name.isBlank) return null
        val items = o.optJSONArray("items").objects().mapNotNull(::menuItem)
        return MenuCategory(o.str("id") ?: name.get(LocalizedText.FALLBACK), name, items)
    }

    private fun menuItem(o: JSONObject): MenuItem? {
        val name = text(o, "name")
        if (name.isBlank) return null
        val price = if (o.has("price") && !o.isNull("price")) o.optDouble("price").takeIf { !it.isNaN() } else null
        return MenuItem(
            id = o.str("id") ?: name.get(LocalizedText.FALLBACK),
            name = name,
            description = text(o, "description"),
            price = price,
            imageUrl = o.str("image"),
            tags = o.optJSONArray("tags").strings(),
        )
    }

    private fun app(o: JSONObject): AppTile? {
        val id = o.str("id") ?: return null
        val packages = o.optJSONArray("packages").strings()
        if (packages.isEmpty()) return null
        return AppTile(id, o.str("title") ?: id, packages, o.str("color")?.takeIf { COLOR.matches(it) })
    }

    private fun weather(o: JSONObject): WeatherLocation? {
        if (!o.has("latitude") || !o.has("longitude")) return null
        val lat = o.optDouble("latitude")
        val lon = o.optDouble("longitude")
        if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return WeatherLocation(lat, lon, text(o, "label"))
    }

    /** Строка или объект языков. */
    fun text(o: JSONObject, key: String): LocalizedText {
        if (!o.has(key) || o.isNull(key)) return LocalizedText.EMPTY
        val v = o.opt(key)
        return when (v) {
            is JSONObject -> {
                val map = LinkedHashMap<String, String>()
                val keys = v.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val s = v.optString(k)
                    if (s.isNotBlank()) map[k.lowercase()] = s
                }
                LocalizedText(map)
            }
            is String -> if (v.isBlank()) LocalizedText.EMPTY else LocalizedText.of(v)
            else -> LocalizedText.of(v.toString())
        }
    }

    private fun JSONObject.str(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).trim().ifEmpty { null }

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        val out = ArrayList<JSONObject>(length())
        for (i in 0 until length()) optJSONObject(i)?.let(out::add)
        return out
    }

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            val s = optString(i, "").trim()
            if (s.isNotEmpty() && s != "null") out.add(s)
        }
        return out
    }
}
