package kz.tenir.tv.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import kz.tenir.tv.R

object Ui {
    val GOLD = Color.parseColor("#C9A96E")

    fun parseColor(s: String?, fallback: Int): Int = try {
        if (s.isNullOrBlank()) fallback else Color.parseColor(s)
    } catch (e: IllegalArgumentException) {
        fallback
    }

    @Suppress("DEPRECATION")
    fun color(context: Context, id: Int): Int = context.resources.getColor(id)

    fun dp(context: Context, v: Float): Int = Math.round(v * context.resources.displayMetrics.density)

    fun rounded(color: Int, radiusPx: Float, strokePx: Int = 0, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx
            if (strokePx > 0) setStroke(strokePx, strokeColor)
        }

    /** Обрезка содержимого (картинок) по скруглённому контуру. */
    fun clipRounded(view: View, radiusPx: Float) {
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
            }
        }
        view.clipToOutline = true
    }

    fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb(Math.round(255 * alpha), Color.red(color), Color.green(color), Color.blue(color))

    /** Плавная заглушка вместо изображения: глубокий градиент «ночные горы». */
    fun placeholder(context: Context, seed: Int = 0): Drawable {
        val palettes = arrayOf(
            intArrayOf(Color.parseColor("#1D2A3A"), Color.parseColor("#0E141C")),
            intArrayOf(Color.parseColor("#2A2219"), Color.parseColor("#120E0A")),
            intArrayOf(Color.parseColor("#17282A"), Color.parseColor("#0B1415")),
        )
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, palettes[Math.abs(seed) % palettes.size])
    }
}

/**
 * Фокус для пульта: увеличение, золотая рамка и тень. Видно с дивана, без мыши.
 * Рамка рисуется поверх (foreground) и не сдвигает содержимое.
 */
object Focus {
    fun apply(view: View, accent: Int, radiusPx: Float, scale: Float = 1.07f, onFocus: ((Boolean) -> Unit)? = null) {
        view.isFocusable = true
        view.isFocusableInTouchMode = false
        val ring = Ui.rounded(Color.TRANSPARENT, radiusPx, Ui.dp(view.context, 2.5f), accent)
        val elevation = Ui.dp(view.context, 14f).toFloat()
        view.setOnFocusChangeListener { v, has ->
            if (v is FrameLayout) v.foreground = if (has) ring else null
            animate(v, if (has) scale else 1f, if (has) elevation else 0f)
            onFocus?.invoke(has)
        }
    }

    private fun animate(v: View, scale: Float, z: Float) {
        val set = AnimatorSet()
        set.playTogether(
            ObjectAnimator.ofFloat(v, View.SCALE_X, scale),
            ObjectAnimator.ofFloat(v, View.SCALE_Y, scale),
            ObjectAnimator.ofFloat(v, View.TRANSLATION_Z, z),
        )
        set.duration = 160
        set.interpolator = DecelerateInterpolator()
        set.start()
    }

    /** Строки списков: фон подсвечивается, без масштабирования. */
    fun applyRow(view: View, accent: Int, radiusPx: Float, onFocus: ((Boolean) -> Unit)? = null) {
        view.isFocusable = true
        val normal: Drawable = ColorDrawable(Color.TRANSPARENT)
        val focused = Ui.rounded(Ui.withAlpha(accent, 0.22f), radiusPx, Ui.dp(view.context, 1.5f), accent)
        view.background = normal
        view.setOnFocusChangeListener { v, has ->
            v.background = if (has) focused else normal
            onFocus?.invoke(has)
        }
    }
}

/** Короткое сообщение поверх экрана (ошибка запуска приложения и т. п.). */
object Notice {
    private val handler = Handler(Looper.getMainLooper())

    fun show(activity: Activity, text: String, durationMs: Long = 4000) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        root.findViewWithTag<View>(TAG)?.let(root::removeView)
        val ctx = activity
        val tv = TextView(ctx).apply {
            tag = TAG
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(Ui.dp(ctx, 22f), Ui.dp(ctx, 12f), Ui.dp(ctx, 22f), Ui.dp(ctx, 12f))
            background = Ui.rounded(Color.parseColor("#E61C2430"), Ui.dp(ctx, 10f).toFloat(), Ui.dp(ctx, 1f), Ui.withAlpha(Ui.GOLD, 0.6f))
            elevation = Ui.dp(ctx, 20f).toFloat()
            alpha = 0f
        }
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        lp.bottomMargin = Ui.dp(ctx, 36f)
        root.addView(tv, lp)
        tv.animate().alpha(1f).setDuration(200).start()
        handler.postDelayed({
            tv.animate().alpha(0f).setDuration(250).withEndAction { root.removeView(tv) }.start()
        }, durationMs)
    }

    private val TAG = R.id.tag_notice
}
