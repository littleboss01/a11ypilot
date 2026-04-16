package com.mrgreenapps.a11ypilot

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

object OverlayController {
    private var view: TextView? = null
    private var windowManager: WindowManager? = null
    private val main = Handler(Looper.getMainLooper())

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun show(context: Context) {
        main.post {
            if (view != null) return@post
            if (!canDrawOverlays(context)) {
                EventLog.append("overlay: SYSTEM_ALERT_WINDOW permission missing")
                return@post
            }
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val tv = TextView(context).apply {
                text = "AccessTest overlay\nwaiting for events…"
                setTextColor(Color.WHITE)
                setBackgroundColor(0xCC222222.toInt())
                setPadding(24, 16, 24, 16)
                textSize = 12f
            }
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 24
                y = 120
            }
            try {
                wm.addView(tv, params)
                view = tv
                windowManager = wm
                EventLog.append("overlay: shown")
            } catch (t: Throwable) {
                EventLog.append("overlay: failed to add view: ${t.message}")
            }
        }
    }

    fun hide() {
        main.post {
            val v = view ?: return@post
            try {
                windowManager?.removeView(v)
            } catch (_: Throwable) {
            }
            view = null
            windowManager = null
            EventLog.append("overlay: hidden")
        }
    }

    @SuppressLint("SetTextI18n")
    fun update(line: String) {
        main.post {
            view?.text = line
        }
    }
}
