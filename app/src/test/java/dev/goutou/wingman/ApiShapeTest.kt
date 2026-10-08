package dev.goutou.wingman

import dev.goutou.wingman.llm.ApiShape
import dev.goutou.wingman.llm.Json
import dev.goutou.wingman.llm.apiUrl
import dev.goutou.wingman.llm.buildApiRequest
import dev.goutou.wingman.llm.parseApiReply
import dev.goutou.wingman.llm.shapeOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 接口形态（缓做 2）。
 *
 * 这一条钉的是「换个接口形状不用改请求逻辑」：URL 路径 / 请求体 / 鉴权头三处，
 * 三种形态各自长什么样、以及认不出来时回落到 OpenAI 兼容。
 */
class ApiShapeTest {

    // ---------- URL 拼接 ----------

    @Test
    fun `openai 形态补上 chat-completions`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            apiUrl(ApiShape.OPENAI, "https://api.openai.com/v1"),
        )
    }

    @Test
    fun `地址已经带路径时不重复拼`() {
        assertEquals(
            "https://x.y/v1/chat/completions",
            apiUrl(ApiShape.OPENAI, "https://x.y/v1/chat/completions"),
        )
    }

    @Test
    fun `responses 与 anthropic 各自的路径`() {
        assertEquals("https://a.b/v1/responses", apiUrl(ApiShape.RESPONSES, "https://a.b/v1"))
        assertEquals("https://a.b/v1/messages", apiUrl(ApiShape.ANTHROPIC, "https://a.b/v1"))
    }

    @Test
    fun `自定义形态用用户填的路径且容错斜杠`() {
        assertEquals("https://a.b/v1/my/path", apiUrl(ApiShape.CUSTOM, "https://a.b/v1", "/my/path/"))
        // 路径留空 = 这个地址本身就是完整端点
        assertEquals("https://a.b/v1/full/endpoint", apiUrl(ApiShape.CUSTOM, "https://a.b/v1/full/endpoint", ""))
    }

    @Test
    fun `认不出来的 id 一律当 openai`() {
        assertEquals(ApiShape.OPENAI, shapeOf(""))
        assertEquals(ApiShape.OPENAI, shapeOf("某种没见过的形态"))
        assertEquals(ApiShape.ANTHROPIC, shapeOf("anthropic"))
    }

    // ---------- 请求体 ----------

    @Test
    fun `openai 形态是 messages 加 Bearer`() {
        val r = buildApiRequest(
            ApiShape.OPENAI, "https://a.b/v1", "", "sk-1", "m1",
            "SYS", "USER", 0.8, 400, jsonMode = true,
        )
        assertTrue(r.body.contains("\"role\":\"system\""))
        assertTrue(r.body.contains("\"role\":\"user\""))
        assertTrue(r.body.contains("\"response_format\""))
        assertTrue(r.body.contains("\"max_tokens\":400"))
        assertEquals("Bearer sk-1", r.authHeaders["Authorization"])
        assertEquals("https://a.b/v1/chat/completions", r.url)
    }

    @Test
    fun `system 为空时不塞空的 system 消息`() {
        // 自检那条最小请求就是 system 为空的 —— 空 system 有些服务商会直接 400
        val r = buildApiRequest(
            ApiShape.OPENAI, "https://a.b/v1", "", "k", "m",
            "", "ping", 0.0, 1, jsonMode = false,
        )
        assertTrue(r.body.contains("\"role\":\"user\""))
        assertTrue(!r.body.contains("\"role\":\"system\""))
    }

    @Test
    fun `responses 形态用 instructions 与 input`() {
        val r = buildApiRequest(
            ApiShape.RESPONSES, "https://a.b/v1", "", "k", "m",
            "SYS", "USER", 0.5, 300, jsonMode = false,
        )
        assertTrue(r.body.contains("\"instructions\":\"SYS\""))
        assertTrue(r.body.contains("\"input\":\"USER\""))
        assertTrue(r.body.contains("\"max_output_tokens\":300"))
        assertTrue(!r.body.contains("\"messages\""))
        assertEquals("https://a.b/v1/responses", r.url)
    }

    @Test
    fun `anthropic 形态把 system 拎出来且 max_tokens 必填`() {
        val r = buildApiRequest(
            ApiShape.ANTHROPIC, "https://api.anthropic.com/v1", "", "sk-ant", "claude",
            "SYS", "USER", 0.7, 0, jsonMode = false,
        )
        assertTrue(r.body.contains("\"system\":\"SYS\""))
        // maxTokens <= 0 时不能真的不传：Anthropic 必填，这里给兜底值
        assertTrue(r.body.contains("\"max_tokens\":1024"))
        assertTrue(!r.body.contains("\"temperature\""))
        assertEquals("sk-ant", r.authHeaders["x-api-key"])
        assertTrue(r.authHeaders.containsKey("anthropic-version"))
        assertNull(r.authHeaders["Authorization"])
        assertEquals("https://api.anthropic.com/v1/messages", r.url)
    }

    // ---------- 响应解析 ----------

    @Test
    fun `openai 响应取 choices 里的正文`() {
        val root = Json.parse(
            """{"model":"m1","choices":[{"message":{"content":"你好"}}],"usage":{"total_tokens":42}}""",
        )!!
        val r = parseApiReply(ApiShape.OPENAI, root)!!
        assertEquals("你好", r.content)
        assertEquals(42, r.tokens)
        assertEquals("m1", r.model)
    }

    @Test
    fun `responses 响应两种形状都认`() {
        val agg = Json.parse("""{"output_text":"聚合好的","usage":{"total_tokens":7}}""")!!
        assertEquals("聚合好的", parseApiReply(ApiShape.RESPONSES, agg)!!.content)

        val native = Json.parse(
            """{"output":[{"content":[{"type":"output_text","text":"原生的"}]}],"usage":{"total_tokens":8}}""",
        )!!
        assertEquals("原生的", parseApiReply(ApiShape.RESPONSES, native)!!.content)
    }

    @Test
    fun `anthropic 响应把两段用量合成一个数`() {
        val root = Json.parse(
            """{"model":"claude","content":[{"type":"text","text":"好的"}],"usage":{"input_tokens":10,"output_tokens":5}}""",
        )!!
        val r = parseApiReply(ApiShape.ANTHROPIC, root)!!
        assertEquals("好的", r.content)
        assertEquals(15, r.tokens)
    }

    @Test
    fun `拿错形态解析不出东西就返回 null`() {
        val anthropicBody = Json.parse("""{"content":[{"type":"text","text":"x"}]}""")!!
        assertNull(parseApiReply(ApiShape.OPENAI, anthropicBody))
        val openaiBody = Json.parse("""{"choices":[{"message":{"content":"x"}}]}""")!!
        assertNull(parseApiReply(ApiShape.ANTHROPIC, openaiBody))
    }
}
