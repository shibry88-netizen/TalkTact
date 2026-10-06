package dev.goutou.wingman

import dev.goutou.wingman.wechat.*
import org.junit.Assert.*
import org.junit.Test

class ViewReaderRulesTest {
    private fun c(w: Int, h: Int, depth: Int = 1, leaf: Boolean = true, avatar: Boolean = false, square: Boolean = false, big: Boolean = false) =
        ImageCandidateSpec(w, h, depth, leaf, avatar, square, big)

    @Test fun `prefers largest big leaf`() {
        assertEquals(1, selectImageCandidate(listOf(c(500, 400, big = true), c(640, 480, big = true))))
    }

    @Test fun `prefers deepest big container after leaves`() {
        assertEquals(1, selectImageCandidate(listOf(c(500, 400, 2, leaf = false, big = true), c(500, 400, 4, leaf = false, big = true))))
    }

    @Test fun `fallback excludes avatars and square stickers`() {
        assertEquals(2, selectImageCandidate(listOf(c(80, 80, avatar = true), c(100, 100, square = true), c(200, 120))))
    }

    @Test fun `returns no candidate when only noise remains`() {
        assertEquals(-1, selectImageCandidate(listOf(c(80, 80, avatar = true), c(100, 100, square = true))))
    }

    private fun title(text: String = "小明", x: Int = 400, y: Int = 50, w: Int = 200, h: Int = 30, size: Float = 20f) =
        TitleCandidateSpec(text, w, h, x, y, size)

    @Test fun `title rejects status UI dump and avoided text`() {
        for (text in listOf("", "返回", "对方正在输入…", "连接中…", "android.widget.TextView", "a".repeat(33))) {
            assertNull(text, chatTitleScore(title(text), 1000, 1f, 14, 120))
        }
        assertNull(chatTitleScore(title(), 1000, 1f, 14, 120, setOf("小明")))
        assertNotNull(chatTitleScore(title("www.baidu.com"), 1000, 1f, 14, 120))
        assertNotNull(chatTitleScore(title("a".repeat(32)), 1000, 1f, 14, 120))
    }

    @Test fun `title band is inclusive and wide fallback remains possible`() {
        assertNotNull(chatTitleScore(title(y = 14), 1000, 1f, 14, 120))
        assertNotNull(chatTitleScore(title(y = 120), 1000, 1f, 14, 120))
        assertNull(chatTitleScore(title(y = 13), 1000, 1f, 14, 120))
        assertNull(chatTitleScore(title(y = 121), 1000, 1f, 14, 120))
        assertNotNull(chatTitleScore(title(y = 121), 1000, 1f, 14, 174))
    }

    @Test fun `title centering size and density retain thresholds`() {
        assertNotNull(chatTitleScore(title(x = 560), 1000, 1f, 14, 120))
        assertNull(chatTitleScore(title(x = 561), 1000, 1f, 14, 120))
        assertNotNull(chatTitleScore(title(w = 48, h = 28, x = 476), 1000, 2f, 14, 120))
        assertNull(chatTitleScore(title(w = 47, h = 28, x = 476), 1000, 2f, 14, 120))
        assertNull(chatTitleScore(title(w = 48, h = 27, x = 476), 1000, 2f, 14, 120))
        assertEquals(1015, chatTitleScore(title(), 1000, 1f, 14, 120))
        assertEquals(1020, chatTitleScore(title(size = 100f), 1000, 1f, 14, 120))
        assertTrue(chatTitleScore(title(), 1000, 1f, 14, 120)!! >
            chatTitleScore(title(x = 450), 1000, 1f, 14, 120)!!)
    }

    @Test fun `conversation container requires visible intersecting sufficiently tall list`() {
        fun container(shown: Boolean = true, children: Int = 3, list: Boolean = true, h: Int = 250, top: Int = 0) =
            isConversationListContainer(shown, children, list, h, top, 1000)
        assertTrue(container())
        assertTrue(container(top = -249))
        assertTrue(container(top = 999))
        assertFalse(container(top = -250))
        assertFalse(container(top = 1000))
        assertFalse(container(h = 249))
        assertFalse(container(h = 0))
        assertFalse(container(shown = false))
        assertFalse(container(children = 2))
        assertFalse(container(list = false))
    }

    @Test fun `conversation avatar preserves size squareness and left boundary`() {
        fun avatar(w: Int = 24, h: Int = 24, left: Int = 0, density: Float = 1f) =
            isConversationAvatar(w, h, left, 1000, density)
        assertTrue(avatar())
        assertTrue(avatar(w = 84, h = 80, left = 299))
        assertFalse(avatar(w = 23))
        assertFalse(avatar(w = 85, h = 85))
        assertFalse(avatar(h = 0))
        assertFalse(avatar(h = 29))
        assertFalse(avatar(left = 300))
        assertTrue(avatar(w = 48, h = 56, density = 2f))
        assertFalse(avatar(w = 48, h = 57, density = 2f))
    }

    @Test fun `bubble colors preserve alpha and strict green thresholds`() {
        fun px(alpha: Int, r: Int, g: Int, b: Int) = (alpha shl 24) or (r shl 16) or (g shl 8) or b
        assertEquals(Side.UNKNOWN, classifyBubbleColor(px(23, 100, 130, 100)))
        assertEquals(Side.ME, classifyBubbleColor(px(24, 100, 117, 106)))
        assertEquals(Side.OTHER, classifyBubbleColor(px(255, 100, 116, 100)))
        assertEquals(Side.OTHER, classifyBubbleColor(px(255, 100, 117, 107)))
        assertEquals(Side.OTHER, classifyBubbleColor(0xFFFFFFFF.toInt()))
        assertEquals(765, colorDistance(0xFF000000.toInt(), 0xFFFFFFFF.toInt()))
        assertEquals(0, colorDistance(0x00112233, 0xFF112233.toInt()))
    }

    @Test fun `view dumps rejected while domains and normal names survive`() {
        for (text in listOf("android.widget.TextView", "x.y.Widget", "x.y.Widget{abc}", "com.tencent.widget", " kotlin.collections.List ")) {
            assertTrue(text, looksLikeViewDump(text))
        }
        for (text in listOf("", "www.baidu.com", "小明", "小明{备注}")) {
            assertFalse(text, looksLikeViewDump(text))
        }
    }
}
