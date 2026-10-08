package dev.goutou.wingman.wechat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 归档隐私闸（[sensitiveArchiveFilter]）。
 *
 * 这一条钉的是「**不外发**」到底成不成立：命中敏感词的消息必须进不了角色档案，
 * 因为档案下一轮会作为「角色背景」进提示词 —— 只拦当次调用是不够的。
 */
class SensitiveArchiveTest {

    private fun msg(text: String, fromMe: Boolean = false) = ChatMsg(
        text = text,
        fromMe = fromMe,
        who = if (fromMe) "" else "对方",
        attachment = false,
    )

    @Test
    fun `关掉检查时原样放行`() {
        val msgs = listOf(msg("我的手机号 13800138000"), msg("随便聊两句"))
        val (kept, skipped) = sensitiveArchiveFilter(msgs, gate = false)
        assertSame(msgs, kept)
        assertEquals(0, skipped)
    }

    @Test
    fun `干净的消息一条都不拦`() {
        val msgs = listOf(msg("今天吃什么"), msg("好呀", fromMe = true))
        val (kept, skipped) = sensitiveArchiveFilter(msgs, gate = true)
        assertEquals(2, kept.size)
        assertEquals(0, skipped)
    }

    @Test
    fun `命中手机号的被拦下、其它照常留下`() {
        val msgs = listOf(msg("今天吃什么"), msg("打给我 13800138000"), msg("好呀", fromMe = true))
        val (kept, skipped) = sensitiveArchiveFilter(msgs, gate = true)
        assertEquals(1, skipped)
        assertEquals(listOf("今天吃什么", "好呀"), kept.map { it.text })
    }

    @Test
    fun `我的和对方的都拦`() {
        val msgs = listOf(msg("我的验证码是 8888", fromMe = true), msg("把密码发我"))
        val (kept, skipped) = sensitiveArchiveFilter(msgs, gate = true)
        assertEquals(2, skipped)
        assertEquals(0, kept.size)
    }

    @Test
    fun `空列表不炸`() {
        val (kept, skipped) = sensitiveArchiveFilter(emptyList(), gate = true)
        assertEquals(0, kept.size)
        assertEquals(0, skipped)
    }
}
