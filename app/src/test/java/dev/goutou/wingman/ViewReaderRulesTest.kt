package dev.goutou.wingman

import dev.goutou.wingman.wechat.ImageCandidateSpec
import dev.goutou.wingman.wechat.selectImageCandidate
import org.junit.Assert.assertEquals
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
}
