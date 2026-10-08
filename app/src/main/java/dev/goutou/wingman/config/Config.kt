package dev.goutou.wingman.config

import android.content.Context
import android.content.SharedPreferences
import dev.goutou.wingman.llm.DEFAULT_PROMPT

/**
 * 装到系统里的包名（= build.gradle 里的 applicationId）。
 *
 * 注意它和 [NAMESPACE] 是**两回事**：namespace 是 Kotlin 包名 / R 类所在的位置，
 * 一直没变；applicationId 为了上 LSPosed 官方模块库（要求包名可验证）改成了
 * io.github.shibry88_netizen.talktact。
 * 用到类名的地方（比如 hook 自己）必须用 NAMESPACE，别用这个。
 */
const val MODULE_PKG = "io.github.shibry88_netizen.talktact"

/** Kotlin 包名。类名、R 类都在这儿，改名要动整个源码树，所以刻意保持不变。 */
const val NAMESPACE = "dev.goutou.wingman"
const val PREF_NAME = "cfg"
const val DEFAULT_BASE = "https://api.openai.com/v1"
const val DEFAULT_MODEL = "gpt-4o-mini"

/** 跨进程读写的 key 集中放一处，免得上游（App）和下游（注入微信的代码）写错字符串。 */
object Keys {
    const val BASE = "base_url"
    const val KEY = "api_key"
    const val MODEL = "model"
    /** 接口形态：openai / responses / anthropic / custom（见 llm/ApiShape.kt） */
    const val API_SHAPE = "api_shape"
    /** 「自定义路径」形态时的路径（留空 = 地址本身就是完整端点） */
    const val CUSTOM_PATH = "custom_path"
    /** 在「服务商」下拉里选过谁（只用于回显 + 显示那家的注意事项，不影响请求） */
    const val PROVIDER = "provider_preset"
    const val PROMPT = "prompt_v3"
    const val ENABLED = "enabled"
    const val CTX = "ctx"
    const val TEMPERATURE = "temperature"
    const val MAX_TOKENS = "max_tokens"
    const val MIN_INTERVAL = "min_interval"
    const val SENSITIVE = "allow_sensitive"
    const val SCOPE_OK = "scope_ok"
    const val HEARTBEAT = "heartbeat_at"

    /**
     * 实时探测（App 问 → 注入侧答）：
     * App 写 [PROBE_REQ]，微信进程下一轮读到就回一条广播，Receiver 记下 [PROBE_ACK] 与 [PROBE_INFO]。
     * 「模块是否生效」以前只能看 [HEARTBEAT]（6 小时内有心跳就算），那回答的是「曾经跑过没」。
     */
    const val PROBE_REQ = "probe_req"
    const val PROBE_ACK = "probe_ack"
    const val PROBE_INFO = "probe_info"
    const val CALLS = "stat_calls"
    const val TOKENS = "stat_tokens"
    const val DIAG = "diag"
    const val DIAG_AT = "diag_at"
    const val LAST_CALL = "last_call"
    const val LAST_CALL_AT = "last_call_at"
    /** App 里点「抓当前微信界面」时写一个时间戳，注入侧看到比上次新就去 dump 当前界面 */
    const val DIAG_REQ = "diag_req"
    /** 「角色」页：每个微信联系人的档案 + 平时记下来的聊天记录（JSON） */
    const val ROLES = "roles"
    const val LEARNED = "learned_classes"
    const val GLASS = "glass_alpha"
    const val GLASS_BLUR = "glass_blur"
    const val BG_URI = "bg_uri"
    const val BG_DIM = "bg_dim"
    const val SKILL = "skill_id"
    const val MENTOR_ADV = "mentor_adv"
    /** 「本人」那条的开关：把我的说话风格做成 skill（默认关） */
    const val SELF_STYLE_ON = "self_style_on"
    /** 自动提炼出来的说话风格档案 */
    const val SELF_SKILL = "self_skill"
    const val SELF_SKILL_AT = "self_skill_at"
    /** 每天几点跑（0..23，本地时间） */
    const val SELF_STYLE_HOUR = "self_style_hour"
    /** 模型分级模式：开 = 风险一路 + 写回复一路（可各用一套接口、各带一份提示词） */
    const val GRADED = "graded_mode"
    /** 第二套接口：给「风险评估」那一路用；留空 = 逐项复用第一套 */
    const val BASE2 = "base_url_2"
    const val KEY2 = "api_key_2"
    const val MODEL2 = "model_2"
    /** 玻璃效果档位：auto / high / low（见 ui/GlassQuality.kt） */
    const val GLASS_QUALITY = "glass_quality"
    /** 严格 JSON 输出：请求里带 response_format=json_object（只作用于「生成候选」） */
    const val JSON_MODE = "json_mode"
    /** 「接口自检」量到的两路延迟（ms）；设置页的公告栏与「两路对齐等待」都用它 */
    const val PROBE_REPLY_MS = "probe_reply_ms"
    const val PROBE_RISK_MS = "probe_risk_ms"
    /** 本地代理：开了以后 API Key 不再镜像给注入侧，改用随机 token 走回环 */
    const val PROXY_ON = "proxy_on"
    const val PROXY_PORT = "proxy_port"
    const val PROXY_TOKEN = "proxy_token"
    /** 注入侧回传：最近一次实际走的是 proxy 还是 direct（排查「代理没被用上」用） */
    const val ROUTE = "last_route"
    const val ROUTE_AT = "last_route_at"
    /** 只对白名单里的会话工作（默认关 = 全部会话都工作） */
    const val WHITELIST_ON = "whitelist_on"
    /** 白名单：会话名（归一化过的，见 Roles.normalizeKey） */
    const val WHITELIST = "whitelist"
    /** App 里点「拉取会话列表」时写一个时间戳；注入侧看到比上次新就去读当前这屏的会话名 */
    const val CHAT_REQ = "chats_req"
    /** 注入侧回传的会话名候选（已归一化、已去重）—— 白名单页拿它当候选 */
    const val CHAT_CANDIDATES = "chat_candidates"
    /** 上次拉取时的现场说明（扫到几个列表 / 几行 / 认出几个名字），拉不到东西时靠它排查 */
    const val CHAT_INFO = "chat_info"
    const val CHAT_AT = "chat_at"
    /** 白名单页里「删掉」的候选（归一化过）：记下来，下次拉取不再冒出来 */
    const val CHAT_IGNORED = "chat_ignored"
    /**
     * 图片文字识别（OCR）。
     *
     * 微信里读到一张图时，注入侧把图压成 JPEG 走本地代理送回来，App 用 ML Kit 当场认字，
     * 认出来的文字就当那条消息的内容（见 wechat/ImageOcr.kt 与 proxy/ProxyServer.kt）。
     * 默认开 —— 但它**先要本地代理开着**才有地方认（没代理时注入侧直接跳过、用老占位）。
     */
    const val OCR_ON = "ocr_on"
    /** 注入侧回传：最近一次图片识别的结果（认出几个字 / 为什么没认），设置页显示它 */
    const val OCR_INFO = "last_ocr"
    const val OCR_AT = "last_ocr_at"
    /** 归属地：关掉就完全不问第三方；自定义接口地址（留空 = 用内置那个） */
    const val GEO_ON = "geo_on"
    const val GEO_ENDPOINT = "geo_endpoint"
    /** 从服务端拉回来的模型列表（一行一个）+ 时间戳（0 = 还没拉过） */
    const val MODEL_LIST = "model_list"
    const val MODEL_LIST_AT = "model_list_at"
    /** 第二套接口（分级模式的「风险评估」那一路）的模型列表 */
    const val MODEL_LIST_2 = "model_list_2"
    const val MODEL_LIST_2_AT = "model_list_2_at"
    /**
     * 决策轨迹（注入侧的 ring buffer 渲染成文本）。
     *
     * 和 [DIAG] 的区别：DIAG 是**界面结构快照**（限流、体量大、含聊天正文），
     * TRACE 是**判定时间线**（连续、小、不含正文）—— 排查「为什么没弹卡片」看它。
     */
    const val TRACE = "trace"
    const val TRACE_AT = "trace_at"
}

/** 默认几点跑。 */
const val DEFAULT_SELF_STYLE_HOUR = 12

/** 玻璃效果：自动（按设备能力判断）。 */
const val GLASS_QUALITY_AUTO = "auto"

/** 玻璃效果：高（折射 + 模糊 + 扫光全开）。 */
const val GLASS_QUALITY_HIGH = "high"

/** 玻璃效果：低（只留半透明，最省电）。 */
const val GLASS_QUALITY_LOW = "low"

data class ConfigData(
    val baseUrl: String = DEFAULT_BASE,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    /** 接口形态 id（见 llm/ApiShape.kt）。老配置没有这个键 → 默认 OpenAI 兼容。 */
    val apiShape: String = "openai",
    /** 「自定义路径」形态时的路径 */
    val customPath: String = "",
    /** 在「服务商」下拉里选过谁（只用于回显） */
    val provider: String = "",
    val prompt: String = DEFAULT_PROMPT,
    val enabled: Boolean = true,
    /** 参考最近几条消息（2..20） */
    val ctx: Int = 8,
    val temperature: Double = 0.8,
    /** 0 = 不传 max_tokens（「无限制」档）；其余按 200/400/600 三档 */
    val maxTokens: Int = 400,
    /** 两次自动分析之间的最短间隔，防止刷屏式调用把额度烧完 */
    val minIntervalSec: Int = 15,
    /** 关掉的话，命中敏感内容时不会再拦你 */
    val allowSensitive: Boolean = false,
    /** 玻璃面板不透明度：1 = 不透明，越小越透（0.30..1.00） */
    val glassAlpha: Float = 0.92f,
    /** 玻璃面板背后的真实背景模糊半径（dp，0 = 不模糊）。只在设了自定义背景图时看得出来。 */
    val glassBlur: Float = 24f,
    /** 自定义背景图（OpenDocument 的持久化 URI），空 = 用默认渐变 */
    val bgUri: String = "",
    /** 背景压暗程度，保证玻璃上的字看得清 */
    val bgDim: Float = 0.30f,
    /** 当前选中的 skill：classic / full / coder / custom */
    val skillId: String = "classic",
    /** 「军师」页停在进阶视图。以前是用 maxTokens==0 猜的，会粘住，改成独立记住 */
    val mentorAdvanced: Boolean = false,
    /**
     * 「本人」那条的「把我的说话风格做成 skill」开关。
     *
     * 注意这个字段是**只读**的：它由 [ConfigStore.setSelfStyleEnabled] 单独写，
     * save() 刻意不碰它 —— 免得设置页拿着一份旧快照把这个开关覆盖回去。
     */
    val selfStyleEnabled: Boolean = false,
    /**
     * 每天几点跑（0..23）。和 [selfStyleEnabled] 一样是**只读**字段：
     * 由 [ConfigStore.setSelfStyleHour] 单独写，save() 不碰它。
     */
    val selfStyleHour: Int = DEFAULT_SELF_STYLE_HOUR,
    /**
     * 模型分级模式。关（默认）= 直通：一套接口、一份提示词、一次调用，和以前完全一样。
     * 开 = 风险一路 + 写回复一路，两路各带自己的提示词（见 llm/Graded.kt）。
     */
    val graded: Boolean = false,
    /** 第二套接口（给风险评估那一路用）。留空 = 复用第一套 —— 那样等于「同一个模型拆两路提示词」。 */
    val baseUrl2: String = "",
    val apiKey2: String = "",
    val model2: String = "",
    /**
     * 玻璃效果档位：auto（默认，按设备能力）/ high（全开）/ low（省电）。
     * 判定见 ui/GlassQuality.kt —— 手动选择永远优先于自动判断。
     */
    val glassQuality: String = GLASS_QUALITY_AUTO,
    /**
     * 严格 JSON 输出（`response_format: json_object`）。
     * 默认关：有些中转站不认这个参数，开了会直接报错。开关只作用于「生成候选」那一条调用路径。
     */
    val jsonMode: Boolean = false,
    /**
     * 「接口自检」量到的两路延迟（毫秒；0 = 没测过）。
     * 和 [selfStyleEnabled] 一样是**只读**字段：由 [ConfigStore.saveProbeMs] 单独写，save() 不碰。
     */
    val probeReplyMs: Long = 0L,
    val probeRiskMs: Long = 0L,
    /**
     * 本地代理开关。开了以后**注入侧拿不到 API Key**（RemoteSync 会把 api_key 推成空串），
     * 它改用 [proxyToken] 去请求本机回环端口。
     */
    val proxyEnabled: Boolean = false,
    val proxyPort: Int = 8799,
    /** 随机 token；由 [ConfigStore.ensureProxyToken] 生成。和 selfStyle* 一样是**只读**字段，save() 不碰。 */
    val proxyToken: String = "",
    /**
     * 只对白名单里的聊天工作。
     * 开着的时候，没勾的会话**彻底不处理** —— 不读内容、不记角色、也不调接口。
     */
    val whitelistEnabled: Boolean = false,
    /** 白名单里的会话名（存的是 [Roles.normalizeKey] 归一化之后的样子）。 */
    val whitelist: Set<String> = emptySet(),
    /**
     * 图片文字识别（OCR）：把聊天里的图片认成文字再交给模型。
     *
     * 默认开。真正干活的在 App 进程（ML Kit），微信那边只负责把图送过去 ——
     * 所以**必须先开「本地代理」**，否则注入侧连不上回环端口，会直接跳过（回落成 [ATTACHMENT_TEXT]）。
     */
    val ocrEnabled: Boolean = true,
    /**
     * 查 IP 归属地（「运行状态 → 接口自检」里那两行省份）。默认开。
     *
     * 归属地是**整个模块唯一**一处会把你的 IP 发给第三方的地方（见 llm/NetInfo.kt）——
     * 所以给一个开关：关掉之后自检只显示 IP，不再问任何人。
     */
    val geoEnabled: Boolean = true,
    /** 归属地接口地址。留空 = 用内置的 `ip.useragentinfo.com`；换成别的只要返回 JSON 里有 province/country/isp 就行。 */
    val geoEndpoint: String = "",
) {
    /**
     * 分级模式下「风险评估」那一路要用的接口。
     *
     * 第二套一个字都没填 → 直接用第一套（合法用法：同一个模型、拆两路提示词）；
     * 只填了一部分 → 把填了的字段覆盖过去。不用逼用户把两套都填满。
     */
    /** 这个会话允不允许工作。白名单关着 → 一律允许。 */
    fun allowsChat(name: String): Boolean = chatAllowed(whitelistEnabled, whitelist, name)

    fun riskEndpoint(): ConfigData =
        if (baseUrl2.isBlank() && apiKey2.isBlank() && model2.isBlank()) {
            this
        } else {
            copy(
                // 都 trim 一道：多一个空格/换行在有些中转上会变成 400（比如 model 名对不上）
                baseUrl = baseUrl2.trim().ifBlank { baseUrl },
                apiKey = apiKey2.trim().ifBlank { apiKey },
                model = model2.trim().ifBlank { model },
            )
        }

    companion object {
        /** 老版本存过的 max_tokens 不在四档里（比如 500），归一化到最近的档，免得设置页四档都没选中。 */
        fun snapTier(v: Int): Int = when {
            v <= 0 -> 0
            v <= 300 -> 200
            v <= 500 -> 400
            v <= 800 -> 600
            else -> 0
        }

        fun from(p: SharedPreferences): ConfigData = ConfigData(
            baseUrl = p.getString(Keys.BASE, DEFAULT_BASE).orEmpty().ifBlank { DEFAULT_BASE },
            apiKey = p.getString(Keys.KEY, "").orEmpty(),
            model = p.getString(Keys.MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL },
            apiShape = p.getString(Keys.API_SHAPE, "openai").orEmpty().ifBlank { "openai" },
            customPath = p.getString(Keys.CUSTOM_PATH, "").orEmpty(),
            provider = p.getString(Keys.PROVIDER, "").orEmpty(),
            prompt = p.getString(Keys.PROMPT, null).orEmpty().ifBlank { DEFAULT_PROMPT },
            enabled = p.getBoolean(Keys.ENABLED, true),
            ctx = p.getInt(Keys.CTX, 8).coerceIn(2, 20),
            glassAlpha = p.getFloat(Keys.GLASS, 0.92f).coerceIn(0.30f, 1f),
            glassBlur = p.getFloat(Keys.GLASS_BLUR, 24f).coerceIn(0f, 48f),
            bgUri = p.getString(Keys.BG_URI, "").orEmpty(),
            bgDim = p.getFloat(Keys.BG_DIM, 0.30f).coerceIn(0f, 0.8f),
            skillId = p.getString(Keys.SKILL, "classic").orEmpty().ifBlank { "classic" },
            mentorAdvanced = p.getBoolean(Keys.MENTOR_ADV, false),
            temperature = p.getFloat(Keys.TEMPERATURE, 0.8f).toDouble(),
            maxTokens = snapTier(p.getInt(Keys.MAX_TOKENS, 400)),
            minIntervalSec = p.getInt(Keys.MIN_INTERVAL, 15).coerceIn(0, 600),
            allowSensitive = p.getBoolean(Keys.SENSITIVE, false),
            selfStyleEnabled = p.getBoolean(Keys.SELF_STYLE_ON, false),
            selfStyleHour = p.getInt(Keys.SELF_STYLE_HOUR, DEFAULT_SELF_STYLE_HOUR).coerceIn(0, 23),
            graded = p.getBoolean(Keys.GRADED, false),
            baseUrl2 = p.getString(Keys.BASE2, "").orEmpty(),
            apiKey2 = p.getString(Keys.KEY2, "").orEmpty(),
            model2 = p.getString(Keys.MODEL2, "").orEmpty(),
            glassQuality = p.getString(Keys.GLASS_QUALITY, GLASS_QUALITY_AUTO).orEmpty().ifBlank { GLASS_QUALITY_AUTO },
            jsonMode = p.getBoolean(Keys.JSON_MODE, false),
            probeReplyMs = p.getLong(Keys.PROBE_REPLY_MS, 0L).coerceAtLeast(0L),
            probeRiskMs = p.getLong(Keys.PROBE_RISK_MS, 0L).coerceAtLeast(0L),
            proxyEnabled = p.getBoolean(Keys.PROXY_ON, false),
            proxyPort = p.getInt(Keys.PROXY_PORT, 8799).coerceIn(1024, 65535),
            proxyToken = p.getString(Keys.PROXY_TOKEN, "").orEmpty(),
            whitelistEnabled = p.getBoolean(Keys.WHITELIST_ON, false),
            whitelist = p.getStringSet(Keys.WHITELIST, emptySet()).orEmpty()
                .map { Roles.normalizeKey(it) }
                .filter { it.isNotBlank() }
                .toSet(),
            ocrEnabled = p.getBoolean(Keys.OCR_ON, true),
            geoEnabled = p.getBoolean(Keys.GEO_ON, true),
            geoEndpoint = p.getString(Keys.GEO_ENDPOINT, "").orEmpty(),
        )

    }
}

/**
 * 配置仓库。
 *
 * API Key 落盘是明文（SharedPreferences 的 XML）。
 * 试过用 EncryptedSharedPreferences，但那样注入到微信进程的代码就读不到了 ——
 * Keystore 的密钥按 UID 隔离，跨进程就是打不开。所以这里保持明文，并在「设置」页明说。
 * 真正的解法是让 App 起一个本地代理、Key 不出 App 进程（见 IMPROVEMENTS.md 的路线图）。
 */
class ConfigStore(context: Context) {
    /**
     * 本地配置。它仍然是 App 侧唯一的事实来源（界面、导出导入都读它）。
     *
     * 迁移到现代 API 之后这里不再需要 MODE_WORLD_READABLE：以前靠它让注入进程（不同 UID）
     * 能读这个 XML 文件，现在注入侧读的是框架推送的配置副本（见 RemoteSync），
     * 两个进程之间不再需要共享同一个文件。
     */
    private val sp: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun load(): ConfigData = ConfigData.from(sp)

    fun save(d: ConfigData) {
        sp.edit()
            .putString(Keys.BASE, d.baseUrl.trim())
            .putString(Keys.KEY, d.apiKey.trim())
            .putString(Keys.MODEL, d.model.trim())
            .putString(Keys.API_SHAPE, d.apiShape)
            .putString(Keys.CUSTOM_PATH, d.customPath)
            .putString(Keys.PROVIDER, d.provider)
            .putString(Keys.PROMPT, d.prompt)
            .putBoolean(Keys.ENABLED, d.enabled)
            .putInt(Keys.CTX, d.ctx)
            .putFloat(Keys.TEMPERATURE, d.temperature.toFloat())
            .putInt(Keys.MAX_TOKENS, d.maxTokens)
            .putInt(Keys.MIN_INTERVAL, d.minIntervalSec)
            .putBoolean(Keys.SENSITIVE, d.allowSensitive)
            .putFloat(Keys.GLASS, d.glassAlpha.coerceIn(0.30f, 1f))
            .putFloat(Keys.GLASS_BLUR, d.glassBlur.coerceIn(0f, 48f))
            .putString(Keys.BG_URI, d.bgUri)
            .putFloat(Keys.BG_DIM, d.bgDim.coerceIn(0f, 0.8f))
            .putString(Keys.SKILL, d.skillId)
            .putBoolean(Keys.MENTOR_ADV, d.mentorAdvanced)
            .putBoolean(Keys.GRADED, d.graded)
            .putString(Keys.BASE2, d.baseUrl2.trim())
            .putString(Keys.KEY2, d.apiKey2.trim())
            .putString(Keys.MODEL2, d.model2.trim())
            .putString(Keys.GLASS_QUALITY, d.glassQuality)
            .putBoolean(Keys.JSON_MODE, d.jsonMode)
            .putBoolean(Keys.PROXY_ON, d.proxyEnabled)
            .putInt(Keys.PROXY_PORT, d.proxyPort.coerceIn(1024, 65535))
            .putBoolean(Keys.WHITELIST_ON, d.whitelistEnabled)
            .putStringSet(Keys.WHITELIST, d.whitelist.map { Roles.normalizeKey(it) }.filter { it.isNotBlank() }.toHashSet())
            .putBoolean(Keys.OCR_ON, d.ocrEnabled)
            .putBoolean(Keys.GEO_ON, d.geoEnabled)
            .putString(Keys.GEO_ENDPOINT, d.geoEndpoint.trim())
            .apply()
    }

    fun usage(): Pair<Int, Int> = sp.getInt(Keys.CALLS, 0) to sp.getInt(Keys.TOKENS, 0)

    /** 本地代理的 token：没有就生成一个 32 位随机串。只写这个 key，不走 save()。 */
    fun ensureProxyToken(): String {
        val cur = sp.getString(Keys.PROXY_TOKEN, "").orEmpty()
        if (cur.isNotBlank()) return cur
        val fresh = (1..32).map { "abcdefghijkmnpqrstuvwxyz23456789".random() }.joinToString("")
        sp.edit().putString(Keys.PROXY_TOKEN, fresh).apply()
        return fresh
    }

    /** 重新生成 token（怀疑被别的 App 蹭了就换一个）。 */
    fun resetProxyToken(): String {
        sp.edit().putString(Keys.PROXY_TOKEN, "").apply()
        return ensureProxyToken()
    }

    /** 请求注入侧读一次「当前这屏看得见的会话名」（白名单页的「拉取会话列表」）。 */
    fun requestChats(): Long {
        val now = System.currentTimeMillis()
        sp.edit().putLong(Keys.CHAT_REQ, now).apply()
        return now
    }

    /** 上一次点「拉取会话列表」的时间（0 = 没点过）。界面拿它和回传时间比，
     *  就能分清「请求还没送到微信侧」和「送到了但没读出名字」——这两件事的排查方向是相反的。 */
    fun chatRequestAt(): Long = sp.getLong(Keys.CHAT_REQ, 0L)

    /** 注入侧回传的会话名候选（归一化过、已去重；上限 300）。被「删掉」过的不再列出。 */
    fun chatCandidates(): Set<String> {
        val all = sp.getStringSet(Keys.CHAT_CANDIDATES, emptySet()).orEmpty().toSet()
        return all - chatIgnored()
    }

    /** 被「长按多选 → 删除」掉的候选名（归一化过）。拉取回来的候选会先过一遍这里。 */
    fun chatIgnored(): Set<String> =
        sp.getStringSet(Keys.CHAT_IGNORED, emptySet()).orEmpty().toSet()

    /**
     * 把这些候选名拉黑，并从已收候选里删掉 —— 白名单页「长按多选 → 删除」用。
     *
     * 为什么除了删还要单独记一份「已忽略」：候选是注入侧每次回传时**并**进来的，
     * 只从候选里删掉的话，下一次拉取它们会原样长回来（用户实测「杂项删不掉」就是这个）。
     */
    fun ignoreChats(names: Set<String>): Set<String> {
        val norm = names.map { Roles.normalizeKey(it) }.filter { it.isNotBlank() }.toSet()
        if (norm.isEmpty()) return chatIgnored()
        val ignored = LinkedHashSet(chatIgnored()).apply { addAll(norm) }
        val left = sp.getStringSet(Keys.CHAT_CANDIDATES, emptySet()).orEmpty().toSet() - norm
        sp.edit()
            .putStringSet(Keys.CHAT_IGNORED, ignored.toHashSet())
            .putStringSet(Keys.CHAT_CANDIDATES, left.toHashSet())
            .apply()
        return ignored
    }

    /** 把名字从「已忽略」里放出来（手动又把它加回白名单 / 点「恢复」时用）。 */
    fun unignoreChats(names: Set<String>) {
        val norm = names.map { Roles.normalizeKey(it) }.filter { it.isNotBlank() }.toSet()
        if (norm.isEmpty()) return
        sp.edit().putStringSet(Keys.CHAT_IGNORED, (chatIgnored() - norm).toHashSet()).apply()
    }

    /** 清空「已忽略」—— 恢复整份候选。 */
    fun clearIgnored() {
        sp.edit().remove(Keys.CHAT_IGNORED).apply()
    }

    /**
     * 只改「白名单开关 + 名单」这两项，**立刻落盘**（增删和开关都要即时生效 —— 这张卡没有保存按钮）。
     *
     * 刻意只写这两项，而不是让调用方 `save(load().copy(...))`：白名单卡所在的那一页还有别的卡片
     * 正在编辑、还没点保存的内容（接口地址、生成模式…），整体写回会把它们的草稿一起定死。
     * 名字按 `save()` 同一套规矩归一化、去掉空串 —— 存进去的和注入侧读出来的必须是同一套写法。
     */
    fun saveWhitelist(enabled: Boolean, chats: Set<String>) {
        sp.edit()
            .putBoolean(Keys.WHITELIST_ON, enabled)
            .putStringSet(
                Keys.WHITELIST,
                chats.map { Roles.normalizeKey(it) }.filter { it.isNotBlank() }.toHashSet(),
            )
            .apply()
    }

    /** 上次拉取的现场说明 + 时间戳（空串 / 0 = 还没拉过）。 */
    fun chatPullInfo(): Pair<String, Long> =
        sp.getString(Keys.CHAT_INFO, "").orEmpty() to sp.getLong(Keys.CHAT_AT, 0L)

    /**
     * 只改「图片文字识别」这一个开关，**立刻落盘**。
     *
     * 跟白名单 / 本地代理同一个道理：这类开关改完就该生效，不该等那个管整页的「保存」按钮 ——
     * 而高级设置页恰恰是「一个保存按钮管整页」的，混着来最容易出现「我明明开了怎么没用」。
     */
    fun saveOcr(enabled: Boolean) {
        sp.edit().putBoolean(Keys.OCR_ON, enabled).apply()
    }

    /**
     * 只改「生成模式」这一个开关，**立刻落盘**。
     *
     * 跟白名单 / 识图同一个道理：模式切换是开关型的选择，点完就该生效 —— 而这一页恰恰是
     * 「一个保存按钮管整页」，接口地址、温度这些还都是草稿。两种行为混在一页最容易出现
     * 「我明明点了，怎么还是老的」（白名单当初就是这么坑到的）。
     */
    fun saveGraded(graded: Boolean) {
        sp.edit().putBoolean(Keys.GRADED, graded).apply()
    }

    /**
     * 归属地的开关 / 地址：**只写这两项、立刻落盘**（跟白名单 / 识图 / 生成模式同一个道理）。
     * 这两个字段也参与整份 save()，所以设置页按「保存」时会照常一起写。
     */
    fun saveGeo(enabled: Boolean, endpoint: String) {
        sp.edit()
            .putBoolean(Keys.GEO_ON, enabled)
            .putString(Keys.GEO_ENDPOINT, endpoint.trim())
            .apply()
    }

    /**
     * 拉回来的模型列表 + 时间戳（空列表 / 0 = 还没拉过）。一行一个存着，够用又不引序列化。
     * [second] = 第二套接口（分级模式那一跳）——两套各存各的，它们常常是不同服务商。
     */
    fun modelList(second: Boolean = false): Pair<List<String>, Long> {
        val raw = sp.getString(if (second) Keys.MODEL_LIST_2 else Keys.MODEL_LIST, "").orEmpty()
        val list = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        return list to sp.getLong(if (second) Keys.MODEL_LIST_2_AT else Keys.MODEL_LIST_AT, 0L)
    }

    /** 存模型列表（只写这两项，不走 save()）。 */
    fun saveModelList(models: List<String>, second: Boolean = false) {
        sp.edit()
            .putString(
                if (second) Keys.MODEL_LIST_2 else Keys.MODEL_LIST,
                models.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n"),
            )
            .putLong(if (second) Keys.MODEL_LIST_2_AT else Keys.MODEL_LIST_AT, System.currentTimeMillis())
            .apply()
    }

    /** 注入侧回传：最近一次图片识别的结果 + 时间戳（空串 / 0 = 还没试过）。 */
    fun ocrInfo(): Pair<String, Long> =
        sp.getString(Keys.OCR_INFO, "").orEmpty() to sp.getLong(Keys.OCR_AT, 0L)

    /** 注入侧最近一次实际走的路线（proxy / direct；空 = 还没回传过）+ 时间戳。 */
    fun lastRoute(): Pair<String, Long> =
        sp.getString(Keys.ROUTE, "").orEmpty() to sp.getLong(Keys.ROUTE_AT, 0L)

    /** 自检测到的两路延迟（写回复 / 风险评估；0 = 没测过）。设置页的公告栏读它。 */
    fun probeMs(): Pair<Long, Long> =
        sp.getLong(Keys.PROBE_REPLY_MS, 0L) to sp.getLong(Keys.PROBE_RISK_MS, 0L)

    /**
     * 自检测到的两路延迟（写回复 / 风险评估）。
     * 单独写、不走 save()：设置页拿着别的字段的旧快照保存时，不该把这俩覆盖回 0。
     */
    fun saveProbeMs(replyMs: Long, riskMs: Long) {
        sp.edit()
            .putLong(Keys.PROBE_REPLY_MS, replyMs.coerceAtLeast(0L))
            .putLong(Keys.PROBE_RISK_MS, riskMs.coerceAtLeast(0L))
            .apply()
    }

    fun addUsage(tokens: Int) {
        sp.edit()
            .putInt(Keys.CALLS, sp.getInt(Keys.CALLS, 0) + 1)
            .putInt(Keys.TOKENS, sp.getInt(Keys.TOKENS, 0) + tokens.coerceAtLeast(0))
            .apply()
    }

    fun heartbeatAt(): Long = sp.getLong(Keys.HEARTBEAT, 0L)

    /**
     * 实时探测（见 [Keys.PROBE_REQ]）。**「模块现在还在不在」只能这么问**：
     * 心跳时间戳回答的是「曾经跑过没」（那是用户实测点出来的毛病）。
     * 探测是双向的：这里写请求 → 微信进程回执 → [probeAck] 晚于 [probeAt] 才算「有人应」。
     */
    fun requestProbe() {
        sp.edit().putLong(Keys.PROBE_REQ, System.currentTimeMillis()).apply()
    }

    fun probeAt(): Long = sp.getLong(Keys.PROBE_REQ, 0L)
    fun probeAck(): Long = sp.getLong(Keys.PROBE_ACK, 0L)
    fun probeInfo(): String = sp.getString(Keys.PROBE_INFO, "").orEmpty()

    /** 微信进程最近一次写回来的结构诊断（排查「读不到消息」用）。 */
    fun diag(): String = sp.getString(Keys.DIAG, "").orEmpty()

    fun diagAt(): Long = sp.getLong(Keys.DIAG_AT, 0L)

    /**
     * 微信进程回传的「决策轨迹」（比 diag 更连续、更小、不含聊天正文）。
     * 排查「卡片为什么不弹」时先看它 —— 它把每一轮的判定和停在哪一步都记下来了。
     */
    fun trace(): String = sp.getString(Keys.TRACE, "").orEmpty()

    fun traceAt(): Long = sp.getLong(Keys.TRACE_AT, 0L)

    /**
     * 注入侧回传的「最近一次真正发出去的请求」存档。
     * 排查「App 里选的是 A，用起来像 B」时必须看它 —— App 显示的是本进程读到的配置，
     * 而这里是微信进程实际拿去调接口的那一份，两者走的读取路径完全不同。
     */
    fun lastCall(): String = sp.getString(Keys.LAST_CALL, "").orEmpty()

    fun lastCallAt(): Long = sp.getLong(Keys.LAST_CALL_AT, 0L)

    /**
     * 请求注入侧抓一次「当前微信界面」的结构。
     *
     * 为什么需要它：出问题的聊天页是「连卡片都不弹」的，而诊断入口原本是长按卡片标题 ——
     * 没有卡片就没有入口，永远拿不到那几个页面的证据。所以改成由 App 主动发起。
     * 只动这一个 key，不走 save()，免得把别的字段一起写回去。
     */
    // ---------------- 角色 ----------------

    /** 角色列表。统一走 [Roles.withSelf]：「本人」那条永远是第一条、永远在。 */
    fun roles(): List<Role> = Roles.withSelf(Roles.decode(sp.getString(Keys.ROLES, "").orEmpty()))

    // ---------------- 「本人」的说话风格 skill ----------------

    fun selfStyleEnabled(): Boolean = sp.getBoolean(Keys.SELF_STYLE_ON, false)

    /**
     * 开关。
     *
     * 关掉时**连采集到的原始记录一起清掉** —— 这就是「不存储我的聊天记录」的字面意思。
     * 已生成的 skill 先留着：它只是提炼结果，不重新打开就不会被用上。
     */
    fun setSelfStyleEnabled(on: Boolean) {
        sp.edit().putBoolean(Keys.SELF_STYLE_ON, on).apply()
        if (!on) clearRoleMsgs(SELF_ROLE_KEY)
    }

    /** 每天几点跑（本地时间 0..23）。 */
    fun selfStyleHour(): Int =
        sp.getInt(Keys.SELF_STYLE_HOUR, DEFAULT_SELF_STYLE_HOUR).coerceIn(0, 23)

    fun setSelfStyleHour(hour: Int) {
        sp.edit().putInt(Keys.SELF_STYLE_HOUR, hour.coerceIn(0, 23)).apply()
    }

    fun selfSkill(): String = sp.getString(Keys.SELF_SKILL, "").orEmpty()

    fun selfSkillAt(): Long = sp.getLong(Keys.SELF_SKILL_AT, 0L)

    fun saveSelfSkill(text: String) {
        sp.edit()
            .putString(Keys.SELF_SKILL, text)
            .putLong(Keys.SELF_SKILL_AT, System.currentTimeMillis())
            .apply()
    }

    /** 「本人」这条线攒到的样本（都是我自己发出去的话）。 */
    fun selfSamples(): List<RoleMsg> =
        roles().firstOrNull { it.key == SELF_ROLE_KEY }?.msgs.orEmpty().filter { it.fromMe }

    fun saveRoles(roles: List<Role>) {
        sp.edit().putString(Keys.ROLES, Roles.encode(roles)).apply()
    }

    /** 注入侧回传的一批新消息，合并进对应角色（1 小时内重复只留一条）。 */
    fun mergeRoles(incomingJson: String) {
        val incoming = Roles.decodeIncoming(incomingJson)
        if (incoming.isEmpty()) return
        saveRoles(Roles.merge(roles(), incoming))
    }

    /** 写「TA 是你什么人 / 平时的关系」。参数是识别名（key）。 */
    fun setRoleProfile(key: String, relation: String, note: String) {
        saveRoles(Roles.setProfile(roles(), key, relation, note))
    }

    /** 改显示名（不动 key，所以后续消息还是记到这一条）。 */
    fun renameRole(key: String, newName: String) {
        saveRoles(Roles.rename(roles(), key, newName))
    }

    /** 把 [from] 这个角色的记录并到 [to] 上（同一个人被记成了两个名字时，手动合并）。 */
    fun mergeRoles(from: String, to: String) {
        saveRoles(Roles.mergeTwo(roles(), from, to))
    }

    fun removeRole(key: String) {
        saveRoles(roles().filterNot { it.key == key })
    }

    /** 只清聊天记录，保留档案。 */
    fun clearRoleMsgs(key: String) {
        saveRoles(roles().map { if (it.key == key) it.copy(msgs = emptyList()) else it })
    }

    fun requestDiag(): Long {
        val now = System.currentTimeMillis()
        sp.edit().putLong(Keys.DIAG_REQ, now).apply()
        return now
    }

    /** 已经学会「自己画字」的控件类（模块下次启动就先挂钩子）。 */
    fun learnedClasses(): Set<String> = sp.getStringSet(Keys.LEARNED, emptySet()).orEmpty()

    fun scopeConfirmed(): Boolean = sp.getBoolean(Keys.SCOPE_OK, false)

    fun setScopeConfirmed(v: Boolean) {
        sp.edit().putBoolean(Keys.SCOPE_OK, v).apply()
    }
}


/**
 * 白名单判定（纯函数，配单测）。
 *
 * 规则刻意很简单：**没开白名单 = 一律放行**；开了就只认集合里的名字。
 * 名字统一走 [Roles.normalizeKey]（去空白、去尾部 "(3)" 这类计数），
 * 免得「张三」和「张三 」被当成两个会话。
 *
 * 注意「开着白名单但集合是空的」→ 谁都不放行。这是有意的：
 * 用户把开关打开却还一条没加时，应该表现为「都没反应」，而不是「悄悄全都放行了」。
 */
fun chatAllowed(whitelistEnabled: Boolean, whitelist: Set<String>, name: String): Boolean {
    if (!whitelistEnabled) return true
    val key = Roles.normalizeKey(name)
    return key.isNotBlank() && whitelist.contains(key)
}

/**
 * 「这一屏要不要拦下来」—— 注入侧用的判定，单测直接钉这个。
 *
 * 和 [chatAllowed] 只差一处：**认不出会话名时按拦下处理**（fail-closed）。
 *
 * 这里以前是放行的，理由是「用户看到完全没反应不好查」。但白名单是个**隐私开关** ——
 * 开着它却因为「这屏标题没认出来」把聊天内容读出来、发到接口，比没反应严重得多
 * （`publish/PRIVACY.md` 里写的是「没勾的会话彻底不处理」）。
 * 所以改成拦下，同时把原因写到界面上：按钮上「白名单 · 认不出会话名」，
 * 第一次再给一句提示 —— 既不误发，也不让人猜。
 */
fun chatBlocked(whitelistEnabled: Boolean, whitelist: Set<String>, name: String): Boolean {
    if (!whitelistEnabled) return false
    val key = Roles.normalizeKey(name)
    return key.isBlank() || !whitelist.contains(key)
}
