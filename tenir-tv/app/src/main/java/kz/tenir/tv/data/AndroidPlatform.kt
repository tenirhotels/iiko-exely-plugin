package kz.tenir.tv.data

import android.content.Context
import kz.tenir.tv.core.util.HttpResult
import kz.tenir.tv.core.util.HttpTransport
import kz.tenir.tv.core.util.TextStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** HTTP без сторонних библиотек: таймауты короткие, чтобы опрос не висел при падении сети. */
class AndroidHttpTransport(
    private val userAgent: String,
    private val connectTimeoutMs: Int = 7000,
    private val readTimeoutMs: Int = 10000,
    private val maxBodyBytes: Int = 4 * 1024 * 1024,
) : HttpTransport {

    override fun get(url: String, headers: Map<String, String>): HttpResult = send("GET", url, null, headers)

    override fun post(url: String, json: String, headers: Map<String, String>): HttpResult =
        send("POST", url, json, headers)

    private fun send(method: String, url: String, body: String?, headers: Map<String, String>): HttpResult {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = true
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", userAgent)
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.use { readLimited(it, maxBodyBytes) }?.toString(Charsets.UTF_8)
            val out = HashMap<String, String>()
            conn.headerFields?.forEach { (k, v) -> if (k != null && !v.isNullOrEmpty()) out[k] = v[0] }
            return HttpResult(code, text, out)
        } catch (e: IOException) {
            throw e
        } catch (e: RuntimeException) {
            // некорректный URL из настроек, SecurityException без разрешения и т. п. — как сетевая ошибка
            throw IOException(e.message ?: e.javaClass.simpleName, e)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        fun readLimited(input: InputStream, limit: Int): ByteArray {
            val buf = ByteArray(16 * 1024)
            val out = ByteArrayOutputStream()
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > limit) throw IOException("ответ больше ${limit / 1024} КБ")
            }
            return out.toByteArray()
        }
    }
}

/** Строки в файлах приложения (filesDir/store). Запись атомарная: временный файл + rename. */
class FileTextStore(context: Context, dirName: String = "store") : TextStore {
    private val dir = File(context.filesDir, dirName).apply { mkdirs() }

    private fun file(key: String) = File(dir, key.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    override fun read(key: String): String? = synchronized(this) {
        val f = file(key)
        if (!f.exists()) null else try {
            f.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            null
        }
    }

    override fun write(key: String, value: String) = synchronized(this) {
        val f = file(key)
        val tmp = File(dir, f.name + ".tmp")
        try {
            tmp.writeText(value, Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (e: IOException) {
            tmp.delete()
        }
        Unit
    }

    override fun delete(key: String) = synchronized(this) {
        file(key).delete()
        Unit
    }
}
