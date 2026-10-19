package kz.tenir.tv.ui.apps

import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kz.tenir.tv.R
import kz.tenir.tv.data.InstalledApp
import kz.tenir.tv.data.LaunchResult
import kz.tenir.tv.ui.BaseTvActivity
import kz.tenir.tv.ui.Focus
import kz.tenir.tv.ui.Ui

/** Все установленные приложения сеткой: баннер Android TV или иконка. */
class AppsActivity : BaseTvActivity() {
    private lateinit var grid: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apps)
        grid = findViewById(R.id.grid)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun initialFocus(): View? = (grid.getChildAt(0) as? LinearLayout)?.getChildAt(0)

    private fun render() {
        grid.removeAllViews()
        val apps = app.launcher.installedApps()
        findViewById<View>(R.id.empty).visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
        val gap = Ui.dp(this, 16f)
        apps.chunked(COLUMNS).forEach { chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
            chunk.forEachIndexed { i, a ->
                row.addView(cell(a), LinearLayout.LayoutParams(0, Ui.dp(this, 104f), 1f).apply {
                    if (i < COLUMNS - 1) marginEnd = gap
                })
            }
            repeat(COLUMNS - chunk.size) { i ->
                row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply {
                    if (chunk.size + i < COLUMNS - 1) marginEnd = gap
                })
            }
            grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = gap })
        }
        (grid.getChildAt(0) as? LinearLayout)?.getChildAt(0)?.requestFocus()
    }

    private fun cell(a: InstalledApp): View {
        val radius = Ui.dp(this, 10f).toFloat()
        val cell = FrameLayout(this)
        cell.background = Ui.rounded(Ui.color(this, R.color.surface_hi), radius)
        Ui.clipRounded(cell, radius)
        cell.contentDescription = a.label
        val banner = app.launcher.banner(a.packageName)
        if (banner != null) {
            cell.background = Ui.rounded(Color.BLACK, radius)
            cell.addView(ImageView(this).apply {
                setImageDrawable(banner)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(-1, -1))
        } else {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(Ui.dp(context, 8f), 0, Ui.dp(context, 8f), 0)
            }
            col.addView(ImageView(this).apply { setImageDrawable(app.launcher.icon(a.packageName)) },
                LinearLayout.LayoutParams(Ui.dp(this, 44f), Ui.dp(this, 44f)))
            col.addView(TextView(this).apply {
                text = a.label
                textSize = 13f
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@AppsActivity, 8f) })
            cell.addView(col, FrameLayout.LayoutParams(-1, -1))
        }
        Focus.apply(cell, accent, radius, scale = 1.08f)
        cell.setOnClickListener {
            when (val r = app.launcher.launch(a.packageName)) {
                LaunchResult.Started -> {}
                LaunchResult.NotInstalled -> {
                    notice(getString(R.string.launch_not_installed, a.label))
                    render()
                }
                is LaunchResult.Failed -> {
                    app.event("Запуск ${a.packageName}: ${r.reason}")
                    notice(getString(R.string.launch_failed, a.label))
                }
            }
        }
        return cell
    }

    companion object {
        private const val COLUMNS = 5
    }
}
