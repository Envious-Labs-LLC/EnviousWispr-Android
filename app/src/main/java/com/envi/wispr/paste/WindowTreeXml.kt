package com.envi.wispr.paste

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * The accessibility tree as the XML `uiautomator dump` writes, from the windows this service already
 * holds (#181). The test harness (`scripts/uat/wispr_eyes.py`) parses exactly these attributes; a
 * `uiautomator dump` costs 1.9 s of process start per read, this costs milliseconds and, unlike
 * `uiautomator`, sees the service's own accessibility overlay.
 *
 * Pure: the walk is over [TreeNode], an interface the unit tests fake, and [fromWindows] is the only
 * line that touches the framework. Every string attribute goes through one escaper, because the
 * value that breaks the XML is never the one a test happened to try.
 */
internal object WindowTreeXml {
    /** One node of an accessibility tree, in the shape the harness reads. */
    interface TreeNode {
        val text: String
        val description: String
        val packageName: String
        val className: String
        val viewId: String
        val clickable: Boolean
        val enabled: Boolean
        val selected: Boolean
        val scrollable: Boolean
        val focused: Boolean
        val checkable: Boolean
        val checked: Boolean
        /** left, top, right, bottom on screen. */
        val bounds: IntArray
        val children: List<TreeNode>
    }

    fun render(roots: List<TreeNode>): String {
        val out = StringBuilder("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>")
        out.append("<hierarchy rotation=\"0\">")
        roots.forEach { appendNode(out, it) }
        out.append("</hierarchy>")
        return out.toString()
    }

    private fun appendNode(out: StringBuilder, node: TreeNode) {
        val b = node.bounds
        out.append("<node")
        attr(out, "text", node.text)
        attr(out, "resource-id", node.viewId)
        attr(out, "class", node.className)
        attr(out, "package", node.packageName)
        attr(out, "content-desc", node.description)
        attr(out, "checkable", node.checkable.toString())
        attr(out, "checked", node.checked.toString())
        attr(out, "clickable", node.clickable.toString())
        attr(out, "enabled", node.enabled.toString())
        attr(out, "focused", node.focused.toString())
        attr(out, "scrollable", node.scrollable.toString())
        attr(out, "selected", node.selected.toString())
        attr(out, "bounds", "[${b[0]},${b[1]}][${b[2]},${b[3]}]")
        if (node.children.isEmpty()) {
            out.append(" />")
            return
        }
        out.append(">")
        node.children.forEach { appendNode(out, it) }
        out.append("</node>")
    }

    private fun attr(out: StringBuilder, name: String, value: String) {
        out.append(' ').append(name).append("=\"").append(escape(value)).append('"')
    }

    /** XML attribute escaping for every string attribute: the five markup characters and control codes. */
    fun escape(value: String): String {
        val out = StringBuilder(value.length + 8)
        for (c in value) {
            when {
                c == '&' -> out.append("&amp;")
                c == '<' -> out.append("&lt;")
                c == '>' -> out.append("&gt;")
                c == '"' -> out.append("&quot;")
                c == '\'' -> out.append("&apos;")
                c == '\n' || c == '\t' || c == '\r' -> out.append(c)
                c < ' ' -> out.append(' ')
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /**
     * The framework adapter: every window's root, walked depth first. A window with no root is
     * skipped; a child that cannot be read is skipped. Nothing here recycles: the API floor is 33,
     * where `AccessibilityNodeInfo.recycle` is a no-op.
     */
    fun fromWindows(windows: List<AccessibilityWindowInfo>): List<TreeNode> =
        windows.mapNotNull { window -> window.root?.let { adapt(it, depth = 0) } }

    private const val MAX_DEPTH = 64

    private fun adapt(info: AccessibilityNodeInfo, depth: Int): TreeNode? {
        // THE CACHE LIES AFTER A TAP. The service's node cache is invalidated by the events it
        // subscribes to, and a switch flipping emits none of them, so a walk read right after a tap
        // reported the OLD checked state for seconds (measured 2026-09-20: fast eye False, uiautomator
        // True, for 1.5 s). `refresh()` asks the app's view directly. A node that is gone answers
        // false and is dropped.
        if (!info.refresh()) return null
        val rect = Rect()
        info.getBoundsInScreen(rect)
        val kids = if (depth >= MAX_DEPTH) emptyList() else
            (0 until info.childCount).mapNotNull { i -> info.getChild(i)?.let { adapt(it, depth + 1) } }
        return Snapshot(
            text = info.text?.toString().orEmpty(),
            description = info.contentDescription?.toString().orEmpty(),
            packageName = info.packageName?.toString().orEmpty(),
            className = info.className?.toString().orEmpty(),
            viewId = info.viewIdResourceName.orEmpty(),
            clickable = info.isClickable,
            enabled = info.isEnabled,
            selected = info.isSelected,
            scrollable = info.isScrollable,
            focused = info.isFocused,
            checkable = info.isCheckable,
            checked = info.isChecked,
            bounds = intArrayOf(rect.left, rect.top, rect.right, rect.bottom),
            children = kids,
        )
    }

    /** A value copy of a node, taken while the framework object is live. */
    class Snapshot(
        override val text: String,
        override val description: String,
        override val packageName: String,
        override val className: String,
        override val viewId: String,
        override val clickable: Boolean,
        override val enabled: Boolean,
        override val selected: Boolean,
        override val scrollable: Boolean,
        override val focused: Boolean,
        override val checkable: Boolean,
        override val checked: Boolean,
        override val bounds: IntArray,
        override val children: List<TreeNode>,
    ) : TreeNode
}
