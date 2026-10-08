package dev.goutou.wingman.llm

/**
 * 服务商预设：**只省掉「去官网查地址」这一步**。
 *
 * 我们本来就是「OpenAI 兼容 + 自定义 base_url + 自定义 model」，所以预设不是什么特殊通道，
 * 就是一张字段映射表 —— 选中之后照样能手改，也不改变任何请求逻辑。
 *
 * ⚠️ **刻意不预填模型名**（除了 DeepSeek，那两个名字是查过官方文档的）：
 * 模型名各家迭代很快，写死一个过期的比留空更坑 —— 留空时界面上有「从服务端拉取模型列表」。
 */
data class ProviderPreset(
    val id: String,
    val label: String,
    /** 「接口地址」该填什么（这是用户最难猜的一项，所以预设主要在填它） */
    val baseUrl: String,
    /** 模型名。空 = 不预填，让用户拉列表或自己填。 */
    val model: String = "",
    /** 这个服务商该用哪种接口形态 */
    val shape: ApiShape = ApiShape.OPENAI,
    /** 一行注意事项，选中后显示在卡片里 */
    val note: String = "",
)

val PROVIDER_PRESETS: List<ProviderPreset> = listOf(
    ProviderPreset(
        id = "deepseek",
        label = "DeepSeek 深度求索",
        baseUrl = "https://api.deepseek.com",
        // 这两个名字来自官方文档（老名 deepseek-chat / deepseek-reasoner 已不再出现）
        model = "deepseek-flash",
        note = "思考模式默认开着：temperature 设了不报错，但**不生效**；按峰谷计价（低谷半价）",
    ),
    ProviderPreset(
        id = "anthropic",
        label = "Anthropic Claude",
        baseUrl = "https://api.anthropic.com/v1",
        shape = ApiShape.ANTHROPIC,
        note = "鉴权走 x-api-key（不是 Bearer）；本地代理不支持这种形态，请直连",
    ),
    ProviderPreset(
        id = "dashscope",
        label = "通义千问（DashScope）",
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        note = "地址必须带 /compatible-mode",
    ),
    ProviderPreset(id = "zhipu", label = "智谱 GLM", baseUrl = "https://open.bigmodel.cn/api/paas/v4"),
    ProviderPreset(id = "kimi", label = "月之暗面 Kimi", baseUrl = "https://api.moonshot.cn/v1"),
    ProviderPreset(
        id = "ark",
        label = "火山方舟（豆包）",
        baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
        note = "模型要填「推理接入点 ID」（ep-xxxxxxxx），**不是模型名**",
    ),
    ProviderPreset(id = "qianfan", label = "百度千帆", baseUrl = "https://qianfan.baidubce.com/v2"),
    ProviderPreset(
        id = "siliconflow",
        label = "硅基流动（聚合）",
        baseUrl = "https://api.siliconflow.cn/v1",
        note = "聚合平台：一个 Key 能调多家模型",
    ),
    ProviderPreset(id = "stepfun", label = "阶跃星辰", baseUrl = "https://api.stepfun.com/v1"),
    ProviderPreset(id = "minimax", label = "MiniMax", baseUrl = "https://api.minimax.chat/v1"),
    ProviderPreset(id = "openai", label = "OpenAI", baseUrl = "https://api.openai.com/v1"),
)

fun presetOf(id: String): ProviderPreset? = PROVIDER_PRESETS.firstOrNull { it.id == id }
