package com.mrgreenapps.a11ypilot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume

class PilotAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "AccessTest"
        const val ACTION = "com.mrgreenapps.a11ypilot.ACTION"
        const val EXTRA_OP = "op"
        const val EXTRA_TEXT = "text"
        const val EXTRA_VALUE = "value"
        const val EXTRA_X1 = "x1"
        const val EXTRA_Y1 = "y1"
        const val EXTRA_X2 = "x2"
        const val EXTRA_Y2 = "y2"
        const val EXTRA_DURATION = "durationMs"
        const val EXTRA_GLOBAL = "globalAction"

        const val OP_DUMP = "dump"
        const val OP_CLICK = "click"
        const val OP_SET_TEXT = "setText"
        const val OP_SWIPE = "swipe"
        const val OP_GLOBAL = "global"
        const val OP_OVERLAY_SHOW = "overlayShow"
        const val OP_OVERLAY_HIDE = "overlayHide"

        @Volatile
        var INSTANCE: PilotAccessibilityService? = null
            private set
    }

    @Volatile
    private var lastEventTimeMs: Long = 0L

    /**
     * Suspends until no AccessibilityEvent has been received for [quietMs], or until [timeoutMs]
     * elapses, whichever comes first. Used by the agent loop to wait for a screen to "settle"
     * after dispatching an action.
     */
    /**
     * Captures the default-display screenshot via [takeScreenshot] (API 30+), downscales to
     * [maxEdgePx] on the longest edge, encodes JPEG at [quality], and returns base64 (no padding).
     * Returns null on older OS versions or if the platform call fails.
     */
    data class Screenshot(val base64: String, val mimeType: String, val width: Int, val height: Int)

    suspend fun captureScreenshotJpegBase64(
        maxEdgePx: Int = 1024,
        quality: Int = 70
    ): Screenshot? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val raw: Bitmap = suspendCancellableCoroutine<Bitmap?> { cont ->
            val executor = Executors.newSingleThreadExecutor()
            cont.invokeOnCancellation { executor.shutdown() }
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    executor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val buffer: HardwareBuffer = screenshot.hardwareBuffer
                            val bmp = try {
                                Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            } catch (t: Throwable) {
                                EventLog.append("screenshot: wrap failed ${t.message}")
                                null
                            } finally {
                                try { buffer.close() } catch (_: Throwable) {}
                                executor.shutdown()
                            }
                            if (cont.isActive) cont.resume(bmp)
                        }
                        override fun onFailure(errorCode: Int) {
                            EventLog.append("screenshot: onFailure code=$errorCode")
                            executor.shutdown()
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            } catch (t: Throwable) {
                executor.shutdown()
                EventLog.append("screenshot: takeScreenshot threw ${t.message}")
                if (cont.isActive) cont.resume(null)
            }
        } ?: return null

        // wrapHardwareBuffer's bitmap shares the hardware buffer; copy to a software bitmap so
        // we can downscale + compress safely, then recycle the hardware-backed wrapper.
        val software = try {
            raw.copy(Bitmap.Config.ARGB_8888, false)
        } catch (t: Throwable) {
            EventLog.append("screenshot: copy failed ${t.message}")
            null
        } ?: run {
            try { raw.recycle() } catch (_: Throwable) {}
            return null
        }
        try { raw.recycle() } catch (_: Throwable) {}

        val w0 = software.width
        val h0 = software.height
        val longest = maxOf(w0, h0)
        val scaled = if (longest <= maxEdgePx) software else {
            val scale = maxEdgePx.toFloat() / longest
            val newW = (w0 * scale).toInt().coerceAtLeast(1)
            val newH = (h0 * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(software, newW, newH, true).also {
                if (it !== software) software.recycle()
            }
        }

        val finalW = scaled.width
        val finalH = scaled.height
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(30, 95), baos)
        scaled.recycle()
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        return Screenshot(b64, "image/jpeg", finalW, finalH)
    }

    suspend fun awaitSettle(timeoutMs: Long = 1500L, quietMs: Long = 250L) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        // Poll-based: cheap and reliable; we already update lastEventTimeMs on every event.
        while (true) {
            val now = SystemClock.uptimeMillis()
            if (now >= deadline) return
            val sinceLast = now - lastEventTimeMs
            if (sinceLast >= quietMs) return
            val sleep = minOf(quietMs - sinceLast, deadline - now)
            if (sleep <= 0) return
            delay(sleep)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val op = intent.getStringExtra(EXTRA_OP) ?: return
            EventLog.append("recv op=$op")
            when (op) {
                OP_DUMP -> dumpActiveWindow()
                OP_CLICK -> {
                    val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                    clickByText(text)
                }
                OP_SET_TEXT -> {
                    val text = intent.getStringExtra(EXTRA_TEXT)
                    val value = intent.getStringExtra(EXTRA_VALUE).orEmpty()
                    setTextOnTarget(text, value)
                }
                OP_SWIPE -> {
                    val x1 = intent.getIntExtra(EXTRA_X1, 0)
                    val y1 = intent.getIntExtra(EXTRA_Y1, 0)
                    val x2 = intent.getIntExtra(EXTRA_X2, 0)
                    val y2 = intent.getIntExtra(EXTRA_Y2, 0)
                    val duration = intent.getLongExtra(EXTRA_DURATION, 300L)
                    swipe(x1, y1, x2, y2, duration)
                }
                OP_GLOBAL -> {
                    val g = intent.getIntExtra(EXTRA_GLOBAL, -1)
                    if (g != -1) globalAction(g)
                }
                OP_OVERLAY_SHOW -> OverlayController.show(this@PilotAccessibilityService)
                OP_OVERLAY_HIDE -> OverlayController.hide()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        INSTANCE = this
        ServiceState.setEnabled(true)
        EventLog.append("service: connected")
        Log.i(TAG, "service connected")
        val filter = IntentFilter(ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        ServiceState.setEnabled(false)
        EventLog.append("service: unbound")
        Log.i(TAG, "service unbound")
        try {
            unregisterReceiver(receiver)
        } catch (_: Throwable) {
        }
        OverlayController.hide()
        if (INSTANCE === this) INSTANCE = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        ServiceState.setEnabled(false)
        EventLog.append("service: destroyed")
        if (INSTANCE === this) INSTANCE = null
        super.onDestroy()
    }

    override fun onInterrupt() {
        EventLog.append("service: interrupted")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        lastEventTimeMs = SystemClock.uptimeMillis()
        val type = AccessibilityEvent.eventTypeToString(event.eventType)
        val pkg = event.packageName?.toString() ?: "?"
        val cls = event.className?.toString() ?: "?"
        val summary = "$type  $pkg  $cls"
        Log.d(TAG, summary)
        EventLog.append(summary)
        OverlayController.update("$type\n$pkg")

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // Light-touch: only deep-dump on explicit op to avoid log spam.
        }
    }

    // ---- helpers ----

    private fun dumpActiveWindow() {
        val root = rootInActiveWindow
        if (root == null) {
            EventLog.append("dump: no active window")
            return
        }
        EventLog.append("dump: pkg=${root.packageName}")
        val sb = StringBuilder()
        walk(root, 0, sb)
        Log.i(TAG, "---- DUMP ----\n$sb")
        EventLog.append("dump: ${sb.lineSequence().count()} nodes (see Logcat tag $TAG)")
    }

    private fun walk(node: AccessibilityNodeInfo?, depth: Int, sb: StringBuilder) {
        if (node == null) return
        val indent = "  ".repeat(depth)
        val id = node.viewIdResourceName ?: "-"
        val text = node.text?.toString()?.take(60) ?: "-"
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "-"
        val clickable = if (node.isClickable) "[click]" else ""
        val editable = if (node.isEditable) "[edit]" else ""
        sb.appendLine("$indent$cls  id=$id  text=\"$text\" $clickable$editable")
        for (i in 0 until node.childCount) {
            walk(node.getChild(i), depth + 1, sb)
        }
    }

    private fun clickByText(text: String) {
        val root = rootInActiveWindow
        if (root == null) {
            EventLog.append("click: no active window")
            return
        }
        val matches = root.findAccessibilityNodeInfosByText(text).orEmpty()
        if (matches.isEmpty()) {
            EventLog.append("click: no node matches \"$text\"")
            return
        }
        val target = matches.firstOrNull { it.isClickable }
            ?: matches.first().let { climbToClickable(it) ?: it }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        EventLog.append("click \"$text\" → $ok")
    }

    private fun climbToClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }

    private fun setTextOnTarget(searchText: String?, value: String) {
        val root = rootInActiveWindow
        if (root == null) {
            EventLog.append("setText: no active window")
            return
        }
        val target: AccessibilityNodeInfo? = if (!searchText.isNullOrEmpty()) {
            root.findAccessibilityNodeInfosByText(searchText)?.firstOrNull { it.isEditable }
        } else {
            findFocusedEditable(root)
        }
        if (target == null) {
            EventLog.append("setText: no editable node found")
            return
        }
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                value
            )
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        EventLog.append("setText \"$value\" → $ok")
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedEditable(child)
            if (found != null) return found
        }
        return null
    }

    private fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                EventLog.append("swipe: completed")
            }
            override fun onCancelled(g: GestureDescription?) {
                EventLog.append("swipe: cancelled")
            }
        }, null)
        EventLog.append("swipe ($x1,$y1)→($x2,$y2) dispatched=$dispatched")
    }

    private fun globalAction(action: Int) {
        val ok = performGlobalAction(action)
        EventLog.append("globalAction $action → $ok")
    }
}
