package kz.tenir.tv.core.content

import kz.tenir.tv.core.util.Clock
import kz.tenir.tv.core.util.HttpTransport
import kz.tenir.tv.core.util.TextStore
import java.io.IOException

enum class ContentSource { BUNDLED, CACHE, NETWORK }

class ContentSnapshot(val content: HotelContent, val source: ContentSource, val savedAtMillis: Long)

sealed class RefreshResult {
    class Updated(val snapshot: ContentSnapshot) : RefreshResult()
    object NotModified : RefreshResult()
    object NotConfigured : RefreshResult()
    class Failed(val reason: String) : RefreshResult()
}

/**
 * Публичный контент: встроенный демо-набор → кеш с прошлой загрузки → свежая версия с сервера.
 * Персональных данных здесь нет, поэтому кеш хранится на диске без ограничения срока.
 * Сломанный ответ сервера не затирает рабочий кеш.
 */
class ContentRepository(
    private val http: HttpTransport,
    private val store: TextStore,
    private val clock: Clock,
    private val bundled: () -> String,
) {
    @Volatile
    var current: ContentSnapshot? = null
        private set

    /** Без сети, быстро: для первого кадра. */
    fun loadLocal(): ContentSnapshot {
        val cached = store.read(KEY_BODY)
        if (cached != null) {
            try {
                val snap = ContentSnapshot(
                    ContentParser.parse(cached), ContentSource.CACHE, store.read(KEY_SAVED)?.toLongOrNull() ?: 0L,
                )
                current = snap
                return snap
            } catch (_: ContentException) {
                store.delete(KEY_BODY) // испорченный кеш: возвращаемся к встроенному
                store.delete(KEY_ETAG)
            }
        }
        val snap = ContentSnapshot(ContentParser.parse(bundled()), ContentSource.BUNDLED, 0L)
        current = snap
        return snap
    }

    /** Блокирующий вызов: только из фонового потока. */
    fun refresh(url: String?): RefreshResult {
        if (url.isNullOrBlank()) return RefreshResult.NotConfigured
        val headers = HashMap<String, String>()
        if (store.read(KEY_BODY) != null && store.read(KEY_URL) == url) {
            store.read(KEY_ETAG)?.let { headers["If-None-Match"] = it }
        }
        val res = try {
            http.get(url, headers)
        } catch (e: IOException) {
            return RefreshResult.Failed("сеть: ${e.message ?: e.javaClass.simpleName}")
        }
        if (res.code == 304) return RefreshResult.NotModified
        if (!res.isSuccess || res.body == null) return RefreshResult.Failed("HTTP ${res.code}")
        val content = try {
            ContentParser.parse(res.body)
        } catch (e: ContentException) {
            return RefreshResult.Failed(e.message ?: "ошибка формата")
        }
        val now = clock.nowMillis()
        store.write(KEY_BODY, res.body)
        store.write(KEY_SAVED, now.toString())
        store.write(KEY_URL, url)
        val etag = res.header("ETag")
        if (etag != null) store.write(KEY_ETAG, etag) else store.delete(KEY_ETAG)
        val snap = ContentSnapshot(content, ContentSource.NETWORK, now)
        current = snap
        return RefreshResult.Updated(snap)
    }

    fun clearCache() {
        listOf(KEY_BODY, KEY_SAVED, KEY_ETAG, KEY_URL).forEach(store::delete)
    }

    companion object {
        const val KEY_BODY = "content.json"
        const val KEY_SAVED = "content.saved"
        const val KEY_ETAG = "content.etag"
        const val KEY_URL = "content.url"
    }
}
