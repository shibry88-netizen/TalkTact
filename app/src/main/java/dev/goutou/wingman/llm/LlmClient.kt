package dev.goutou.wingman.llm

import dev.goutou.wingman.config.ConfigData
import dev.goutou.wingman.proxy.ProxyProtocol
import dev.goutou.wingman.wechat.ChatMsg
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

data class LlmResult(val suggestion: Suggestion, val totalTokens: Int, val millis: Long, val model: String)

/**
 * 「接口自检」的结果：**只报连通性和延迟**，不含任何模型回复内容。
 *
 * 三段耗时分开量，好判断慢在哪一段：DNS 解析 / 裸 TCP 握手 / 一次完整往返。
 */
data class ProbeResult(
    val host: String?,
    val targetIp: String?,
    val dnsMs: Long,
    /** 裸 TCP 握手；null = 没测到（前置代理拦裸 TCP 很正常，不代表接口不通） */
    val connectMs: Long?,
    val totalMs: Long,
    val model: String,
    val totalTokens: Int,
)

/**
 * OpenAI 兼容接口客户端。
 *
 * 刻意用 HttpURLConnection 而不是 OkHttp：这段代码会被注入进微信进程，
 * 而微信自己也打包了 okhttp/okio，parent-first 的类加载会先拿到它那份，
 * 版本不一致就是 NoSuchMethodError。系统 API 没有撞车问题，也不用多带依赖。
 */
class LlmClient(
    private val cfg: ConfigData,
    /** 把「这次实际发出去的东西」交出去存档，null = 不记 */
    private val onTrace: ((String) -> Unit)? = null,
    /**
     * 这一路用的是**第二套接口**（分级模式的「风险评估」那一跳）。
     *
     * 只在走本地代理时有意义：代理只拿到一个 token，认不出「这一跳是谁」，所以这里带一个头
     * 告诉它该用第一套还是第二套的地址与 Key（见 [ProxyProtocol.HEADER_ENDPOINT]）。
     * 不带的话第二路会被代理按第一套转发 —— 症状是「第二套填对了却报 Key 不对」。
     */
    private val second: Boolean = false,
) {

    /** 当前接口形态。差异只有三处：URL 路径 / 请求体 / 鉴权头（见 llm/ApiShape.kt）。 */
    private val shape: ApiShape get() = shapeOf(cfg.apiShape)

    /**
     * 出站地址。
     *
     * 开了本地代理就走本机回环（路径写死在 [ProxyProtocol] 里）：那段代码被注入在微信进程里，
     * 手上只有一个随机 token，Key 在 App 进程那边 —— 这就是「Key 不出 App」的做法。
     */
    private fun outboundUrl(upstream: String): String =
        if (viaProxy()) ProxyProtocol.urlFor(cfg.proxyPort) else upstream

    /** 走不走本地代理。和回传给 App 显示的是同一份判断（ProxyProtocol.routeOf）。 */
    private fun viaProxy(): Boolean =
        ProxyProtocol.routeOf(cfg.proxyEnabled, cfg.proxyToken) == ProxyProtocol.ROUTE_PROXY

    // 出站凭据的规矩（踩过一次别忘）：**凭据由「这一跳到底走不走代理」决定，不看别的东西**。
    // 以前这里自己算代理、而 probe 的地址是直连的，于是自检出现了「连服务商、发代理 token」
    // 的组合 → 必然 401，回显的还正是那个随机 token。现在这个判断只在 once() 里算一次。
    // 直连时的鉴权头改由**接口形态**给（OpenAI/自定义 = Bearer；Anthropic = x-api-key + 版本头）。

    /** 发起前的前置检查。代理模式下**不看 Key** —— 它本来就该是空的（App 根本没推过来）。 */
    private fun requireReady() {
        if (viaProxy()) {
            if (cfg.proxyToken.isBlank()) {
                throw LlmException("本地代理的 token 是空的", "到 App 的「高级设置 → 本地代理」里关一下再开")
            }
        } else if (cfg.apiKey.isBlank()) {
            throw LlmException("还没填 API Key", "到「设置」里填地址和 Key")
        }
    }

    /**
     * @param roleContext 「角色」页攒下来的背景（TA 是你什么人 / 平时的关系 / 更早的聊天记录）。
     *   刻意拼进 user 消息而不是 system：skill 提示词要保持原样（App 里显示的字长、
     *   「最近一次调用」里核对的那份都是它），角色背景属于「这一次的素材」。
     */
    fun analyze(msgs: List<ChatMsg>, roleContext: String? = null): LlmResult =
        analyzeWith(cfg.prompt, msgs, roleContext)

    /**
     * 用指定的 system 提示词跑一次。
     *
     * 抽出来的原因：分级模式下两路各带自己的提示词（风险评估 / 写回复），
     * [analyze] 就是「用当前 skill 那一份」的快捷方式。
     */
    fun analyzeWith(
        systemText: String,
        msgs: List<ChatMsg>,
        roleContext: String? = null,
        /** 见 [SuggestionParser.parse]：分级模式的「风险评估」那一路要传 false */
        requireReplies: Boolean = true,
    ): LlmResult {
        requireReady()
        if (msgs.isEmpty()) throw LlmException("没读到聊天内容", null)

        val start = System.currentTimeMillis()
        // trace 要能原样看到「发出去的是什么」，所以用传进来的那份，而不是回头读 cfg
        // 顺序刻意是「跨请求不变的东西在前、每次都变的东西在后」：
        // system（skill）→ 角色档案 + 说话风格 → 聊天记录。
        // 这样同一个联系人的前两次之后，前缀就一直一样，能吃到服务端的**前缀缓存**
        // （OpenAI / DeepSeek 是按前缀自动命中的，命中价差一个数量级）。
        val userText = buildString {
            if (!roleContext.isNullOrBlank()) append(roleContext).append("\n\n")
            append("聊天记录（时间顺序，最后一条是对方刚发的）：\n").append(msgs.asTranscript())
            append("\n\n只输出系统要求的那个 JSON 对象，不要任何解释文字。")
        }
        // 请求体与鉴权头交给「接口形态」去组装（llm/ApiShape.kt）：
        // 形态差异只有三处 —— URL 路径 / 请求体 / 鉴权头，所以这里不再自己拼。
        // 严格 JSON 输出只加在「生成候选」这条路上（jsonMode）—— 改写和风格提炼走 complete()，
        // 它们本来就要纯文本，带上 json_object 反而会把返回变成 JSON 字符串。
        fun requestFor(system: String): ApiRequest = buildApiRequest(
            shape = shape,
            base = cfg.baseUrl,
            customPath = cfg.customPath,
            apiKey = cfg.apiKey,
            model = cfg.model,
            system = system,
            user = userText,
            temperature = cfg.temperature,
            maxTokens = cfg.maxTokens,
            jsonMode = cfg.jsonMode,
        )

        // 最多两次：第一次正常发；如果**模型没按契约回**（不是合法 JSON / 被截断），
        // 补一句「严格只输出 JSON」再试一次 —— 比让用户自己去点「重新识别」强。
        // 注意：网络类错误不在这里重试（post() 已经做过一次），免得把等待时间叠成三倍。
        var attempt = 0
        var tokensTotal = 0
        while (true) {
            val system = if (attempt == 0) systemText else systemText + "\n\n" + JSON_NUDGE
            try {
                val req = requestFor(system)
                val root = parseResponse(post(outboundUrl(req.url), req.body, authHeaders = req.authHeaders))
                val reply = parseApiReply(shape, root)
                    ?: throw LlmException(
                        "返回里没有正文（${shape.label}）",
                        "确认模型名是否可用、以及这个服务商是否真的支持所选的接口形态",
                    )
                val content = reply.content
                trace(system, userText, content)
                tokensTotal += reply.tokens
                val parsed = try {
                    SuggestionParser.parse(content, requireReplies)
                } catch (e: LlmException) {
                    trace(system, userText, "（解析失败，${if (attempt == 0) "补正后重试一次" else "不再重试"}）${e.message}\n---\n$content")
                    if (attempt == 0 && e.retryable) {
                        attempt = 1
                        continue
                    }
                    throw e
                }
                return LlmResult(
                    suggestion = parsed,
                    totalTokens = tokensTotal,
                    millis = System.currentTimeMillis() - start,
                    model = reply.model.ifBlank { cfg.model },
                )
            } catch (t: Throwable) {
                trace(system, userText, "（请求失败）${t.message}")
                throw t
            }
        }
    }

    /**
     * 通用的一次文本调用（目前只有「把我的说话风格提炼成 skill」用它）。
     *
     * 和 [analyze] 的区别：不要求模型返回 JSON，直接把 content 原文给回去。
     * 复用同一套 endpoint / post / 错误处理，省得两份请求代码各自漂移。
     */
    fun complete(systemText: String, userText: String): Pair<String, Int> {
        requireReady()
        // 提炼风格 / 单条改写不需要发散：温度压低，免得每次结果跳来跳去。
        // 明确不带 jsonMode：这里要的就是纯文本。
        val req = buildApiRequest(
            shape = shape,
            base = cfg.baseUrl,
            customPath = cfg.customPath,
            apiKey = cfg.apiKey,
            model = cfg.model,
            system = systemText,
            user = userText,
            temperature = 0.3,
            maxTokens = cfg.maxTokens,
            jsonMode = false,
        )
        val root = parseResponse(post(outboundUrl(req.url), req.body, authHeaders = req.authHeaders))
        val reply = parseApiReply(shape, root)
            ?: throw LlmException(
                "返回里没有正文（${shape.label}）",
                "确认模型名是否可用、以及这个服务商是否真的支持所选的接口形态",
            )
        return reply.content to reply.tokens
    }

    /**
     * 对**一条**已经生成好的候选做局部改写（「再短点 / 更正式 / 换个说法」）。
     *
     * 刻意走和 [complete] 同一条路：一句话进、一句话出，不要求 JSON ——
     * 既不碰候选列表的解析，也不会像「重新识别」那样把另外两条一起换掉。
     */
    fun rewrite(text: String, instruction: String): Pair<String, Int> {
        requireReady()
        val (content, tokens) = complete(REWRITE_SYSTEM, "原回复：$text\n要求：$instruction")
        val one = sanitizeOneLine(content)
        if (one.isBlank()) throw LlmException("模型没给出改写结果", "换个预设再试一次")
        return one to tokens
    }


    /** 把这次实际发出去的 system 提示词 / user 消息 / 模型原始返回交给调用方存档。 */
    private fun trace(systemText: String, userText: String, response: String) {
        val cb = onTrace ?: return
        val body = buildString {
            append("system 长度 = ${systemText.length} 字\n")
            append("-------- system 开头 300 字 --------\n")
            append(systemText.take(300))
            if (systemText.length > 300) append("\n…（后面省略 ${systemText.length - 300} 字）")
            append("\n\n-------- user 消息 --------\n").append(userText)
            append("\n\n-------- 模型原始返回 --------\n").append(response.take(1200))
        }
        runCatching { cb(body) }
    }


    /**
     * 「接口自检」用的一次最小请求 —— **只验连通性和延迟**。
     *
     * 和 [analyze] 的三点区别，都是刻意的：
     *
     * ① 请求最小：**只有一句 `user: "ping"`**，既不拼当前 skill 也不拼角色档案 ——
     *    自检不该受它们影响（它跟「你配了哪套提示词」一点关系都没有）；
     * ② `max_tokens = 1`：只让服务端吐一个 token，尽量不花钱；
     * ③ **不看模型回了什么**：HTTP 2xx 且返回是 JSON 就算通。
     *
     * 第 ③ 条是这次修的重点。以前这里拿模型回复去跑 `SuggestionParser.parse()`，
     * 于是「模型话多了 / 被 max_tokens 截断 / 没按 JSON 契约回」都会被报成**接口不通** ——
     * 那是模型听不听话的问题，不是连接的问题。想验模型是否按契约回复，去「试一试」页。
     */
    fun probe(): ProbeResult {
        // 自检永远是直连的（地址见下），所以这里要的就是**真 Key** ——
        // 代理模式下 `proxyToken` 不是凭据，拿它去连服务商只会换回一个 401。
        if (cfg.apiKey.isBlank()) throw LlmException("还没填 API Key", "到「设置 → 高级设置」里填地址和 Key")
        // 自检也按**当前形态**发：否则选了 Anthropic 的人会看到「接口 404」，
        // 其实只是形态没对上（跟模型听不听话一点关系都没有）。
        val probeReq = buildApiRequest(
            shape = shape,
            base = cfg.baseUrl,
            customPath = cfg.customPath,
            apiKey = cfg.apiKey,
            model = cfg.model,
            system = "",
            user = "ping",
            temperature = 0.0,
            maxTokens = 1,
            jsonMode = false,
        )
        val url = probeReq.url
        val host = NetInfo.hostOf(url)

        // ① DNS 解析耗时（顺便拿到目标 IP）
        val dnsStart = System.currentTimeMillis()
        val targetIp = NetInfo.resolve(host)
        val dnsMs = System.currentTimeMillis() - dnsStart

        // ② 裸 TCP 握手：这才是「连接延迟」
        val connectMs = NetInfo.tcpConnectMs(host, NetInfo.portOf(url))

        // ③ 一次最小往返
        val start = System.currentTimeMillis()
        // direct = true：自检量的是**真实目标机**的 DNS/TCP 延迟，走回环就变成量 127.0.0.1，没意义。
        // 「代理通不通」由代理卡片那行「微信侧最近一次走的是：本地代理 ✅」负责。
        val root = parseResponse(post(url, probeReq.body, direct = true, authHeaders = probeReq.authHeaders))
        return ProbeResult(
            host = host,
            targetIp = targetIp,
            dnsMs = dnsMs,
            connectMs = connectMs,
            totalMs = System.currentTimeMillis() - start,
            // 自检**不要求正文**（只要 2xx 且是 JSON 就算通），所以取不到就给兜底值
            model = parseApiReply(shape, root)?.model?.ifBlank { cfg.model } ?: cfg.model,
            totalTokens = parseApiReply(shape, root)?.tokens ?: 0,
        )
    }

    private fun parseResponse(body: String) = run {
        val root = Json.parse(body)
            ?: throw LlmException("接口返回的不是 JSON（HTTP 200）", "可能是中转站返回了 HTML 错误页")
        root.at("error", "message").asStr()?.let { throw LlmException("接口报错：$it", null) }
        root
    }

    /**
     * 失败重试一次（只对超时/5xx/429 这种「可能只是碰巧」的错误）。
     *
     * @param direct 这一跳**强制直连**、不走本地代理（自检用）。
     */
    private fun post(
        url: String,
        payload: String,
        direct: Boolean = false,
        authHeaders: Map<String, String> = emptyMap(),
    ): String {
        var last: LlmException? = null
        for (attempt in 0..1) {
            try {
                return once(url, payload, direct, authHeaders)
            } catch (e: LlmException) {
                last = e
                if (!e.retryable || attempt == 1) break
                try {
                    Thread.sleep(500L * (attempt + 1))
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        throw last ?: LlmException("请求失败", null)
    }

    private fun once(
        url: String,
        payload: String,
        direct: Boolean = false,
        authHeaders: Map<String, String> = emptyMap(),
    ): String {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (t: Throwable) {
            throw LlmException("接口地址不合法：${t.message}", "地址要带 http(s)://，且写到 /v1")
        }
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 12_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            // 「走不走代理」在这里只算一次，凭据和接口头都用它 —— 两者分开判断过一次，出了 401
            val proxyRoute = !direct && viaProxy()
            if (proxyRoute) {
                // 代理模式：凭据是**回环 token**（真 Key 在 App 进程那边）。
                // ⚠️ 代理只转发 OpenAI 兼容形态 —— 选了 Responses / Anthropic 还开着代理时，
                // 界面上会提示把代理关掉（见 ApiShape.usableThroughProxy）。
                conn.setRequestProperty("Authorization", "Bearer ${cfg.proxyToken}")
                // 代理那侧认不出「这一跳是谁」，得靠这个头告诉它用哪套接口（只对代理有意义）
                conn.setRequestProperty(ProxyProtocol.HEADER_ENDPOINT, ProxyProtocol.endpointHeader(second))
            } else {
                // 直连：鉴权头由**接口形态**决定（OpenAI / 自定义 = Bearer；Anthropic = x-api-key + 版本头）
                authHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                if (authHeaders.isEmpty()) conn.setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
            }
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code in 200..299) return body

            val detail = Json.parse(body)?.at("error", "message").asStr() ?: body.take(160).replace('\n', ' ')
            throw when (code) {
                401, 403 -> LlmException(
                    "鉴权失败（HTTP $code）",
                    buildString {
                        append("Key 不对，或这个 Key 没有该模型的权限")
                        if (second) {
                            append("（**这是第二套接口**：它的 Key 留空时会复用第一套的")
                            append(" —— 两家服务商不同的话，就会拿第一套的 Key 去请求第二套的地址）")
                        }
                        append("：").append(detail)
                    },
                )
                404 -> LlmException(
                    "接口返回 404",
                    buildString {
                        append("请求地址：").append(url)
                        append("；模型：").append(cfg.model.ifBlank { "（空）" })
                        if (detail.isNotBlank()) append("；服务商：").append(detail)
                        append("。如果地址和模型都正确，检查服务商是否支持 /chat/completions；不要只按“地址要写到 /v1”处理")
                    },
                )
                429 -> LlmException("被限流了（HTTP 429）", "等几秒再点「重新识别」，或把「最短调用间隔」调大", retryable = true)
                in 500..599 -> LlmException("服务端错误（HTTP $code）", detail, retryable = true)
                else -> LlmException("HTTP $code", detail)
            }
        } catch (e: SocketTimeoutException) {
            throw LlmException("请求超时", "网络慢或接口没响应（当前 12s 连接 / 60s 读取）", retryable = true)
        } catch (e: IOException) {
            throw LlmException("网络错误：${e.message ?: e.javaClass.simpleName}", "检查手机网络；需要代理的接口在微信进程里可能连不上", retryable = true)
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}

/** 模型没按契约回时的补正指令（只在第二次尝试时追加到 system 末尾）。 */
private const val JSON_NUDGE = """【补正】你上一次的输出不是合法 JSON，或者被截断了。

这次请严格只输出那一个 JSON 对象：不要解释、不要 markdown 代码块、不要在 JSON 前后加任何文字，
也不要把字段值写太长以免再次被截断。"""

/**
 * 把「接口地址」拼成 chat/completions 的完整 URL。
 *
 * 抽成顶层纯函数是为了让**本地代理**用同一份拼法 —— 以前这段逻辑只在 LlmClient 里，
 * 代理另写一份的话，两边对「地址该不该带 /v1」的处理迟早会不一致。
 */
internal fun chatCompletionsUrl(base: String): String {
    val u = base.trim().trimEnd('/')
    return if (u.endsWith("/chat/completions")) u else "$u/chat/completions"
}

/**
 * 「单条改写」的三个预设：左边是按钮文字，右边那句原样发给模型。
 * 放这里是为了让微信内的卡片和 App 的「试一试」页共用同一份 —— 改一处两边都变。
 */
val REWRITE_PRESETS: List<Pair<String, String>> = listOf(
    "再短点" to "更短：控制在 15 个字以内，意思不变",
    "更正式" to "语气更正式、礼貌一些，但别变成官腔",
    "换个说法" to "换一种说法，意思不变",
)

private const val REWRITE_SYSTEM = """你是微信聊天回复的润色助手：用户给你一条已经写好的回复，你按他的要求改一版。

要求：
- 只输出改写后的那一句话。不要引号、不要解释、不要 markdown、不要「改写后：」这类前缀。
- 保持原意和语气，长度不要明显超过原文（要求「再短点」时就该更短）。
- 像真人发微信：口语、短句；不用客服腔、不用感叹号，不写「收到」「这边」「同步一下」这类话。
- 涉及钱、承诺、账号、密码、验证码时不要加码，也不要替用户答应任何事。"""

/**
 * 从模型返回里抠出「那一句话」。
 *
 * 即便提示词写得很死，模型还是经常加引号、加「改写后：」前缀、裹一层 markdown 围栏，
 * 或者多解释两行 —— 这里只留第一行干净文本，免得整段塞进输入框。
 */
internal fun sanitizeOneLine(raw: String): String {
    var s = raw.trim()
    Regex("```(?:[a-zA-Z]*)\\s*([\\s\\S]*?)```").find(s)?.let { s = it.groupValues[1].trim() }
    s = s.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    Regex("^(改写后|改写|回复|结果|输出)\\s*[:：]\\s*").find(s)?.let { s = s.removePrefix(it.value) }
    return s.trim('"', '\'', '“', '”', '‘', '’', '「', '」', '『', '』', '`', '：', ':', ' ').trim()
}
