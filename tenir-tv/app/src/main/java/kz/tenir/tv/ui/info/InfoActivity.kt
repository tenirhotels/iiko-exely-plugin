package kz.tenir.tv.ui.info

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kz.tenir.tv.R
import kz.tenir.tv.ui.BaseTvActivity
import kz.tenir.tv.ui.Ui

/** Об отеле: текст с сервера, карточки Wi-Fi и рецепции. */
class InfoActivity : BaseTvActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_info)
        val h = app.hotel.hotel
        app.images.load(h.backgroundUrl, findViewById(R.id.background), Ui.placeholder(this))
        findViewById<TextView>(R.id.tagline).apply { text = t(h.tagline); setTextColor(accent) }
        findViewById<TextView>(R.id.title).text = h.name
        findViewById<TextView>(R.id.info).text = t(h.info)
        val cards = findViewById<LinearLayout>(R.id.cards)
        if (h.wifiSsid != null) {
            cards.addView(card(R.drawable.ic_wifi, getString(R.string.wifi_title), listOfNotNull(
                getString(R.string.wifi_network) to h.wifiSsid!!,
                h.wifiPassword?.let { getString(R.string.wifi_password) to it },
            )))
        }
        if (h.phone != null) {
            cards.addView(card(R.drawable.ic_phone, getString(R.string.reception), listOf("" to h.phone!!)))
        }
        findViewById<android.view.View>(R.id.scroll).requestFocus()
    }

    override fun initialFocus(): android.view.View? = findViewById(R.id.scroll)

    private fun card(icon: Int, title: String, lines: List<Pair<String, String>>): LinearLayout {
        val ctx = this
        val pad = Ui.dp(ctx, 22f)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = Ui.rounded(Ui.withAlpha(Ui.color(ctx, R.color.surface_hi), 0.9f), Ui.dp(ctx, 14f).toFloat(),
                Ui.dp(ctx, 1f), Ui.color(ctx, R.color.line))
        }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(ImageView(ctx).apply { setImageResource(icon); setColorFilter(accent) },
            LinearLayout.LayoutParams(Ui.dp(ctx, 22f), Ui.dp(ctx, 22f)).apply { marginEnd = Ui.dp(ctx, 10f) })
        head.addView(TextView(ctx).apply {
            text = title
            textSize = 13f
            letterSpacing = 0.18f
            isAllCaps = true
            setTextColor(accent)
        })
        card.addView(head)
        lines.forEach { (label, value) ->
            if (label.isNotEmpty()) {
                card.addView(TextView(ctx).apply {
                    text = label
                    textSize = 11f
                    setTextColor(Ui.color(ctx, R.color.text_3))
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dp(ctx, 14f) })
            }
            card.addView(TextView(ctx).apply {
                text = value
                textSize = 24f
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dp(ctx, if (label.isEmpty()) 12f else 2f) })
        }
        card.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(ctx, 16f) }
        return card
    }
}
