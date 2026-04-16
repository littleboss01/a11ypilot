package com.mrgreenapps.a11ypilot.agent

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.mrgreenapps.a11ypilot.EventLog

/**
 * Floating overlay shown over other apps while the agent runs. Displays:
 *   - Current step + last tool call
 *   - Running token totals
 *   - A Stop button that invokes the cancel callback
 *
 * Requires SYSTEM_ALERT_WINDOW (Settings.canDrawOverlays). Silently no-ops if missing.
 */
object AgentOverlay {

    private val main = Handler(Looper.getMainLooper())

    private var root: LinearLayout? = null
    private var statusView: TextView? = null
    private var usageView: TextView? = null
    private var stopBtn: Button? = null
    private var wm: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var onStop: (() -> Unit)? = null

    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun show(context: Context, onStop: () -> Unit) {
        main.post {
            if (root != null) {
                this.onStop = onStop
                return@post
            }
            if (!canShow(context)) return@post

            val wmLocal = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = 24f
                    setColor(0xE6111317.toInt())
                    setStroke(2, 0x4D7C8DA5)
                }
                setPadding(28, 20, 28, 20)
            }

            val title = TextView(context).apply {
                text = "🤖 Agent running"
                setTextColor(Color.WHITE)
                textSize = 13f
            }
            val status = TextView(context).apply {
                text = "starting…"
                setTextColor(0xFFCBD5E1.toInt())
                textSize = 11f
                setPadding(0, 6, 0, 0)
            }
            val usage = TextView(context).apply {
                text = "in 0  cR 0  out 0"
                setTextColor(0xFF94A3B8.toInt())
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(0, 4, 0, 0)
            }
            val stop = Button(context).apply {
                text = "Stop"
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = 16f
                    setColor(0xFFB91C1C.toInt())
                }
                minWidth = 0
                minimumWidth = 0
                setPadding(28, 8, 28, 8)
                textSize = 11f
                setOnClickListener { this@AgentOverlay.onStop?.invoke() }
            }

            container.addView(title)
            container.addView(status)
            container.addView(usage)
            container.addView(stop, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 })

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

            // FLAG_NOT_FOCUSABLE so we don't steal IME; touches still go to the Stop button.
            // FLAG_NOT_TOUCH_MODAL so taps outside our box pass through to the app underneath.
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 24
                y = 80
            }

            attachDragHandler(container, wmLocal, lp)

            try {
                wmLocal.addView(container, lp)
                root = container
                statusView = status
                usageView = usage
                stopBtn = stop
                wm = wmLocal
                params = lp
                this.onStop = onStop
                EventLog.append("agent overlay: shown")
            } catch (t: Throwable) {
                EventLog.append("agent overlay: addView failed: ${t.message}")
            }
        }
    }

    fun update(statusLine: String, usageLine: String) {
        main.post {
            statusView?.text = statusLine
            usageView?.text = usageLine
        }
    }

    fun hide() {
        main.post {
            val r = root ?: return@post
            try { wm?.removeView(r) } catch (_: Throwable) {}
            root = null
            statusView = null
            usageView = null
            stopBtn = null
            wm = null
            params = null
            onStop = null
            EventLog.append("agent overlay: hidden")
        }
    }

    private fun attachDragHandler(view: View, wm: WindowManager, lp: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        view.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = ev.rawX
                    touchY = ev.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (ev.rawX - touchX).toInt()
                    lp.y = startY + (ev.rawY - touchY).toInt()
                    try { wm.updateViewLayout(view, lp) } catch (_: Throwable) {}
                    false
                }
                else -> false
            }
        }
    }
}
