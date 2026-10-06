package dev.goutou.wingman

import dev.goutou.wingman.wechat.*
import org.junit.Assert.*
import org.junit.Test

class ConversationStateTest {
    private val messages = listOf(ChatMsg(false, "好的"))

    @Test fun `same message in different conversations has separate replies and records`() {
        assertNotEquals(conversationRequestKey("张三", "cfg", null, messages), conversationRequestKey("项目群", "cfg", null, messages))
        assertNotEquals(roleObservationKey("张三", false, "好的"), roleObservationKey("李四", false, "好的"))
        assertEquals(roleObservationKey("张三", false, "好的"), roleObservationKey("张三", false, "好的"))
    }

    @Test fun `style profile history and settings changes invalidate cached reply`() {
        val original = conversationRequestKey("张三", "cfg", "风格A\n同事\n旧记录A", messages)
        for ((settings, context) in listOf(
            "cfg" to "风格B\n同事\n旧记录A",
            "cfg" to "风格A\n朋友\n旧记录A",
            "cfg" to "风格A\n同事\n旧记录B",
            "model2" to "风格A\n同事\n旧记录A",
            "cfg" to null,
        )) assertNotEquals(original, conversationRequestKey("张三", settings, context, messages))
        assertEquals(original, conversationRequestKey("张三", "cfg", "风格A\n同事\n旧记录A", messages))
    }

    @Test fun `entire transcript including author direction and OCR participates in cache key`() {
        val original = conversationRequestKey("群", "cfg", null, messages)
        for (changed in listOf(
            listOf(ChatMsg(true, "好的")),
            listOf(ChatMsg(false, "好的", "小明")),
            listOf(ChatMsg(false, "好的", attachment = true)),
            listOf(ChatMsg(false, "前文")) + messages,
            listOf(ChatMsg(false, "[图片] 新文字")),
        )) assertNotEquals(original, conversationRequestKey("群", "cfg", null, changed))
    }

    @Test fun `length prefixed keys distinguish delimiter text and scopes`() {
        assertNotEquals(conversationDigest("a|b", "c"), conversationDigest("a", "b|c"))
        assertNotEquals(conversationDigest("ab", "c"), conversationDigest("a", "bc"))
        assertNotEquals(roleObservationKey("张三", true, "好的"), roleObservationKey("张三", false, "好的"))
        assertFalse(conversationRequestKey("张三", "cfg", "私人档案", messages).contains("私人档案"))
    }
}
