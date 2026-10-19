package kz.tenir.tv.ui.news

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kz.tenir.tv.R
import kz.tenir.tv.core.content.NewsItem
import kz.tenir.tv.ui.BaseTvActivity
import kz.tenir.tv.ui.Focus
import kz.tenir.tv.ui.Ui
import java.text.SimpleDateFormat
import java.util.Locale

/** Новости отеля: список слева, статья справа. Вправо — прокрутка текста статьи. */
class NewsActivity : BaseTvActivity() {
    private val rows = ArrayList<View>()
    private var startRow = 0

    override fun initialFocus(): View? = rows.getOrNull(startRow)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_news)
        val news = app.hotel.news
        if (news.isEmpty()) {
            findViewById<View>(R.id.empty).visibility = View.VISIBLE
            findViewById<View>(R.id.detailScroll).visibility = View.GONE
            return
        }
        val list = findViewById<LinearLayout>(R.id.list)
        news.forEach { n -> list.addView(row(n)) }
        val start = news.indexOfFirst { it.id == intent.getStringExtra(EXTRA_ID) }.coerceAtLeast(0)
        startRow = start
        show(news[start])
        rows[start].requestFocus()
        findViewById<TextView>(R.id.date).setTextColor(accent)
        val image = findViewById<View>(R.id.image)
        Ui.clipRounded(image, Ui.dp(this, 14f).toFloat())
    }

    private fun row(n: NewsItem): View {
        val ctx = this
        val p = Ui.dp(ctx, 14f)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p, Ui.dp(ctx, 12f), p, Ui.dp(ctx, 12f))
            id = View.generateViewId()
            nextFocusRightId = R.id.detailScroll
        }
        col.addView(TextView(ctx).apply {
            text = formatDate(n.date)
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(accent)
            visibility = if (n.date == null) View.GONE else View.VISIBLE
        })
        col.addView(TextView(ctx).apply {
            text = t(n.title)
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("serif", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(ctx, 4f) })
        col.addView(TextView(ctx).apply {
            text = t(n.summary)
            textSize = 13f
            setTextColor(Ui.color(ctx, R.color.text_3))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(ctx, 4f) })
        Focus.applyRow(col, accent, Ui.dp(ctx, 10f).toFloat()) { has -> if (has) show(n) }
        rows += col
        return col
    }

    private fun show(n: NewsItem) {
        app.images.load(n.imageUrl, findViewById(R.id.image), Ui.placeholder(this, n.id.hashCode()))
        findViewById<View>(R.id.image).visibility = if (n.imageUrl == null) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.date).text = formatDate(n.date)
        findViewById<TextView>(R.id.title).text = t(n.title)
        val body = t(n.body).ifBlank { t(n.summary) }
        findViewById<TextView>(R.id.body).text = body
        findViewById<ScrollView>(R.id.detailScroll).scrollTo(0, 0)
    }

    private fun formatDate(iso: String?): String {
        if (iso == null) return ""
        return try {
            val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso.take(10)) ?: return iso
            SimpleDateFormat("d MMMM yyyy", Locale(lang)).format(d)
        } catch (e: Exception) {
            iso
        }
    }

    companion object {
        const val EXTRA_ID = "news_id"
    }
}
