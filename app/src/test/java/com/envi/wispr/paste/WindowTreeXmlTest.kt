package com.envi.wispr.paste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The fast eye's XML is what the harness parses (#181). Every attribute carries a NON-DEFAULT value
 * here, so omitting any one of them changes the parsed document and turns a row red; a fixture of
 * defaults would parse identically with the attribute gone (review round 1).
 */
class WindowTreeXmlTest {
    private class Fake(
        override val text: String = "",
        override val description: String = "",
        override val packageName: String = "com.envi.wispr",
        override val className: String = "android.widget.TextView",
        override val viewId: String = "",
        override val clickable: Boolean = false,
        override val enabled: Boolean = true,
        override val selected: Boolean = false,
        override val scrollable: Boolean = false,
        override val focused: Boolean = false,
        override val checkable: Boolean = false,
        override val checked: Boolean = false,
        override val bounds: IntArray = intArrayOf(0, 0, 0, 0),
        override val children: List<WindowTreeXml.TreeNode> = emptyList(),
    ) : WindowTreeXml.TreeNode

    private fun parse(xml: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(xml.byteInputStream()).documentElement

    private fun nodes(root: Element): List<Element> {
        val out = mutableListOf<Element>()
        fun walk(e: Element) {
            if (e.tagName == "node") out += e
            val kids = e.childNodes
            for (i in 0 until kids.length) (kids.item(i) as? Element)?.let { walk(it) }
        }
        walk(root)
        return out
    }

    @Test
    fun everyAttributeTheHarnessReadsIsWrittenWithItsValueAndAncestryIsNested() {
        val child = Fake(
            text = "Spoken emoji", description = "row label", packageName = "com.envi.wispr",
            className = "android.widget.Switch", viewId = "com.envi.wispr:id/spoken_emoji",
            clickable = true, enabled = false, selected = true, scrollable = true, focused = true,
            checkable = true, checked = true, bounds = intArrayOf(114, 2401, 428, 2462),
        )
        val root = Fake(className = "android.widget.FrameLayout", bounds = intArrayOf(0, 0, 1344, 2992), children = listOf(child))
        val doc = parse(WindowTreeXml.render(listOf(root)))
        assertEquals("hierarchy", doc.tagName)
        val all = nodes(doc)
        assertEquals(2, all.size)
        val leaf = all[1]
        assertEquals("node", (leaf.parentNode as Element).tagName)  // nested inside its parent
        val expected = mapOf(
            "text" to "Spoken emoji", "content-desc" to "row label", "package" to "com.envi.wispr",
            "class" to "android.widget.Switch", "resource-id" to "com.envi.wispr:id/spoken_emoji",
            "clickable" to "true", "enabled" to "false", "selected" to "true", "scrollable" to "true",
            "focused" to "true", "checkable" to "true", "checked" to "true", "bounds" to "[114,2401][428,2462]",
        )
        for ((name, value) in expected) {
            assertEquals("attribute $name", value, leaf.getAttribute(name))
        }
        assertEquals(13, expected.size)
    }

    @Test
    fun everyStringAttributeIsEscaped() {
        val hostile = "a & b < c > d \" e ' f  g"
        val node = Fake(text = hostile, description = hostile, packageName = hostile, className = hostile, viewId = hostile)
        val leaf = nodes(parse(WindowTreeXml.render(listOf(node))))[0]
        val expected = "a & b < c > d \" e ' f   g"
        for (name in listOf("text", "content-desc", "package", "class", "resource-id")) {
            assertEquals("attribute $name round-trips", expected, leaf.getAttribute(name))
        }
    }

    @Test
    fun anEmptyWindowListStillRendersAHierarchy() {
        val doc = parse(WindowTreeXml.render(emptyList()))
        assertEquals("hierarchy", doc.tagName)
        assertTrue(nodes(doc).isEmpty())
    }

    @Test
    fun escapeLeavesOrdinaryTextAlone() {
        assertEquals("Compose email", WindowTreeXml.escape("Compose email"))
        assertFalse(WindowTreeXml.escape("x<y").contains('<'))
    }
}
