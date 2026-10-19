package kz.tenir.tv.ui.restaurant

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kz.tenir.tv.R
import kz.tenir.tv.core.content.MenuCategory
import kz.tenir.tv.core.content.MenuItem
import kz.tenir.tv.ui.BaseTvActivity
import kz.tenir.tv.ui.Focus
import kz.tenir.tv.ui.Ui

/** Ресторан: категории слева, блюда справа. Фокус на категории сразу показывает её блюда. */
class RestaurantActivity : BaseTvActivity() {
    private lateinit var categoriesView: LinearLayout
    private lateinit var itemsView: LinearLayout
    private var selected = -1
    private val categoryViews = ArrayList<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_restaurant)
        categoriesView = findViewById(R.id.categories)
        itemsView = findViewById(R.id.items)
        val r = app.hotel.restaurant
        if (r == null) {
            finish()
            return
        }
        app.images.load(r.imageUrl, findViewById(R.id.background), Ui.placeholder(this, 1))
        findViewById<TextView>(R.id.hours).apply { text = t(r.hours); setTextColor(accent) }
        findViewById<TextView>(R.id.title).text = t(r.name)
        findViewById<TextView>(R.id.description).text = t(r.description)
        findViewById<TextView>(R.id.orderInfo).apply {
            text = t(r.orderInfo)
            visibility = if (r.orderInfo.isBlank) View.GONE else View.VISIBLE
            val icon = getDrawable(R.drawable.ic_phone)?.mutate()
            icon?.setTint(accent)
            val s = Ui.dp(this@RestaurantActivity, 16f)
            icon?.setBounds(0, 0, s, s)
            setCompoundDrawablesRelative(icon, null, null, null)
        }
        val categories = r.categories.filter { it.items.isNotEmpty() }
        if (categories.isEmpty()) {
            findViewById<View>(R.id.empty).visibility = View.VISIBLE
            return
        }
        categories.forEachIndexed { i, c -> categoriesView.addView(categoryRow(c, i, r.currency)) }
        select(0, categories[0], r.currency)
        categoryViews[0].requestFocus()
    }

    override fun initialFocus(): View? = categoryViews.getOrNull(maxOf(selected, 0))

    private fun categoryRow(c: MenuCategory, index: Int, currency: String): TextView {
        val pad = Ui.dp(this, 14f)
        val tv = TextView(this).apply {
            id = View.generateViewId()
            text = t(c.name)
            textSize = 18f
            setTextColor(Ui.color(this@RestaurantActivity, R.color.text_2))
            setPadding(pad, Ui.dp(this@RestaurantActivity, 11f), pad, Ui.dp(this@RestaurantActivity, 11f))
            maxLines = 2
        }
        Focus.applyRow(tv, accent, Ui.dp(this, 8f).toFloat()) { has -> if (has) select(index, c, currency) }
        tv.setOnClickListener { itemsView.getChildAt(0)?.requestFocus() }
        categoryViews += tv
        return tv
    }

    private fun select(index: Int, c: MenuCategory, currency: String) {
        if (selected == index) return
        selected = index
        categoryViews.forEachIndexed { i, v ->
            v.setTextColor(if (i == index) accent else Ui.color(this, R.color.text_2))
            v.typeface = Typeface.create(if (i == index) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        }
        itemsView.removeAllViews()
        val back = categoryViews[index].id
        c.items.forEach { itemsView.addView(itemRow(it, currency, back)) }
        (itemsView.parent as ScrollView).scrollTo(0, 0)
    }

    private fun itemRow(m: MenuItem, currency: String, leftTarget: Int): View {
        val ctx = this
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = Ui.dp(ctx, 14f)
            setPadding(p, Ui.dp(ctx, 12f), p, Ui.dp(ctx, 12f))
            nextFocusLeftId = leftTarget
        }
        if (m.imageUrl != null) {
            val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            Ui.clipRounded(img, Ui.dp(ctx, 8f).toFloat())
            row.addView(img, LinearLayout.LayoutParams(Ui.dp(ctx, 72f), Ui.dp(ctx, 72f)).apply { marginEnd = Ui.dp(ctx, 16f) })
            app.images.load(m.imageUrl, img, Ui.placeholder(ctx, 1))
        }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(ctx).apply {
            text = t(m.name)
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("serif", Typeface.NORMAL)
        })
        val desc = t(m.description)
        if (desc.isNotBlank()) {
            col.addView(TextView(ctx).apply {
                text = desc
                textSize = 13f
                setTextColor(Ui.color(ctx, R.color.text_3))
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(ctx, 4f) })
        }
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        m.price?.let { price ->
            row.addView(TextView(ctx).apply {
                text = formatPrice(price, currency)
                textSize = 18f
                setTextColor(accent)
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(ctx, 16f) })
        }
        Focus.applyRow(row, accent, Ui.dp(ctx, 10f).toFloat())
        return row
    }

    companion object {
        /** 7900 → «7 900 ₸» (узкий неразрывный пробел между разрядами). */
        fun formatPrice(v: Double, currency: String): String {
            val whole = Math.round(v * 100) % 100 == 0L
            val n = if (whole) String.format(java.util.Locale.US, "%,d", Math.round(v))
            else String.format(java.util.Locale.US, "%,.2f", v)
            return n.replace(",", " ") + " " + currency
        }
    }
}
