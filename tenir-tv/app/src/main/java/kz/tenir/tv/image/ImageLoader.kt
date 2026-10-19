package kz.tenir.tv.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import kz.tenir.tv.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Загрузка изображений без сторонних библиотек: память (LRU) → диск (cacheDir/img) → сеть.
 * Декодирование с уменьшением до размера вьюшки — на 4K это главное, чтобы не упереться в память.
 * Схемы: http(s)://, asset:///путь (встроенный демо-контент), file://.
 * Без сети изображения берутся из дискового кеша.
 */
class ImageLoader(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val pool: ExecutorService = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "tenir-img").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }
    private val diskDir = File(app.cacheDir, "img").apply { mkdirs() }
    private val memory = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 5).toInt().coerceAtLeast(16 * 1024),
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    fun load(url: String?, view: ImageView, fallback: Drawable? = null, onDone: ((Boolean) -> Unit)? = null) {
        view.setTag(R.id.tag_image_url, url)
        if (url.isNullOrBlank()) {
            view.setImageDrawable(fallback)
            onDone?.invoke(false)
            return
        }
        if (view.width == 0 || view.height == 0) {
            // размер ещё неизвестен: ждём разметку, чтобы декодировать ровно под вьюшку
            view.post { if (view.getTag(R.id.tag_image_url) == url) startLoad(url, view, fallback, onDone) }
            if (view.drawable == null) view.setImageDrawable(fallback)
            return
        }
        startLoad(url, view, fallback, onDone)
    }

    private fun startLoad(url: String, view: ImageView, fallback: Drawable?, onDone: ((Boolean) -> Unit)?) {
        val dm = view.resources.displayMetrics
        val w = view.width.takeIf { it > 0 } ?: dm.widthPixels
        val h = view.height.takeIf { it > 0 } ?: dm.heightPixels
        val key = "$url@${w}x$h"
        memory.get(key)?.let {
            view.setImageBitmap(it)
            onDone?.invoke(true)
            return
        }
        if (view.drawable == null) view.setImageDrawable(fallback)
        pool.execute {
            val bmp = try {
                decode(url, w, h)
            } catch (e: Exception) {
                Log.w(TAG, "изображение не загружено: $url (${e.message})")
                null
            } catch (e: OutOfMemoryError) {
                memory.evictAll()
                null
            }
            if (bmp != null) memory.put(key, bmp) else Log.w(TAG, "не удалось декодировать: $url")
            main.post {
                if (view.getTag(R.id.tag_image_url) != url) return@post
                if (bmp != null) {
                    // глубина цепочки — не больше одного перехода, иначе старые кадры копятся в памяти
                    val current = view.drawable
                    val from = (current as? TransitionDrawable)?.getDrawable(1) ?: current ?: ColorDrawable(0)
                    val td = TransitionDrawable(arrayOf(from, BitmapDrawable(view.resources, bmp)))
                    td.isCrossFadeEnabled = true
                    view.setImageDrawable(td)
                    td.startTransition(FADE_MS)
                } else if (fallback != null) {
                    view.setImageDrawable(fallback)
                }
                onDone?.invoke(bmp != null)
            }
        }
    }

    /** Заранее скачать в дисковый кеш (баннеры, меню), чтобы контент был доступен без сети. */
    fun prefetch(urls: Collection<String?>) {
        urls.filterNotNull().filter { it.startsWith("http") }.distinct().forEach { url ->
            pool.execute {
                try {
                    diskFile(url)
                } catch (e: IOException) {
                    Log.i(TAG, "prefetch: $url (${e.message})")
                }
            }
        }
    }

    fun clearDisk() {
        memory.evictAll()
        diskDir.listFiles()?.forEach { it.delete() }
    }

    private fun decode(url: String, reqW: Int, reqH: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(url).use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var sample = 1
        while (opts.outWidth / (sample * 2) >= reqW && opts.outHeight / (sample * 2) >= reqH) sample *= 2
        val real = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return open(url).use { BitmapFactory.decodeStream(it, null, real) }
    }

    private fun open(url: String): InputStream = when {
        url.startsWith(ASSET) -> app.assets.open(url.removePrefix(ASSET))
        url.startsWith("file://") -> File(url.removePrefix("file://")).inputStream()
        url.startsWith("http://") || url.startsWith("https://") -> diskFile(url).inputStream()
        else -> throw IOException("неизвестная схема: $url")
    }

    @Synchronized
    private fun lockFor(name: String): Any = locks.getOrPut(name) { Any() }
    private val locks = HashMap<String, Any>()

    private fun diskFile(url: String): File {
        val name = sha1(url)
        val f = File(diskDir, name)
        synchronized(lockFor(name)) {
            if (f.exists() && f.length() > 0) {
                f.setLastModified(System.currentTimeMillis())
                return f
            }
            val tmp = File(diskDir, "$name.part")
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 7000
                conn.readTimeout = 15000
                conn.instanceFollowRedirects = true
                if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
                conn.inputStream.use { input -> FileOutputStream(tmp).use { input.copyTo(it) } }
            } finally {
                conn.disconnect()
            }
            if (!tmp.renameTo(f)) throw IOException("не удалось сохранить в кеш")
            trimDisk()
            return f
        }
    }

    private fun trimDisk() {
        val files = diskDir.listFiles()?.filter { !it.name.endsWith(".part") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_DISK_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            total -= f.length()
            f.delete()
            if (total <= MAX_DISK_BYTES * 3 / 4) break
        }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "TenirImg"
        const val ASSET = "asset:///"
        private const val FADE_MS = 280
        private const val MAX_DISK_BYTES = 150L * 1024 * 1024
    }
}
