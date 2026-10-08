package dev.goutou.wingman.llm

/**
 * 接口形态。
 *
 * 存在的理由：以前只有一种形状（OpenAI 的 Chat Completions），但**它并不是唯一**——
 * OpenAI 自己的新一代 Responses API 用 `input` / `instructions` 而不是 `messages`，
 * Anthropic 的 `/v1/messages` 把 system 拎成独立字段、鉴权走 `x-api-key`。
 * 想用它们的人以前只能自己搭一层中转。
 *
 * 三种形态的差异**只有三处**：URL 路径、请求体组装、鉴权头 ——
 * 所以这里抽成枚举 + 两个纯函数（[buildApiRequest] / [parseApiReply]），单测见 `ApiShapeTest`。
 */
enum class ApiShape(val id: String, val label: String, val path: String, val note: String) {
    OPENAI("openai", "OpenAI 兼容（Chat Completions）", "chat/completions", "绝大多数服务商选这个"),
    RESPONSES("responses", "OpenAI Responses", "responses", "用 instructions / input，不是 messages"),
    ANTHROPIC("anthropic", "Anthropic Messages", "messages", "鉴权走 x-api-key，且 max_tokens 必填"),
    CUSTOM("custom", "自定义路径", "", "请求体仍按 Chat Completions 组装"),
    ;

    /**
     * 走本地代理时能不能用。
     *
     * 代理那一跳只把 body 原样转发到 `{base}/chat/completions`，**不认识别的形态**；
     * 而且它是「Key 不出 App 进程」的核心路径，为了形态去动它不划算。
     * 所以非 OpenAI 兼容的形态请直连（界面上会提示）。
     */
    val usableThroughProxy: Boolean get() = this == OPENAI || this == CUSTOM
}

/** 字符串 id → 形态。认不出来一律当 [ApiShape.OPENAI]（旧配置没有这个字段，正好走默认）。 */
fun shapeOf(id: String): ApiShape = ApiShape.values().firstOrNull { it.id == id } ?: ApiShape.OPENAI

/**
 * 把「接口地址」拼成完整 URL。
 *
 * [customPath] 只在 [ApiShape.CUSTOM] 时有意义（用户自己填的路径，前后斜杠都容错）。
 * 自定义形态且路径留空时原样返回 base —— 有些人填的地址本身就是完整端点。
 */
internal fun apiUrl(shape: ApiShape, base: String, customPath: String = ""): String {
    val u = base.trim().trimEnd('/')
    if (u.isBlank()) return u
    val p = if (shape == ApiShape.CUSTOM) customPath.trim().trim('/') else shape.path
    if (p.isBlank()) return u
    return if (u.endsWith("/$p")) u else "$u/$p"
}

/** 一次出站请求的三件套。 */
internal data class ApiRequest(val url: String, val body: String, val authHeaders: Map<String, String>)

/**
 * 组装请求体与鉴权头（纯函数）。
 *
 * 通用规矩：
 * - [system] 为空时**不塞空字段**（自检那条最小请求就是 system 为空的）；
 * - `max_tokens <= 0` 表示「不传」（交给服务端默认），只有 Anthropic 例外 —— 它必填。
 */
internal fun buildApiRequest(
    shape: ApiShape,
    base: String,
    customPath: String,
    apiKey: String,
    model: String,
    system: String,
    user: String,
    temperature: Double,
    maxTokens: Int,
    jsonMode: Boolean,
): ApiRequest {
    val fields = LinkedHashMap<String, JsonValue>()
    fields["model"] = str(model)
    when (shape) {
        ApiShape.OPENAI, ApiShape.CUSTOM -> {
            fields["temperature"] = num(temperature)
            // 严格 JSON 只作用在「生成候选」这条路上（调用方传 jsonMode）
            if (jsonMode) fields["response_format"] = obj("type" to str("json_object"))
            if (maxTokens > 0) fields["max_tokens"] = num(maxTokens)
            if (system.isNotBlank()) {
                fields["messages"] = arr(
                    listOf(
                        obj("role" to str("system"), "content" to str(system)),
                        obj("role" to str("user"), "content" to str(user)),
                    ),
                )
            } else {
                fields["messages"] = arr(listOf(obj("role" to str("user"), "content" to str(user))))
            }
        }
        ApiShape.RESPONSES -> {
            // Responses：system 走 instructions，输入走 input；输出上限是 max_output_tokens
            if (system.isNotBlank()) fields["instructions"] = str(system)
            fields["input"] = str(user)
            if (maxTokens > 0) fields["max_output_tokens"] = num(maxTokens)
        }
        ApiShape.ANTHROPIC -> {
            // Anthropic：system 是顶层字段，messages 只放 user/assistant；max_tokens **必填**
            if (system.isNotBlank()) fields["system"] = str(system)
            fields["max_tokens"] = num(if (maxTokens > 0) maxTokens else ANTHROPIC_FALLBACK_MAX_TOKENS)
            fields["messages"] = arr(listOf(obj("role" to str("user"), "content" to str(user))))
        }
    }
    val auth = if (shape == ApiShape.ANTHROPIC) {
        // Claude 不认 Bearer：凭据走 x-api-key，另外必须带版本头
        linkedMapOf("x-api-key" to apiKey, "anthropic-version" to ANTHROPIC_VERSION)
    } else {
        linkedMapOf("Authorization" to "Bearer $apiKey")
    }
    return ApiRequest(apiUrl(shape, base, customPath), Json.encode(JsonValue.Obj(fields)), auth)
}

/** 从返回里取「正文 / token 数 / 模型名」。取不到正文返回 null（调用方负责报错）。 */
internal fun parseApiReply(shape: ApiShape, root: JsonValue): ApiReply? = when (shape) {
    ApiShape.OPENAI, ApiShape.CUSTOM -> {
        val c = root.at("choices", "0", "message", "content").asStr() ?: return null
        ApiReply(c, root.at("usage", "total_tokens").asInt() ?: 0, root.at("model").asStr().orEmpty())
    }
    ApiShape.RESPONSES -> {
        // 原生响应是 output[] 数组；有的 SDK / 中转会额外给一个聚合好的 output_text —— 两种都认
        val c = root.at("output_text").asStr()
            ?: root.at("output", "0", "content", "0", "text").asStr()
            ?: return null
        ApiReply(c, root.at("usage", "total_tokens").asInt() ?: 0, root.at("model").asStr().orEmpty())
    }
    ApiShape.ANTHROPIC -> {
        val c = root.at("content", "0", "text").asStr() ?: return null
        // Anthropic 把用量拆成输入/输出两段，我们这边统一成「合计」，跟别的形态可比
        val inTok = root.at("usage", "input_tokens").asInt() ?: 0
        val outTok = root.at("usage", "output_tokens").asInt() ?: 0
        ApiReply(c, inTok + outTok, root.at("model").asStr().orEmpty())
    }
}

internal data class ApiReply(val content: String, val tokens: Int, val model: String)

/** Anthropic 的 max_tokens 是必填项：没填就 400，这里给一个保守的兜底值。 */
private const val ANTHROPIC_FALLBACK_MAX_TOKENS = 1024
private const val ANTHROPIC_VERSION = "2023-06-01"
