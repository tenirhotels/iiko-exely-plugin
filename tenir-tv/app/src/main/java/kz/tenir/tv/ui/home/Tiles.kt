package kz.tenir.tv.ui.home

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kz.tenir.tv.R
import kz.tenir.tv.ui.Focus
import kz.tenir.tv.ui.Ui

/** Описание плитки главного экрана. */
class TileSpec(
    val id: String,
    val title: String,
    val subtitle: String,
    val iconRes: Int = 0,
    val imageUrl: String? = null,
    /** Баннер или иконка установленного приложения. */
    val appArt: Drawable? = null,
    val appArtIsBanner: Boolean = false,
    val color: Int? = null,
    val weight: Float = 1f,
    val onClick: () -> Unit,
)

/** Плитки строятся кодом: их набор и порядок задаёт сервер (home.tiles). */
object TileFactory {

    fun build(context: Context, spec: TileSpec, accent: Int, loadImage: (String, ImageView) -> Unit): FrameLayout {
        val radius = Ui.dp(context, 12f).toFloat()
        val tile = FrameLayout(context)
        tile.background = Ui.rounded(Ui.color(context, R.color.surface_hi), radius)
        Ui.clipRounded(tile, radius)
        tile.contentDescription = spec.title

        when {
            spec.appArt != null && spec.appArtIsBanner -> {
                // официальный баннер Android TV из самого приложения
                tile.background = Ui.rounded(Color.BLACK, radius)
                tile.addView(ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageDrawable(spec.appArt)
                }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                return finish(tile, spec, accent, radius)
            }
            spec.imageUrl != null -> {
                val img = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                tile.addView(img, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                loadImage(spec.imageUrl, img)
                tile.addView(View(context).apply { setBackgroundResource(R.drawable.scrim_banner) },
                    FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
            spec.color != null -> {
                tile.background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(spec.color, darken(spec.color)),
                ).apply { cornerRadius = radius }
            }
            else -> {
                tile.background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.parseColor("#FF222C3A"), Color.parseColor("#FF141A22")),
                ).apply { cornerRadius = radius }
            }
        }

        val pad = Ui.dp(context, 15f)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, Ui.dp(context, 14f))
        }
        if (spec.appArt != null) {
            column.addView(ImageView(context).apply { setImageDrawable(spec.appArt) },
                LinearLayout.LayoutParams(Ui.dp(context, 34f), Ui.dp(context, 34f)))
        } else if (spec.iconRes != 0) {
            column.addView(ImageView(context).apply {
                setImageResource(spec.iconRes)
                setColorFilter(if (spec.color != null) Color.WHITE else accent)
            }, LinearLayout.LayoutParams(Ui.dp(context, 24f), Ui.dp(context, 24f)))
        }
        column.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
        column.addView(TextView(context).apply {
            text = spec.title
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        })
        column.addView(TextView(context).apply {
            text = spec.subtitle
            setTextColor(Ui.color(context, R.color.text_2))
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = Ui.dp(context, 5f) })
        tile.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.BOTTOM))
        return finish(tile, spec, accent, radius)
    }

    private fun finish(tile: FrameLayout, spec: TileSpec, accent: Int, radius: Float): FrameLayout {
        Focus.apply(tile, accent, radius, scale = 1.08f)
        tile.setOnClickListener { spec.onClick() }
        return tile
    }

    private fun darken(c: Int): Int =
        Color.rgb((Color.red(c) * 0.45).toInt(), (Color.green(c) * 0.45).toInt(), (Color.blue(c) * 0.45).toInt())
}
