package kz.tenir.tv.ui.service

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import kz.tenir.tv.R
import kz.tenir.tv.core.DeviceConfig
import kz.tenir.tv.core.guest.GuestState
import kz.tenir.tv.core.guest.HubClient
import kz.tenir.tv.core.guest.HubException
import kz.tenir.tv.data.AndroidHttpTransport
import kz.tenir.tv.system.Diagnostics
import kz.tenir.tv.ui.BaseTvActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Сервисное меню для инженера (вход: ↑ ↑ ↓ ↓ ← → ← → на главном экране, затем PIN).
 * Настройки подключения к Hotel Hub, проверка связи, диагностика приставки. Имена гостей здесь не показываются.
 */
class ServiceActivity : BaseTvActivity() {
    private var diagnostics: String = "…"
    private var hubCheck: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_service)
        val pin = findViewById<EditText>(R.id.pin)
        pin.requestFocus()
        val submit = {
            if (SystemClock.elapsedRealtime() < lockedUntil) {
                notice(getString(R.string.service_pin_wrong))
            } else if (pin.text.toString() == app.deviceConfig.config.servicePin) {
                failures = 0
                openSettings()
            } else {
                failures++
                if (failures >= 5) {
                    lockedUntil = SystemClock.elapsedRealtime() + 60_000
                    failures = 0
                }
                pin.setText("")
                notice(getString(R.string.service_pin_wrong))
            }
        }
        findViewById<View>(R.id.pinOk).setOnClickListener { submit() }
        pin.setOnEditorActionListener { _, _, _ -> submit(); true }
    }

    private fun openSettings() {
        findViewById<View>(R.id.pinPanel).visibility = View.GONE
        findViewById<View>(R.id.settingsPanel).visibility = View.VISIBLE
        fill(app.deviceConfig.config)
        findViewById<TextView>(R.id.version).text = "TENIR TV ${app.version} · ${packageName}"
        findViewById<View>(R.id.f_room).requestFocus()

        findViewById<View>(R.id.b_save).setOnClickListener {
            app.saveDeviceConfig(read())
            notice(getString(R.string.service_saved))
            renderStatus()
        }
        findViewById<View>(R.id.b_test).setOnClickListener { testHub(read()) }
        findViewById<View>(R.id.b_reload).setOnClickListener {
            app.refreshAll()
            findViewById<View>(R.id.status).postDelayed({ renderStatus() }, 3000)
        }
        findViewById<View>(R.id.b_home).setOnClickListener { openSystem(Settings.ACTION_HOME_SETTINGS) }
        findViewById<View>(R.id.b_settings).setOnClickListener { openSystem(Settings.ACTION_SETTINGS) }
        findViewById<View>(R.id.b_reset).setOnClickListener {
            app.resetContentCache()
            notice(getString(R.string.service_cache_cleared))
            renderStatus()
        }
        findViewById<View>(R.id.b_close).setOnClickListener { finish() }
        renderStatus()
        Thread {
            val d = try {
                Diagnostics.report(applicationContext)
            } catch (e: Exception) {
                "диагностика не удалась: ${e.message}"
            }
            runOnUiThread {
                diagnostics = d
                renderStatus()
            }
        }.start()
    }

    override fun initialFocus(): View? =
        if (findViewById<View>(R.id.settingsPanel).visibility == View.VISIBLE) findViewById(R.id.f_room) else findViewById(R.id.pin)

    private fun field(id: Int) = findViewById<EditText>(id)

    private fun fill(c: DeviceConfig) {
        field(R.id.f_room).setText(c.room)
        field(R.id.f_hubUrl).setText(c.hubUrl)
        field(R.id.f_propertyCode).setText(c.propertyCode)
        field(R.id.f_hubToken).setText(c.hubToken)
        field(R.id.f_contentUrl).setText(c.contentUrl)
        field(R.id.f_language).setText(c.language)
        field(R.id.f_pin).setText("")
        findViewById<CheckBox>(R.id.f_demo).isChecked = c.demoMode
    }

    private fun read(): DeviceConfig {
        val old = app.deviceConfig.config
        fun v(id: Int) = field(id).text.toString().trim()
        return DeviceConfig(
            room = v(R.id.f_room),
            hubUrl = v(R.id.f_hubUrl),
            propertyCode = v(R.id.f_propertyCode),
            hubToken = v(R.id.f_hubToken),
            contentUrl = v(R.id.f_contentUrl),
            demoMode = findViewById<CheckBox>(R.id.f_demo).isChecked,
            servicePin = v(R.id.f_pin).ifEmpty { old.servicePin },
            language = v(R.id.f_language).lowercase(Locale.US),
        )
    }

    private fun openSystem(action: String) {
        try {
            startActivity(Intent(action))
        } catch (e: RuntimeException) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: RuntimeException) {
                notice(e2.message ?: "Настройки недоступны")
            }
        }
    }

    /** Проверка без сохранения: доступность хаба, токен, номер комнаты, право guests. */
    private fun testHub(c: DeviceConfig) {
        val conn = c.hubConnection
        if (conn == null || c.room.isBlank()) {
            hubCheck = "Проверка хаба: заполните адрес, код отеля, токен и номер комнаты"
            renderStatus()
            return
        }
        hubCheck = "Проверка хаба…"
        renderStatus()
        Thread {
            val client = HubClient(AndroidHttpTransport("TENIR-TV/${app.version}"), conn)
            val result = try {
                val rooms = client.rooms()
                val row = rooms.firstOrNull { it.room.equals(c.room, true) || it.roomId == c.room }
                buildString {
                    append("Хаб доступен, токен принят, номеров: ${rooms.size}\n")
                    if (row == null) {
                        append("Номер «${c.room}» в хабе НЕ найден. Примеры: ${rooms.take(8).joinToString { it.room }}")
                    } else {
                        append("Номер ${row.room} (PMS ${row.roomId ?: "—"}): статус ${row.status}")
                        if (row.status == "checked_in" && row.stayId != null) {
                            val guests = try {
                                "гостей в стоянке: ${client.guests(row.stayId!!).size} (имена скрыты)"
                            } catch (e: HubException) {
                                "имена гостей недоступны: ${e.message}"
                            }
                            append("\n$guests")
                        }
                    }
                }
            } catch (e: HubException) {
                "Хаб: ${e.message}"
            }
            runOnUiThread {
                hubCheck = result
                renderStatus()
            }
        }.start()
    }

    private fun renderStatus() {
        val fmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US)
        val c = app.deviceConfig.config
        val snap = app.content.value
        val st = app.sessionStatus
        val guest = when (val g = app.guest.value) {
            is GuestState.Present -> "проживание подтверждено, номер ${g.room} (имя скрыто)"
            GuestState.Vacant -> "номер свободен / гость не заселён"
            GuestState.Unknown -> "нет подтверждённых данных"
        }
        val text = buildString {
            appendLine("[${getString(R.string.service_status)}]")
            appendLine("Режим: ${if (c.demoMode) "демо-гость" else if (c.isHubReady) "Hotel Hub" else "хаб не настроен"}")
            appendLine("Номер: ${c.room.ifEmpty { "—" }}, хаб: ${c.hubUrl.ifEmpty { "—" }}, отель: ${c.propertyCode.ifEmpty { "—" }}")
            appendLine("Токен: ${if (c.hubToken.isEmpty()) "—" else "•••• ${c.hubToken.takeLast(4)}"}")
            appendLine("Гость: $guest")
            appendLine("Последний ответ хаба: ${if (st.lastSuccessMillis > 0) fmt.format(Date(st.lastSuccessMillis)) else "—"}" +
                (st.hubStatus?.let { ", статус номера $it" } ?: "") +
                (if (st.roomFound == false) ", НОМЕР НЕ НАЙДЕН" else ""))
            st.lastError?.let { appendLine("Ошибка хаба: $it") }
            if (hubCheck.isNotEmpty()) appendLine(hubCheck)
            appendLine("Контент: ${snap.source}, версия ${snap.content.version}" +
                (if (snap.savedAtMillis > 0) ", загружен ${fmt.format(Date(snap.savedAtMillis))}" else "") +
                ", адрес ${c.contentUrl.ifEmpty { "не задан (встроенный)" }}")
            appendLine("Язык: ${app.language}, часовой пояс: ${app.timeZone.id}")
            appendLine()
            appendLine("[${getString(R.string.service_diag)}]")
            appendLine(diagnostics)
            appendLine("[${getString(R.string.service_events)}]")
            app.events().forEach { appendLine(it) }
        }
        findViewById<TextView>(R.id.status).text = text
    }

    companion object {
        private var failures = 0
        private var lockedUntil = 0L
    }
}
