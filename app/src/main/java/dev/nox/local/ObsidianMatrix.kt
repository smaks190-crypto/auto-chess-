package dev.nox.local

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot

/** Native modal. The original chip retains touch ownership while the sheet is shown. */
internal class ObsidianMatrix(
    private val host: FrameLayout,
    private val names: List<String>,
    private val current: () -> MatrixChoice,
    private val commit: (MatrixChoice) -> Unit,
    private val onVisibilityChanged: (Boolean) -> Unit
) {
    private val ctx: Context = host.context
    private var layer: FrameLayout? = null
    private var board: LinearLayout? = null
    private var title: TextView? = null
    private val cells = mutableListOf<TextView>()
    private var picked: MatrixChoice? = null
    private val colors = intArrayOf(0xFFE9EBF5.toInt(), 0xFF8EB8F2.toInt(), 0xFF82D4B6.toInt(), 0xFFFFAD65.toInt())
    private fun dp(n: Int) = (n * ctx.resources.displayMetrics.density + .5f).toInt()
    private fun panel(c: Int, radius: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(radius).toFloat() }
    private fun label(s: String, size: Float = 13f) = TextView(ctx).apply {
        text = s; textSize = size; setTextColor(0xFFF3F2F0.toInt()); gravity = Gravity.CENTER
    }
    private fun pill(s: String, action: () -> Unit) = Button(ctx).apply {
        text = s; isAllCaps = false; setTextColor(0xFFF3F2F0.toInt()); background = panel(0xFF333238.toInt(), 13)
        setOnClickListener { action() }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun bind(chip: Button) {
        chip.contentDescription = "Модель и мышление. Нажми или удерживай для выбора"
        chip.setOnClickListener { open(false) }
        var downX = 0f; var downY = 0f; var active = false; var cancelled = false
        val hold = Runnable {
            if (!chip.isEnabled || !chip.isAttachedToWindow || cancelled) return@Runnable
            active = true
            open(true)
            chip.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
        chip.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!v.isEnabled) return@setOnTouchListener false
                    downX = event.rawX; downY = event.rawY; active = false; cancelled = false
                    v.isPressed = true
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    v.postDelayed(hold, 340L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (active) {
                        val cell = at(event.rawX, event.rawY)
                        if (cell != null && cell != picked) { select(cell); v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
                    } else if (hypot(event.rawX - downX, event.rawY - downY) > dp(12)) {
                        cancelled = true; v.removeCallbacks(hold)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(hold); v.isPressed = false; v.parent.requestDisallowInterceptTouchEvent(false)
                    if (active) {
                        val final = at(event.rawX, event.rawY)
                        dismiss()
                        if (final != null && v.isEnabled) commit(final)
                    } else if (!cancelled && v.isEnabled) v.performClick()
                    active = false; true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(hold); v.isPressed = false; v.parent.requestDisallowInterceptTouchEvent(false)
                    if (active) dismiss()
                    active = false; cancelled = true; true
                }
                else -> true
            }
        }
        chip.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { v.removeCallbacks(hold) }
        })
    }

    fun dismiss(): Boolean {
        val old = layer ?: return false
        host.removeView(old)
        layer = null; board = null; title = null; cells.clear(); picked = null
        onVisibilityChanged(false)
        return true
    }

    private fun at(x: Float, y: Float): MatrixChoice? {
        val b = board ?: return null
        val p = IntArray(2); b.getLocationOnScreen(p)
        return MatrixChoice.at(x - p[0], y - p[1], b.width.toFloat(), b.height.toFloat(), names.size)
    }

    private fun open(holding: Boolean) {
        dismiss()
        val overlay = FrameLayout(ctx).apply {
            setBackgroundColor(0xCC000000.toInt())
            isClickable = true; setOnClickListener { dismiss() }
        }
        val sheet = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; background = panel(0xFF252527.toInt(), 24)
            setPadding(dp(14), dp(14), dp(14), dp(12)); isClickable = true
        }
        val width = minOf(host.width - dp(24), dp(430)).coerceAtLeast(dp(240))
        overlay.addView(sheet, FrameLayout.LayoutParams(width, -2, Gravity.CENTER))
        title = label("", 17f).apply { typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.START; setPadding(0, dp(4), 0, dp(14)) }
        sheet.addView(title)
        val matrixRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val rows = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        names.forEach { name ->
            rows.addView(label(name, 11f).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; setPadding(0,0,dp(6),0) }, LinearLayout.LayoutParams(-1,0,1f))
        }
        matrixRow.addView(rows, LinearLayout.LayoutParams(dp(78), -1))
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; background = panel(0xFF1B1B1D.toInt(), 18) }
        board = grid
        for (i in names.indices) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (effort in ChatEffort.entries) {
                val choice = MatrixChoice(i, effort)
                val dot = label("●", 15f).apply {
                    contentDescription = names[i] + ", " + effort.label
                    isFocusable = true; isClickable = true
                    setOnClickListener { select(choice) }
                }
                cells.add(dot)
                row.addView(dot, LinearLayout.LayoutParams(0, -1, 1f))
            }
            grid.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        matrixRow.addView(grid, LinearLayout.LayoutParams(0, -1, 1f))
        sheet.addView(matrixRow, LinearLayout.LayoutParams(-1, dp(174)))
        val columns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(78), dp(7), 0, dp(5)) }
        ChatEffort.entries.forEach { columns.addView(label(it.label, 10f), LinearLayout.LayoutParams(0, -2, 1f)) }
        sheet.addView(columns)
        sheet.addView(label(if (holding) "Веди палец · отпусти для выбора" else "Выбери сочетание", 12f).apply { setPadding(0, dp(8), 0, dp(8)) })
        sheet.addView(label("Мышление для чата. Агент использует собственный планировщик. Новая модель требует загрузки, если её нет.", 11f).apply { setTextColor(0xFFB5B1BA.toInt()); setPadding(0, dp(4), 0, dp(8)) })
        if (!holding) {
            sheet.addView(pill("Готово") { val choice = picked; dismiss(); if (choice != null) commit(choice) })
            sheet.addView(pill("Отмена") { dismiss() })
        }
        layer = overlay
        host.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        onVisibilityChanged(true)
        select(current())
    }

    private fun select(choice: MatrixChoice) {
        picked = choice
        title?.text = names[choice.model] + "  ·  " + choice.effort.label
        title?.setTextColor(colors[choice.effort.ordinal])
        cells.forEachIndexed { k, v ->
            val selected = k == choice.model * ChatEffort.entries.size + choice.effort.ordinal
            val color = colors[k % ChatEffort.entries.size]
            v.isSelected = selected
            v.setTextColor(color)
            v.textSize = if (selected) 30f else 12f
            v.alpha = if (selected) 1f else .52f
            v.background = if (selected) GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0x55777777,0x001B1B1D)).apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = dp(30).toFloat(); cornerRadius = dp(12).toFloat()
            } else null
        }
    }
}
