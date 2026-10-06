package dev.goutou.wingman.wechat

import kotlin.math.abs

/** Pure view classification rules. Android-specific code only supplies these measurements. */
data class ImageCandidateSpec(
    val width: Int,
    val height: Int,
    val depth: Int,
    val isLeaf: Boolean,
    val isAvatarLike: Boolean,
    val isSquareSmall: Boolean,
    val isBig: Boolean,
)

/** Selects the image candidate without depending on View, Bitmap, or Canvas. */
fun selectImageCandidate(candidates: List<ImageCandidateSpec>, rowIndex: Int = -1): Int {
    val bigLeaf = candidates.indices.filter { candidates[it].isBig && candidates[it].isLeaf }
    bigLeaf.maxByOrNull { candidates[it].width * candidates[it].height }?.let { return it }

    val bigContainer = candidates.indices.filter { candidates[it].isBig && it != rowIndex }
    bigContainer.maxWithOrNull(compareBy<Int> { candidates[it].depth * 10_000_000 + candidates[it].width * candidates[it].height })
        ?.let { return it }

    return candidates.indices
        .filter { candidates[it].isLeaf && !candidates[it].isAvatarLike && !candidates[it].isSquareSmall }
        .maxByOrNull { candidates[it].width * candidates[it].height } ?: -1
}

/** Measurements of a title node; visibility and ancestry stay at the Android boundary. */
data class TitleCandidateSpec(
    val text: String,
    val width: Int,
    val height: Int,
    val x: Int,
    val y: Int,
    val textSize: Float = 0f,
)

/** Null means rejected; callers keep the first candidate on equal scores and prefer the narrow band. */
fun chatTitleScore(
    candidate: TitleCandidateSpec,
    screenWidth: Int,
    density: Float,
    bandTop: Int,
    bandBottom: Int,
    avoidTexts: Set<String> = emptySet(),
): Int? {
    fun dp(value: Int) = (value * density).toInt()
    val t = candidate.text
    if (t.isEmpty() || t.length > 32 || t in UI_WORDS || looksLikeViewDump(t)) return null
    if (t in avoidTexts || TITLE_NOISE.any { t.startsWith(it) }) return null
    if (candidate.width < dp(24) || candidate.height < dp(14)) return null
    if (candidate.y < bandTop || candidate.y > bandBottom) return null
    val off = kotlin.math.abs((candidate.x + candidate.width / 2f) - screenWidth / 2f) / screenWidth.toFloat()
    if (off > 0.16f) return null
    return ((1f - off) * 1000).toInt() + candidate.width.coerceAtMost(screenWidth) / 20 +
        (candidate.textSize.coerceAtMost(dp(40).toFloat()) / 4f).toInt()
}

fun isConversationListContainer(shown: Boolean, childCount: Int, listLike: Boolean, height: Int, top: Int, screenHeight: Int): Boolean =
    shown && childCount >= 3 && listLike && height > 0 && top + height > 0 &&
        top < screenHeight && height >= screenHeight * 0.25f

fun isConversationAvatar(width: Int, height: Int, left: Int, screenWidth: Int, density: Float): Boolean {
    fun dp(value: Int) = (value * density).toInt()
    return width in dp(24)..dp(84) && height > 0 && kotlin.math.abs(width - height) <= dp(4) && left < screenWidth * 0.30f
}

fun classifyBubbleColor(px: Int): Side {
    if (((px ushr 24) and 0xFF) < 24) return Side.UNKNOWN
    val r = (px shr 16) and 0xFF
    val g = (px shr 8) and 0xFF
    val b = px and 0xFF
    return if (g - r > 16 && g - b > 10) Side.ME else Side.OTHER
}

fun colorDistance(a: Int, b: Int): Int {
    val dr = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
    val dg = abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
    val db = abs((a and 0xFF) - (b and 0xFF))
    return dr + dg + db
}

fun looksLikeViewDump(s: String): Boolean {
    val t = s.trim()
    if (t.isEmpty()) return false
    if (FQN_RE.matches(t)) return true
    // "android.widget.LinearLayout{...}" 这种：去掉 {...} 之后再看一眼前半段
    val head = t.substringBefore('{').trim()
    if (head.length < t.length && head.length >= 8 && FQN_RE.matches(head)) return true
    // 兜底：框架包名开头的一律不是聊天内容
    return FRAMEWORK_PREFIXES.any { t.startsWith(it) }
}

/** 会出现在同一带的临时/状态文案，不是会话名。 */
private val TITLE_NOISE = listOf(
    "对方正在输入", "正在输入", "网络连接不可用", "未连接", "点击重连", "连接中",
    "语音通话中", "视频通话中", "邀请你", "按住说话", "松开 结束",
)

/**
 * `android.widget.TextView` 这种：全是点分标识符、末段首字母大写。
 * 刻意不把 `$` 写进字符类 —— 在 Kotlin 字符串里它是模板起始符，容易出幺蛾子，
 * 而带 `$` 的内部类名字符串还有 FRAMEWORK_PREFIXES 那条兜底。
 */
// matches() 本身就是整串匹配，不用 ^ / $ 锚点（也避开 Kotlin 字符串里的 $ 模板歧义）
private val FQN_RE = Regex("([A-Za-z_][A-Za-z0-9_]*\\.)+[A-Z][A-Za-z0-9_]*")

/** 这些包名开头的一律不是聊天内容。 */
private val FRAMEWORK_PREFIXES = listOf(
    "android.", "androidx.", "java.", "javax.", "kotlin.", "dalvik.",
    "com.tencent.", "com.android.", "com.google.android.",
)

/** 纯 UI 文案的无障碍描述，不当消息正文。 */
internal val UI_WORDS = setOf(
    "头像", "表情", "更多功能", "更多", "返回", "发送", "语音输入", "加号",
    "图片", "视频", "按住 说话", "切换键盘", "菜单", "关闭", "搜索", "聊天信息",
)
