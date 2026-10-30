package com.ski.live.island

import android.animation.LayoutTransition
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ski.live.R
import com.ski.live.data.PillPosition
import com.ski.live.data.Store
import com.ski.live.live.ACCENT
import com.ski.live.live.LiveService
import com.ski.live.live.copyCode
import com.ski.live.live.iconFor
import com.ski.live.live.render
import com.ski.live.model.Kind
import com.ski.live.notif.Mirror
import com.ski.live.ui.MainActivity

/**
 * The floating pill at the top of the screen (Dynamic Island style).
 * Shows, in priority order: a just-arrived notification, one-time codes,
 * Ski Live activities, and other apps' live notifications. Tap to expand.
 */
object Pill {
    private const val GREEN = 0xFF3DFF8B.toInt()
    private const val GREY = 0xFF9A9AA6.toInt()

    private class Item(
        val key: String,
        val icon: Drawable?,
        val tint: Int?,
        val label: String,
        val title: String,
        val text: String,
        val chip: String?,
        val chipColor: Int,
        val fraction: Float?,
        val barColor: Int,
        val ticking: Boolean,
        val tap: (Context) -> Unit,
        val dismiss: () -> Unit,
    )

    private var appCtx: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var root: LinearLayout? = null
    private var shownAt: PillPosition? = null
    private var expanded = false
    private val hidden = HashSet<String>()
    private val ticker = Runnable { update() }
    private val collapse = Runnable { expanded = false; update() }

    fun refresh(ctx: Context) {
        appCtx = ctx.applicationContext
        main.post { update() }
    }

    private fun update() {
        val ctx = appCtx ?: return
        main.removeCallbacks(ticker)
        val s = Store.settings.value
        val now = System.currentTimeMillis()
        val list = if (s.pill && Settings.canDrawOverlays(ctx)) items(ctx, now) else emptyList()
        if (list.isEmpty()) {
            removeView()
            expanded = false
            return
        }
        try {
            ensureView(ctx, s.pillPosition)
            bind(ctx, list)
        } catch (e: Exception) {
            removeView()
            return
        }
        if (list.any { it.ticking } || Mirror.flash.value != null) main.postDelayed(ticker, 1_000)
    }

    // ---------- what to show ----------

    private fun items(ctx: Context, now: Long): List<Item> {
        val out = ArrayList<Item>()

        Mirror.flash.value?.let { f ->
            if (f.until > now) {
                out += Item(
                    key = "flash:" + f.key, icon = f.icon, tint = null,
                    label = f.title, title = "${f.app} · ${f.title}", text = f.text,
                    chip = null, chipColor = GREY, fraction = null, barColor = ACCENT, ticking = true,
                    tap = { c -> send(c, f.intent) },
                    dismiss = { Mirror.flash(null) },
                )
            } else {
                Mirror.flash(null)
            }
        }

        val acts = Store.activities.value.filter { !it.ended }
        acts.filter { it.kind == Kind.OTP }.sortedByDescending { it.startAt }.forEach { a ->
            out += Item(
                key = a.id, icon = ctx.getDrawable(R.drawable.ic_key), tint = GREEN,
                label = a.title.ifBlank { "Code" }, title = "${a.title} code", text = "Tap to copy",
                chip = a.chip, chipColor = GREEN, fraction = null, barColor = GREEN, ticking = false,
                tap = { c ->
                    copyCode(c, a.chip)
                    Store.remove(a.id)
                    c.getSystemService(NotificationManager::class.java).cancel(a.notificationId)
                },
                dismiss = { LiveService.send(ctx, LiveService.ACTION_END, a.id) },
            )
        }

        acts.filter { it.kind != Kind.OTP }.forEach { a ->
            val r = render(a, now)
            val counting = r.countdownTo > now
            out += Item(
                key = a.id, icon = ctx.getDrawable(r.trackerIcon), tint = r.segmentColors.first(),
                label = r.pillLabel, title = r.title, text = listOfNotNull(r.text, r.sub).joinToString(" · "),
                chip = if (counting) hms(r.countdownTo - now) else r.chip,
                chipColor = r.segmentColors.first(), fraction = r.fraction, barColor = r.segmentColors.first(),
                ticking = counting,
                tap = { c -> openApp(c) },
                dismiss = { LiveService.send(ctx, LiveService.ACTION_END, a.id) },
            )
        }

        if (Store.settings.value.mirrorLive) {
            Mirror.items.value.values.sortedByDescending { it.postTime }.forEach { m ->
                val chrono = m.chronoAt > 0
                val color = if (m.color != 0 && Color.alpha(m.color) > 0) m.color or 0xFF000000.toInt() else ACCENT
                out += Item(
                    key = m.key, icon = m.icon, tint = null,
                    label = m.title, title = "${m.app} · ${m.title}", text = m.text,
                    chip = m.chip ?: if (chrono) {
                        if (m.countDown) hms(m.chronoAt - now) else hms(now - m.chronoAt)
                    } else null,
                    chipColor = color, fraction = m.fraction, barColor = color,
                    ticking = chrono,
                    tap = { c -> send(c, m.intent) },
                    dismiss = {},
                )
            }
        }
        return out.filter { it.key !in hidden }
    }

    // ---------- views ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureView(ctx: Context, pos: PillPosition) {
        if (root != null && shownAt == pos) return
        removeView()
        val w = ctx.getSystemService(WindowManager::class.java)
        val v = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutTransition = LayoutTransition()
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_OUTSIDE && expanded) {
                    expanded = false
                    update()
                }
                false
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or when (pos) {
                PillPosition.LEFT -> Gravity.START
                PillPosition.CENTER -> Gravity.CENTER_HORIZONTAL
                PillPosition.RIGHT -> Gravity.END
            }
            x = if (pos == PillPosition.CENTER) 0 else dp(ctx, 10)
            y = dp(ctx, 4)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "Ski Live pill"
        }
        w.addView(v, lp)
        wm = w
        root = v
        shownAt = pos
    }

    private fun removeView() {
        val v = root ?: return
        try {
            wm?.removeView(v)
        } catch (_: Exception) {
        }
        root = null
        shownAt = null
    }

    private fun bind(ctx: Context, items: List<Item>) {
        val r = root ?: return
        r.removeAllViews()
        if (!expanded) {
            r.addView(compact(ctx, items.first(), items.size - 1))
        } else {
            r.addView(expandedCard(ctx, items.take(4)))
        }
    }

    private fun compact(ctx: Context, it: Item, more: Int): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillBg(ctx, 50f)
            setPadding(dp(ctx, 8), dp(ctx, 5), dp(ctx, 12), dp(ctx, 5))
            setOnClickListener {
                expanded = true
                main.removeCallbacks(collapse)
                main.postDelayed(collapse, 8_000)
                update()
            }
            setOnLongClickListener { _ ->
                it.dismiss()
                hidden += it.key
                update()
                true
            }
        }
        row.addView(icon(ctx, it, 18))
        row.addView(text(ctx, it.label, 13f, Color.WHITE, bold = true).apply {
            maxWidth = dp(ctx, 150)
            setPadding(dp(ctx, 6), 0, 0, 0)
        })
        it.chip?.let { c ->
            row.addView(text(ctx, c, 13f, it.chipColor, bold = true).apply {
                setPadding(dp(ctx, 8), 0, 0, 0)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                fontFeatureSettings = "tnum"
            })
        }
        if (more > 0) {
            row.addView(text(ctx, "+$more", 11f, GREY, bold = false).apply { setPadding(dp(ctx, 6), 0, 0, 0) })
        }
        return row
    }

    private fun expandedCard(ctx: Context, items: List<Item>): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = pillBg(ctx, 26f)
            setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10))
            minimumWidth = dp(ctx, 280)
        }
        items.forEachIndexed { i, it ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, if (i == 0) 0 else dp(ctx, 10), 0, 0)
                setOnClickListener { _ ->
                    expanded = false
                    it.tap(ctx)
                    update()
                }
                setOnLongClickListener { _ ->
                    it.dismiss()
                    hidden += it.key
                    update()
                    true
                }
            }
            val top = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(icon(ctx, it, 28))
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(ctx, 10), 0, dp(ctx, 8), 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(text(ctx, it.title, 14f, Color.WHITE, bold = true))
            if (it.text.isNotBlank()) {
                col.addView(text(ctx, it.text, 12f, GREY, bold = false).apply {
                    isSingleLine = false
                    maxLines = 2
                })
            }
            top.addView(col)
            it.chip?.let { c -> top.addView(text(ctx, c, 16f, it.chipColor, bold = true)) }
            row.addView(top)
            it.fraction?.let { f ->
                row.addView(
                    Bar(ctx, f, it.barColor).apply {
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 6))
                            .apply { topMargin = dp(ctx, 8) }
                    },
                )
            }
            card.addView(row)
        }
        card.addView(
            text(ctx, "Tap to open · hold to dismiss", 10f, GREY, bold = false).apply {
                setPadding(0, dp(ctx, 8), 0, 0)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            },
        )
        return card
    }

    private fun icon(ctx: Context, it: Item, size: Int) = ImageView(ctx).apply {
        val d = (it.icon ?: ctx.getDrawable(R.drawable.ic_stat_live))?.mutate()
        if (it.tint != null) d?.setTint(it.tint)
        setImageDrawable(d)
        layoutParams = LinearLayout.LayoutParams(dp(ctx, size), dp(ctx, size))
    }

    private fun text(ctx: Context, s: String, sp: Float, color: Int, bold: Boolean) = TextView(ctx).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun pillBg(ctx: Context, radiusDp: Float) = GradientDrawable().apply {
        setColor(Color.BLACK)
        cornerRadius = radiusDp * ctx.resources.displayMetrics.density
        setStroke(dp(ctx, 1), 0x22FFFFFF)
    }

    private class Bar(ctx: Context, val f: Float, val color: Int) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val h = height.toFloat()
            val r = h / 2
            p.color = 0x33FFFFFF
            c.drawRoundRect(RectF(0f, 0f, width.toFloat(), h), r, r, p)
            p.color = color
            c.drawRoundRect(RectF(0f, 0f, width * f.coerceIn(0.03f, 1f), h), r, r, p)
        }
    }

    // ---------- helpers ----------

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun hms(ms: Long): String {
        val t = (ms.coerceAtLeast(0) + 999) / 1000
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun openApp(ctx: Context) {
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Suppress("DEPRECATION")
    private fun send(ctx: Context, pi: PendingIntent?) {
        if (pi == null) return
        try {
            val opts = if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
            } else null
            pi.send(ctx, 0, null, null, null, null, opts)
        } catch (_: Exception) {
        }
    }
}
