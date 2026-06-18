package com.mrgreenapps.a11ypilot.agent

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Renders the current accessibility tree to a compact, token-efficient one-line-per-node DSL
 * the LLM consumes:
 *
 *   [id] ShortClass "text"  ?hint  *!…
 *
 * Flags: `*` checked/selected, `!` disabled, `…` editable.
 *
 * Returns an [IdMap] so the caller can resolve `[id]`s back to the live AccessibilityNodeInfo.
 */
object ScreenSerializer {

    private const val MAX_LINES = 200
    private const val MAX_TEXT = 80
    private val OWN_PACKAGE = "com.mrgreenapps.a11ypilot"

    data class Snapshot(
        val text: String,
        val idMap: Map<Int, AccessibilityNodeInfo>,
        val foregroundPackage: String,
        val truncated: Boolean
    )

    fun serialize(
        root: AccessibilityNodeInfo?,
        screenW: Int,
        screenH: Int,
        excludeOwnPackage: Boolean = false
    ): Snapshot {
        if (root == null) {
            return Snapshot("(no active window)", emptyMap(), "", false)
        }
        val pkg = root.packageName?.toString() ?: "?"
        if (excludeOwnPackage && pkg == OWN_PACKAGE) {
            return Snapshot(
                "(active window is the controller app itself; navigate away to act)",
                emptyMap(), pkg, false
            )
        }

        val screen = Rect(0, 0, screenW, screenH)
        val builder = StringBuilder()
        builder.append("foreground: ").append(pkg).append('\n')
        builder.append("screen: ").append(screenW).append('x').append(screenH).append('\n')

        val idMap = HashMap<Int, AccessibilityNodeInfo>(64)
        val nextId = intArrayOf(1)
        val lineCount = intArrayOf(0)
        var truncated = false

        fun emit(node: AccessibilityNodeInfo, depth: Int) {
            if (lineCount[0] >= MAX_LINES) { truncated = true; return }
            if (!shouldKeep(node, screen)) {
                // Skip this node but recurse — useful nodes may be inside container layouts.
                for (i in 0 until node.childCount) {
                    emit(node.getChild(i) ?: continue, depth)
                }
                return
            }
            val id = nextId[0]++
            idMap[id] = node
            builder.append("  ".repeat(depth.coerceAtMost(8)))
            builder.append('[').append(id).append("] ")
            builder.append(shortClass(node.className?.toString()))
            val text = pickText(node)
            if (text.isNotEmpty()) {
                builder.append(" \"").append(escape(text.take(MAX_TEXT))).append('"')
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val hint = node.hintText?.toString().orEmpty()
                if (hint.isNotEmpty() && hint != text) {
                    builder.append(" ?").append(escape(hint.take(MAX_TEXT)))
                }
            }
            val flags = buildFlags(node)
            if (flags.isNotEmpty()) builder.append(' ').append(flags)
            builder.append('\n')
            lineCount[0]++
            for (i in 0 until node.childCount) {
                emit(node.getChild(i) ?: continue, depth + 1)
            }
        }
        emit(root, 0)

        if (truncated) builder.append("(… tree truncated at $MAX_LINES nodes)\n")
        return Snapshot(builder.toString().trimEnd(), idMap, pkg, truncated)
    }

    private fun shouldKeep(n: AccessibilityNodeInfo, screen: Rect): Boolean {
        if (!n.isVisibleToUser) return false
        val b = Rect()
        n.getBoundsInScreen(b)
        if (b.isEmpty || !Rect.intersects(b, screen)) return false
        val hasText = !n.text.isNullOrBlank() ||
            !n.contentDescription.isNullOrBlank() ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !n.hintText.isNullOrBlank())
        val isAction = n.isClickable || n.isLongClickable || n.isCheckable ||
            n.isEditable || n.isScrollable || n.isFocusable
        return hasText || isAction
    }

    private fun pickText(n: AccessibilityNodeInfo): String {
        if (n.isPassword) return "••••"
        val t = n.text?.toString().orEmpty()
        if (t.isNotEmpty()) return t
        val cd = n.contentDescription?.toString().orEmpty()
        if (cd.isNotEmpty()) return cd
        return ""
    }

    private fun shortClass(name: String?): String {
        if (name.isNullOrEmpty()) return "View"
        val tail = name.substringAfterLast('.')
        // Strip noisy suffixes
        return tail
            .removeSuffix("Compat")
            .removeSuffix("Impl")
    }

    private fun buildFlags(n: AccessibilityNodeInfo): String {
        val sb = StringBuilder(4)
        if (n.isChecked || n.isSelected) sb.append('*')
        if (!n.isEnabled) sb.append('!')
        if (n.isEditable) sb.append('…')
        return sb.toString()
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
}
