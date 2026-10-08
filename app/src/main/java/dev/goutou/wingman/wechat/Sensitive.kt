package dev.goutou.wingman.wechat

/**
 * 发给模型之前的本地隐私自检。
 * 原版是「把最近 8 条消息直接发到你填的接口地址」+ 一句免责说明；
 * 现在至少能在命中高危内容时先弹一次确认，用户可以选择不发。
 */
object Sensitive {
    private val rules: List<Pair<String, Regex>> = listOf(
        "验证码/动态码" to Regex("(?i)(验证码|校验码|动态码|短信码|verification code|\\botp\\b)"),
        "密码/口令" to Regex("(?i)(密码|口令|password|passwd|\\bpwd\\b)"),
        "身份证号" to Regex("""(?<!\d)\d{17}[\dXx](?!\d)"""),
        "银行卡号" to Regex("""(?<!\d)\d{16,19}(?!\d)"""),
        "手机号" to Regex("""(?<!\d)1[3-9]\d{9}(?!\d)"""),
        "转账/红包/借钱" to Regex("(转账|打款|汇款|红包|借钱|借点|扫码付|付款码|收款码)"),
        "链接" to Regex("""https?://\S+|www\.\S+\.\S+"""),
    )

    /** 命中的规则名（去重），空 = 干净。 */
    fun hits(text: String): List<String> =
        rules.filter { it.second.containsMatchIn(text) }.map { it.first }

    /** 只用于本地预览/日志，别拿它替换真正要发的正文。 */
    fun redact(text: String): String =
        rules.fold(text) { acc, (_, re) -> re.replace(acc, "«已屏蔽»") }
}

/**
 * 归档前的**隐私闸**：敏感检查开着时，命中敏感词的消息不进角色档案。
 *
 * 为什么要有它：敏感提示原本只拦「这一次调用」—— 被判敏感的正文照样进了角色档案，
 * 下一轮又作为「角色背景」进提示词发出去，于是「只提示、不外发」其实只对当次成立。
 * 在**归档这一步**滤掉，才真的不外发（不进档 = 永远不会再进提示词）。
 *
 * ⚠️ 用户放行过（点「仍然分析这一条」）也**不**补记：那只是同意「这一次发」，
 * 不等于同意「以后每次都作为背景发」。用户自己关掉检查（allowSensitive）时 gate=false，照常记录。
 *
 * @return first = 可以归档的消息（顺序不变）；second = 被拦下的条数（只记条数，不记正文）
 */
internal fun sensitiveArchiveFilter(msgs: List<ChatMsg>, gate: Boolean): Pair<List<ChatMsg>, Int> {
    if (!gate) return msgs to 0
    val kept = ArrayList<ChatMsg>(msgs.size)
    var skipped = 0
    for (m in msgs) {
        if (Sensitive.hits(m.text).isNotEmpty()) skipped++ else kept.add(m)
    }
    return kept to skipped
}
