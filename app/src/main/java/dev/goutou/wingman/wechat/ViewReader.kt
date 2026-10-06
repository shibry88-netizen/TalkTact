package dev.goutou.wingman.wechat

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.NinePatchDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.AbsListView
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * View 树 -> RowSnapshot。整个模块唯一依赖 Android API 的读取逻辑。
 *
 * 判据都是被真机数据打过脸的，改动前先看线上诊断（长按卡片标题）：
 *
 * 1. 选列表：不认类名顺序，以输入框为锚点（见 findList）。
 * 2. 微信的输入框「浮在」消息列表上层，列表高度会伸到输入框下面，不能要求列表整个在输入框上方。
 * 3. 正文不一定在 TextView 里：微信 8.0.78 实测 `jh` 行里只有时间戳是 TextView，
 *    正文要么在 INVISIBLE 的占位控件里、要么是自绘的 —— 所以这里：
 *    · 读文字时不跳过 INVISIBLE（只跳 GONE）
 *    · 兜底时用 contentDescription（读屏用的那种描述）
 * 4. 方向判定三票制（头像位置 / 气泡左右 / 气泡颜色），颜色只算一票（深色模式会失效）。
 * 5. 别每轮重画：气泡底色只采样一次并缓存，重画共用同一块 Bitmap。
 */
internal class ViewReader(private val a: Activity) {

    private val metrics = a.resources.displayMetrics
    private val width = metrics.widthPixels
    private val height = metrics.heightPixels
    private val density = metrics.density
    private val night = (a.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    private fun dp(v: Int) = (v * density).toInt()

    private val scratch: Bitmap by lazy { Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888) }
    private val canvas = Canvas()
    private val bubbleCache = WeakHashMap<View, BubbleHit>()

    /** 类 -> 它的 getText() 方法（null 表示这个类没有）。只查一次。 */
    private val textMethods = HashMap<Class<*>, java.lang.reflect.Method?>()

    /** 类 -> 它所有「无参返回 CharSequence」的方法（暴力反射用）。 */
    private val anyTextMethods = HashMap<Class<*>, List<java.lang.reflect.Method>>()
    private val pageBg: Int by lazy { detectPageBackground() }

    /** 上一次 findList 的候选情况，只用于诊断输出。 */
    var lastCandidates: String = "（还没找过）"
        private set

    class BubbleHit(val side: Side, val centerRatio: Double, val widthRatio: Double)

    // ---------------- 找锚点 ----------------

    /** 聊天输入框：必须在屏幕偏下的位置，这样顶部的搜索框不会被当成输入框。 */
    /**
     * 屏幕上「像输入框」的 EditText —— 不考核尺寸和显示状态，只看它在屏幕下半部分。
     *
     * 专门用来解释「明明是聊天页，为什么找不到可用的输入框」：
     * 把它的真实尺寸 / isShown 写进诊断，一眼就能看出是哪条判定卡住的。
     */
    fun findInputCandidate(root: View): EditText? {
        var best: EditText? = null
        walk(root, includeInvisible = true) { v ->
            if (best == null && v is EditText && topOf(v) > height * 0.5) best = v
        }
        return best
    }

    /** 会出现在同一带的临时/状态文案，不是会话名。 */
    private val TITLE_NOISE = listOf(
        "对方正在输入", "正在输入", "网络连接不可用", "未连接", "点击重连", "连接中",
        "语音通话中", "视频通话中", "邀请你", "按住说话", "松开 结束",
    )

    /**
     * 聊天页顶部那个标题 —— 也就是这个会话的名字，「角色」功能拿它当 key。
     *
     * 微信没给我们正经接口，只能从界面里找。踩过的坑，按重要性排：
     *
     * 1. **消息列表是从 y=0 铺满整屏的**（穿过工具栏底下），所以列表里某条消息完全可能落在
     *    顶部那一带、还恰好居中 —— 排除「在列表内部的节点」是这里最关键的一条。
     * 2. 标题栏那一带按**相对状态栏**算，不用屏幕高度的固定比例（刘海屏/不同状态栏高度都会偏）。
     * 3. 标题是**水平居中**的。
     * 4. 有的版本标题不是 TextView 而是自绘控件 —— 那种问一遍 TextCapture 的钩子。
     * 5. 「对方正在输入…」这类状态文案和标题在同一带，单独排掉。
     *
     * 第一遍在常规工具栏高度里找；找不到就在更宽的一带里再找一次（有些皮肤工具栏偏高）。
     * 还是找不到就返回 null，调用方会整页跳过。
     */
    fun findChatTitle(root: View, list: ViewGroup?, avoidTexts: List<String> = emptyList()): String? {
        val screenW = width
        val inset = statusBarInset(root)
        val avoid = avoidTexts.map { it.trim() }.filter { it.isNotEmpty() }.toHashSet()
        val bands = listOf(
            (inset - dp(10)).coerceAtLeast(0) to inset + dp(96),
            (inset - dp(10)).coerceAtLeast(0) to inset + dp(150),
        )
        for ((bandTop, bandBottom) in bands) {
            var best: String? = null
            var bestScore = Int.MIN_VALUE
            walk(root, includeInvisible = true) { v ->
                if (v is EditText || !v.isShown) return@walk
                if (list != null && inside(list, v)) return@walk
                val t = if (v is TextView) {
                    v.text?.toString()?.trim().orEmpty()
                } else {
                    TextCapture.textOf(v)?.toString()?.trim().orEmpty()
                }
                if (t.isEmpty() || t.length > 32) return@walk
                if (t in UI_WORDS || looksLikeViewDump(t)) return@walk
                if (t in avoid) return@walk
                if (TITLE_NOISE.any { t.startsWith(it) }) return@walk
                if (v.width < dp(24) || v.height < dp(14)) return@walk
                val loc = IntArray(2)
                v.getLocationOnScreen(loc)
                if (loc[1] < bandTop || loc[1] > bandBottom) return@walk
                val off = abs((loc[0] + v.width / 2f) - screenW / 2f) / screenW.toFloat()
                if (off > 0.16f) return@walk
                // 居中是主判据；宽度和字号只用来打平手（标题通常比旁边的东西更大更宽）
                // 自绘控件的 v 不是 TextView，没有 textSize，取不到就算 0
                val textPx = (v as? TextView)?.textSize ?: 0f
                val score = ((1f - off) * 1000).toInt() +
                    v.width.coerceAtMost(screenW) / 20 +
                    (textPx.coerceAtMost(dp(40).toFloat()) / 4f).toInt()
                if (score > bestScore) {
                    bestScore = score
                    best = t
                }
            }
            if (best != null) return best
        }
        return null
    }

    /** 认不出会话名时，把顶部那一带的候选全列出来（排查用，写进诊断）。 */
    fun describeTitleCandidates(root: View, list: ViewGroup?): String {
        val inset = statusBarInset(root)
        val lo = (inset - dp(10)).coerceAtLeast(0)
        val hi = inset + dp(150)
        val sb = StringBuilder("—— 标题候选（顶部 ${lo}..${hi}）——\n")
        var n = 0
        walk(root, includeInvisible = true) { v ->
            if (n >= 14) return@walk
            if (v is EditText || !v.isShown) return@walk
            val t = if (v is TextView) {
                v.text?.toString()?.trim().orEmpty()
            } else {
                TextCapture.textOf(v)?.toString()?.trim().orEmpty()
            }
            if (t.isEmpty()) return@walk
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            if (loc[1] < lo || loc[1] > hi) return@walk
            n++
            sb.append("  ").append(v.javaClass.simpleName)
                .append(' ').append(v.width).append('x').append(v.height)
                .append('@').append(loc[0]).append(',').append(loc[1])
                .append(" 在列表内=").append(list != null && inside(list, v))
                .append(" 居中偏差=").append(((abs((loc[0] + v.width / 2f) - width / 2f) / width.toFloat()) * 100).toInt()).append('%')
                .append(" \"").append(t.take(22)).append("\"\n")
        }
        if (n == 0) sb.append("  (顶部那一带一个带文字的控件都没有 —— 标题可能不是 TextView)\n")
        return sb.toString()
    }

    /** v 是不是 container 的后代（用来把「消息列表里的文字」和「工具栏标题」分开）。 */
    private fun inside(container: View, v: View): Boolean {
        var p: ViewParent? = v.parent
        var guard = 0
        while (p is View && guard++ < 64) {
            if (p === container) return true
            p = p.parent
        }
        return false
    }

    /** 状态栏高度。标题那一带是相对它算的，不能按屏幕高度取比例。 */
    private fun statusBarInset(root: View): Int = try {
        root.rootWindowInsets?.systemWindowInsets?.top ?: dp(24)
    } catch (t: Throwable) {
        dp(24)
    }

    fun findChatInput(root: View): EditText? {
        var best: EditText? = null
        walk(root) { v ->
            if (best == null && v is EditText && v.isShown && v.width > dp(50) && topOf(v) > height * 0.3) {
                best = v
            }
        }
        return best
    }

    /**
     * 选「消息列表」。规则：
     * 起于输入框上方 / 在屏幕上 / 内部不含输入框（排除页面级容器）/ 够高 / 子视图≥2。
     * 多个候选取面积最大者，面积相同取子视图多的那个。
     * 真机数据（微信 8.0.78，1156x2306，输入框 y=2228）：
     *   选中 ScrollControlRecyclerView 1156x2450@0,0 子=9；MMChattingListView 子=3 为同一块。
     */
    fun findList(root: View, input: View?): ViewGroup? {
        val inputTop = input?.let { topOf(it) } ?: height
        val strict = ArrayList<ViewGroup>()
        val relaxedNotes = ArrayList<String>()
        val rejected = ArrayList<String>()

        walk(root) { v ->
            if (v !is ViewGroup || !v.isShown) return@walk
            val w = v.width
            val h = v.height
            if (w <= 0 || h <= 0) return@walk
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val top = loc[1]
            val bottom = top + h

            if (!looksLikeList(v)) return@walk

            val onScreen = loc[0] < width && loc[0] + w > 0 && bottom > 0 && top < height
            val startsAboveInput = top < inputTop
            val holdsInput = input != null && isAncestor(v, input)
            val tallEnough = h >= height * 0.22
            val multiChild = v.childCount >= 2

            val fatal = buildString {
                if (!onScreen) append("不在屏幕上 ")
                if (!startsAboveInput) append("起点在输入框下方 ")
                if (holdsInput) append("内部含输入框(页面容器) ")
            }.trim()
            val soft = buildString {
                if (!tallEnough) append("太矮 ")
                if (!multiChild) append("子视图<2 ")
            }.trim()

            when {
                fatal.isNotEmpty() -> rejected.add(describe(v) + " [$fatal]")
                soft.isEmpty() -> strict.add(v)
                else -> relaxedNotes.add(describe(v) + " [$soft]")
            }
        }

        val chosen = strict.maxWithOrNull(compareBy({ it.width.toLong() * it.height }, { it.childCount }))
        lastCandidates = if (chosen == null) {
            "入选=0；位置不合格=${rejected.take(6).joinToString(" / ").ifEmpty { "无" }}；" +
                "仅差高度/子视图=${relaxedNotes.take(3).joinToString(" / ").ifEmpty { "无" }}"
        } else {
            "入选=${strict.size}，选中=${describe(chosen)}；" +
                "仅差高度/子视图=${relaxedNotes.take(3).joinToString(" / ").ifEmpty { "无" }}；" +
                "位置不合格=${rejected.take(4).joinToString(" / ").ifEmpty { "无" }}"
        }
        return chosen
    }

    /** 「像个能滚动的列表」—— findList 和 [conversationNames] 共用一套，免得两处规则跑偏。 */
    internal fun looksLikeList(v: View): Boolean = v is AbsListView || v is ScrollView ||
        v.javaClass.name.contains("RecyclerView") ||
        v.javaClass.name.contains("ListView") ||
        v.javaClass.name.contains("ScrollView") ||
        v.canScrollVertically(1) || v.canScrollVertically(-1)

    /**
     * 当前这一屏（微信首页 / 通讯录）里看得见的会话名 —— 「白名单」页的候选靠它。
     *
     * 判据两条，缺一不可：
     * 1. **容器像列表**：有滚动能力、够高、子视图够多。不认类名、也不判断「这是不是首页」——
     *    靠类名猜页面（LauncherUI…）正是这个模块一直在避免的事（微信一改版本就失效）。
     * 2. **行像会话**：左边有方形头像，或者带时间角标（见 [RowShape.looksLikeChat]）。
     *    这条是后补的，也是真机反馈的根因：**个人资料页**（点开头像进去那一页）也是一行行的列表，
     *    只按第 1 条判断的话它整页都会被当成「会话列表」，候选里全是「微信号：xxx」这种杂项，
     *    真正的名字（备注）反而读不到。
     *
     * 返回 (名字们, 现场说明)：拉不到东西时，说明里那一句就是排查的全部线索。
     */
    fun conversationNames(root: View): Pair<List<String>, String> {
        val lists = ArrayList<ViewGroup>()
        walk(root) { v ->
            if (v !is ViewGroup || !v.isShown) return@walk
            if (v.childCount < 3) return@walk
            if (!looksLikeList(v)) return@walk
            val h = v.height
            if (h <= 0) return@walk
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            if (loc[1] + h <= 0 || loc[1] >= height) return@walk
            // 会话列表占小半个屏幕以上；太矮的多半是底部 tab 栏或某个折叠区
            if (h < height * 0.25f) return@walk
            lists.add(v)
        }
        if (lists.isEmpty()) return emptyList<String>() to "没找到像「会话列表」的容器"

        val names = LinkedHashSet<String>()
        var rows = 0       // 扫过的行
        var chatRows = 0   // 通过「像会话」形状闸的行
        var accepted = 0   // 真正被采纳的容器
        // 取**并集**而不是「面积最大的那个」：微信经常是外层 RecyclerView 套内层，
        // 只挑最大的会挑到外层，那一屏就只剩一行（里面还套着列表），读出 0 个名字。
        for (list in lists) {
            var localChatRows = 0
            val localNames = ArrayList<String>()
            for (i in 0 until list.childCount) {
                val row = list.getChildAt(i) ?: continue
                if (!row.isShown) continue
                // 这一格里还套着一个列表 → 它是容器、不是一行，跳过（外层包内层就是这个形状）
                if (holdsList(row)) continue
                rows++
                // 形状闸：不像会话行的一律不参与挑名字（资料页 / 设置页那些行全卡在这里）
                if (!rowShape(row).looksLikeChat) continue
                localChatRows++
                pickRowName(rowCandidates(row))?.let { localNames.add(it) }
            }
            // 整页至少两条会话行才采纳：资料页顶部有一个大头像（也算一行），
            // 门槛放到 1 会被它骗过去 —— 两条一起出现才基本只可能是会话列表。
            if (localChatRows >= MIN_CHAT_ROWS) {
                accepted++
                chatRows += localChatRows
                names.addAll(localNames)
            }
        }
        if (accepted == 0) {
            return emptyList<String>() to
                "容器=${lists.size}｜行=$rows｜会话行=0 —— 这一屏不像会话列表"
        }
        return names.toList() to
            "容器=$accepted（扫过 ${lists.size}）｜行=$rows｜会话行=$chatRows｜认出名字=${names.size}"
    }

    /** 量一行的形状：最左边有没有方形头像、有没有时间角标、有几个带文字的控件。 */
    internal fun rowShape(row: View): RowShape {
        var avatarSize = 0
        var timeMark = false
        var textCount = 0
        val leftLimit = width * 0.30f
        walk(row) { v ->
            if (!v.isShown) return@walk
            if (v is ImageView) {
                val w = if (v.width > 0) v.width else v.measuredWidth
                val h = if (v.height > 0) v.height else v.measuredHeight
                // 方形 + 正常头像尺寸 + 贴左边 —— 会话行就是这么摆的
                if (w in dp(24)..dp(84) && h > 0 && abs(w - h) <= dp(4) && leftOf(v) < leftLimit) {
                    if (w > avatarSize) avatarSize = w
                }
                return@walk
            }
            val t = (if (v is TextView) v.text?.toString() else TextCapture.textOf(v)?.toString())
                ?.trim().orEmpty()
            if (t.isNotEmpty()) {
                textCount++
                if (Chrome.isChrome(t)) timeMark = true
            }
        }
        return RowShape(avatarSize, timeMark, textCount)
    }

    /** row 里面还套着另一个「像列表」的容器吗（外层 RecyclerView 包内层的典型形状）。 */
    internal fun holdsList(row: View): Boolean {
        if (row !is ViewGroup) return false
        var found = false
        walk(row) { v ->
            if (found || v === row || v !is ViewGroup) return@walk
            if (v.childCount >= 3 && looksLikeList(v)) found = true
        }
        return found
    }

    /** 把一行里所有带文字的子视图收成候选，交给纯函数 [pickRowName] 挑。 */
    internal fun rowCandidates(row: View): List<NameCandidate> {
        val out = ArrayList<NameCandidate>()
        walk(row, includeInvisible = false) { v ->
            if (!v.isShown) return@walk
            val t = (if (v is TextView) v.text?.toString() else TextCapture.textOf(v)?.toString())
                ?.trim().orEmpty()
            if (t.isEmpty() || t.length > 40) return@walk
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            out.add(NameCandidate(t, (v as? TextView)?.textSize ?: 0f, loc[1], loc[0]))
        }
        return out
    }

    // ---------------- 读内容 ----------------

    /**
     * 变化指纹：只读最后一行。
     * 「有没有新消息」看最后一行就够，比每轮把整棵树走一遍便宜得多。
     */
    fun fingerprint(list: ViewGroup): String {
        val n = list.childCount
        if (n == 0) return "0"
        val sb = StringBuilder(n.toString())
        walk(list.getChildAt(n - 1), includeInvisible = true) { v ->
            val t = if (v is TextView && v !is EditText) {
                v.text?.toString()?.trim().orEmpty()
            } else {
                TextCapture.textOf(v)?.toString()?.trim().orEmpty()
            }
            if (t.isNotEmpty()) sb.append('|').append(t.take(48))
        }
        return sb.toString()
    }

    fun snapshot(list: ViewGroup): List<RowSnapshot> {
        val rows = ArrayList<RowSnapshot>(list.childCount)
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i)
            if (!row.isShown) continue
            rows.add(runCatching { readRow(row) }.getOrElse { RowSnapshot(null) })
        }
        return rows
    }

    private fun readRow(row: View): RowSnapshot {
        val nodes = ArrayList<Pair<TextNode, BubbleHit?>>(6)
        val avatars = ArrayList<AvatarNode>(2)
        val spoken = ArrayList<TextNode>(5)

        // includeInvisible = true：微信可能把正文放在 INVISIBLE 的占位控件里，
        // 只认 isShown() 会把正文整条漏掉。这里只跳过 GONE。
        walk(row, includeInvisible = true) { v ->
            when {
                v is ImageView -> {
                    val w = if (v.width > 0) v.width else v.measuredWidth
                    val h = if (v.height > 0) v.height else v.measuredHeight
                    if (w in dp(24)..dp(84) && h > 0 && abs(w - h) <= dp(4)) {
                        val cx = centerX(v)
                        if (cx < width * 0.25 || cx > width * 0.75) avatars.add(AvatarNode(cx, topOf(v), w))
                    }
                    // 头像上的 contentDescription 通常是联系人名，不当正文
                }

                v is TextView && v !is EditText -> {
                    val text = v.text?.toString()?.trim().orEmpty()
                    if (okText(text) && v.width > 0 && v.height > 0) {
                        val kind = when {
                            Chrome.isTime(text) -> Kind.TIMESTAMP
                            Chrome.isTag(text) -> Kind.TAG
                            else -> Kind.OTHER
                        }
                        nodes.add(TextNode(text, topOf(v), kind, v.textSize, v.isShown) to bubbleOf(v, row))
                    } else {
                        // text 为空的 TextView 子类（自己画字的）在这里也要问一次钩子 ——
                        // 之前只有「非 TextView」分支问，这类控件就一直读不到字，只能退到反射拿类名。
                        val caught = TextCapture.textOf(v)?.toString()?.trim().orEmpty()
                        val t = if (okText(caught)) caught else fallbackText(v)
                        if (t.isNotEmpty()) {
                            spoken.add(TextNode(t, topOf(v), Kind.OTHER, v.textSize, v.isShown))
                        } else if (v.width >= dp(40) && v.height >= dp(18)) {
                            TextCapture.hookClass(v.javaClass)
                        }
                    }
                }

                else -> {
                    // 自己画字的控件（如 MMNeat7extView）：先看钩子抓到的原文
                    val caught = TextCapture.textOf(v)?.toString()?.trim().orEmpty()
                    if (okText(caught)) {
                        nodes.add(TextNode(caught, topOf(v), Kind.OTHER, 0f, v.isShown) to bubbleOf(v, row))
                        return@walk
                    }
                    val t = fallbackText(v)
                    if (t.isNotEmpty()) {
                        nodes.add(TextNode(t, topOf(v), Kind.OTHER, 0f, v.isShown) to bubbleOf(v, row))
                        return@walk
                    }
                    // 还没有文字、又有正常尺寸 —— 它很可能就是正文控件：挂上 setText 钩子，
                    // 下次微信重新绑定这一行时就能拿到原文
                    if (v.width >= dp(40) && v.height >= dp(18)) TextCapture.hookClass(v.javaClass)
                }
            }
        }

        // 第二遍：整行一个字都没读到 —— 那就问无障碍节点（微信自绘正文时通常只在这里留文字）
        if (nodes.none { it.first.kind == Kind.OTHER } && spoken.isEmpty()) {
            val budget = intArrayOf(6)
            walk(row, includeInvisible = true) { v ->
                if (budget[0] > 0 && v.width > 0 && v.height > 0) {
                    budget[0]--
                    val a11y = a11yText(v)
                    if (okText(a11y)) {
                        nodes.add(TextNode(a11y, topOf(v), Kind.OTHER, 0f, v.isShown) to bubbleOf(v, row))
                    }
                }
            }
        }

        // 一行里的「正文」= 位置最低的气泡文字块（上面那块通常是「引用」的旧消息）
        val bubbleNodes = nodes.filter { it.second != null }
        val visibleBubble = bubbleNodes.filter { it.first.visible }
        val lower = (if (visibleBubble.isNotEmpty()) visibleBubble else bubbleNodes).maxByOrNull { it.first.top }
        val bubbleSize = lower?.first?.size ?: 0f

        val bubble = if (lower != null) {
            val hit = lower.second!!
            Bubble(lower.first.text, hit.side, hit.centerRatio, lower.first.top)
        } else {
            fallbackBubble(nodes, spoken, avatars)
        }

        val texts = nodes.map { (node, hit) ->
            val b = bubble
            when {
                node.kind != Kind.OTHER -> node
                hit != null && hit.side != Side.UNKNOWN -> node.copy(kind = Kind.BUBBLE)
                b != null && b.text != null && node.top <= b.top - dp(2) &&
                    (bubbleSize <= 0f || node.size < bubbleSize) -> node.copy(kind = Kind.NICKNAME)
                else -> node
            }
        }

        // 只有「值得找图的一行」才去找图（有正文的行不用管图；[语音] / [表情] 这类也跳过 ——
        // 那些行里没有能认的文字，第 21 版起不再白认一次），顺手记下现场。
        // 找不到就照旧 null —— 没有这一步时是什么样，现在还是什么样。
        val scan = if (bubble?.text == null || isImageLikeAttachment(bubble.text.orEmpty())) {
            scanImage(row, bubble?.text)
        } else {
            null
        }
        val image = scan?.hit?.let { hit -> RowImage(hit.w, hit.h) { side -> jpegOf(hit, side) } }

        return RowSnapshot(bubble, texts, avatars, image, scan?.probe)
    }

    /** 一次「找图」的结果：命中的控件 + 一句话现场（诊断用）。 */
    private class ImgScan(val hit: ImgHit?, val probe: String)

    /** 一个候选控件：尺寸、嵌套深度、够不够「大」、取像素的方式、一句描述。 */
    private class ImgHit(
        val view: View,
        val w: Int,
        val h: Int,
        val depth: Int,
        /** 短边 > 84dp 且不是文字控件 —— 头像 / 表情都在这个门槛以下 */
        val big: Boolean,
        /** true = 只能靠 view.draw() 画出来（不是 ImageView，或者它的 drawable 不是位图） */
        val viaDraw: Boolean,
        val kind: String,
    )

    /**
     * 在一行里找那张「消息图」，顺便给出一句「看到了什么」。
     *
     * 判据只看尺寸，不认类名（微信一改版本类名就变）：**短边 > 84dp 且不是文字控件**。
     * 头像（24~84dp 的方形）和表情都在这个门槛以下。
     *
     * ⚠️ 这里**只排除 GONE，不看 isShown** —— 微信把内容塞进 INVISIBLE 的占位控件里是老毛病了
     * （[readRow] 读文字时也是这么放宽的），而 `view.draw()` 对 INVISIBLE 的控件照样画得出内容。
     * 第 19 版用了 isShown，真机上就报「图明明在那儿，却一个候选都没有」。
     *
     * 取像素分两档：① ImageView + BitmapDrawable → 直接拿原始位图（最清楚也最省）；
     * ② 其他控件 → `view.draw()` 画进位图（自绘的只能这么取，且必须在主线程）。
     *
     * 挑哪个（从好到差）：[大 + 叶子] → [大 + 最深容器] → [兜底：任意叶子，排除头像 / 方形小图，短边 ≥ 48dp]。
     *
     * 兜底那档是给「图不在大控件里」的真机情况留的（第 20 版实测确实有），但**收紧**过：
     * 头像、表情包那种方形小图一律不算候选 —— 否则一屏表情包会一个接一个地白认。
     *
     * ⚠️ 第 21 版**删掉了「连整行本身都收」**那一档：整行画下来会把昵称、时间这些界面文字
     * 一起认成消息内容（比「认不出来」更糟，还会被当成对方说的话）。现在宁可就这一行返回「没找到」，
     * 回落成原来的 `[ATTACHMENT_TEXT]` 占位 —— 行为和装 OCR 之前一样。
     */
    internal fun scanImage(row: View, bubbleText: String?): ImgScan {
        val rowW = if (row.width > 0) row.width else row.measuredWidth
        val rowH = if (row.height > 0) row.height else row.measuredHeight
        val minSide = dp(84)
        val tiny = dp(32)
        val hits = ArrayList<ImgHit>(8)
        val top = ArrayList<ImgHit>(4)
        var visited = 0

        fun visit(v: View, depth: Int) {
            visited++
            val w = if (v.width > 0) v.width else v.measuredWidth
            val h = if (v.height > 0) v.height else v.measuredHeight
            val isText = v is TextView || v is EditText
            if (!isText && w > 0 && h > 0 && v.visibility != View.GONE && minOf(w, h) >= tiny) {
                val iv = v as? ImageView
                val bmp = (iv?.drawable as? BitmapDrawable)?.bitmap?.takeIf { !it.isRecycled }
                val hit = ImgHit(
                    view = v,
                    w = w,
                    h = h,
                    depth = depth,
                    big = minOf(w, h) > minSide,
                    viaDraw = bmp == null,
                    kind = v.javaClass.simpleName + when {
                        bmp != null -> "（位图 ${bmp.width}x${bmp.height}）"
                        iv != null -> "（drawable=${iv.drawable?.javaClass?.simpleName ?: "null"}）"
                        else -> "（自绘）"
                    },
                )
                hits.add(hit)
                top.add(hit)
                if (top.size > 3) {
                    top.sortByDescending { it.w * it.h }
                    top.removeAt(3)
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i), depth + 1)
        }
        visit(row, 0)

        // 头像：正方形 + 贴在两侧（和 readRow 里认头像的规则同一套）
        fun isAvatarLike(h: ImgHit): Boolean {
            if (abs(h.w - h.h) > dp(4)) return false
            val cx = centerX(h.view)
            return cx < width * 0.25 || cx > width * 0.75
        }

        // 方形小图 = 头像 / 表情包那一类（和认头像同一套容差）。拿它们去 OCR 纯属白认。
        fun looksSquareSmall(h: ImgHit): Boolean = abs(h.w - h.h) <= dp(4) && minOf(h.w, h.h) <= minSide

        val hit = hits.filter { it.big && it.view !is ViewGroup }.maxByOrNull { it.w * it.h }
            ?: hits.filter { it.big && it.view !== row }.maxByOrNull { it.depth * 10_000_000 + it.w * it.h }
            // 兜底档（第 21 版收紧）：排除头像 / 表情那种方形小图，且短边至少 48dp ——
            // 把语音波形、小图标这类噪声挡在外面。原来还有一档「连整行都收」，已删除：
            // 画整行会把昵称、时间这些界面文字一起喂进模型，比认不出来更糟。
            ?: hits.filter {
                it.view !is ViewGroup && !isAvatarLike(it) && !looksSquareSmall(it) &&
                    minOf(it.w, it.h) >= dp(48)
            }.maxByOrNull { it.w * it.h }

        val probe = buildString {
            append("行 ").append(rowW).append('x').append(rowH).append(" 视图 ").append(visited)
            bubbleText?.takeIf { it.isNotBlank() }?.let { append(" 正文=「").append(it.take(10)).append("」") }
            append("｜")
            if (top.isEmpty()) {
                append("行内没有非文字控件（短边 >").append(tiny).append("px）")
            } else {
                top.sortedByDescending { it.w * it.h }.forEachIndexed { index, h ->
                    if (index > 0) append("｜")
                    append(index + 1).append(')').append(h.kind).append(' ')
                    append(h.w).append('x').append(h.h).append(" v=").append(h.view.visibility)
                }
                // 有候选却一个都没被选中：说清楚是被收紧规则挡掉的，省得又以为是「取不到图」
                if (hit == null) append("｜⚠ 这些都没被采纳（太小 / 方形小图），这一行不认图")
            }
        }.take(240)

        return ImgScan(hit, probe)
    }

    /** 把命中的控件变成一份 JPEG（长边缩到 [maxSide]）。null = 这一张取不到。 */
    private fun jpegOf(hit: ImgHit, maxSide: Int): ByteArray? {
        // ① 原始位图：ImageView 且 drawable 就是位图
        val raw = if (hit.viaDraw) {
            null
        } else {
            (hit.view as? ImageView)?.drawable?.let { (it as? BitmapDrawable)?.bitmap }
        }
        val bitmap: Bitmap = if (raw != null && !raw.isRecycled && raw.width > 0 && raw.height > 0) {
            val k = scaleFactor(maxOf(raw.width, raw.height), maxSide)
            if (k >= 1f) raw else scaleBitmap(raw, k) ?: return null
        } else {
            // ② 自绘控件：只能画出来（必须在主线程）
            drawView(hit, maxSide) ?: return null
        }
        val own = if (bitmap === raw) null else bitmap
        val out = java.io.ByteArrayOutputStream()
        val ok = runCatching { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out) }.getOrDefault(false)
        // 只回收自己造的那一份；原始位图是微信的，绝不能碰
        own?.let { runCatching { it.recycle() } }
        return if (ok) out.toByteArray() else null
    }

    private fun scaleFactor(longest: Int, maxSide: Int): Float =
        if (maxSide > 0 && longest > maxSide) maxSide.toFloat() / longest else 1f

    private fun scaleBitmap(src: Bitmap, k: Float): Bitmap? = runCatching {
        Bitmap.createScaledBitmap(
            src,
            (src.width * k).toInt().coerceAtLeast(1),
            (src.height * k).toInt().coerceAtLeast(1),
            true,
        )
    }.getOrNull()

    /**
     * 把一个自绘控件画进位图。
     *
     * ⚠️ **必须在主线程**（`View.draw` 不是线程安全的）。按目标尺寸建位图再 `canvas.scale`，
     * 省掉「先全尺寸画一遍、再缩一次」的那次内存峰值。
     */
    private fun drawView(hit: ImgHit, maxSide: Int): Bitmap? = runCatching {
        val k = scaleFactor(maxOf(hit.w, hit.h), maxSide)
        val w = (hit.w * k).toInt().coerceAtLeast(1)
        val h = (hit.h * k).toInt().coerceAtLeast(1)
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
            val canvas = Canvas(bmp)
            canvas.scale(k, k)
            hit.view.draw(canvas)
        }
    }.getOrNull()

    /**
     * 反射兜底取正文。
     *
     * 微信的自绘正文控件不继承 TextView，但很多这类控件仍然保留一个 `getText()` 方法
     * （它们自己也要序列化/复制文本）。每个类只查一次方法，查不到就记住 null。
     */
    private fun reflectText(v: View): String {
        val cls = v.javaClass
        val method = if (textMethods.containsKey(cls)) {
            textMethods[cls]
        } else {
            var found: java.lang.reflect.Method? = null
            for (name in TEXT_METHODS) {
                found = try {
                    cls.getMethod(name).takeIf { CharSequence::class.java.isAssignableFrom(it.returnType) }
                } catch (t: Throwable) {
                    null
                }
                if (found != null) break
            }
            textMethods[cls] = found
            found
        } ?: return ""
        return try {
            (method.invoke(v) as? CharSequence)?.toString()?.trim().orEmpty()
        } catch (t: Throwable) {
            ""
        }
    }

    /**
     * 暴力反射：把这个类所有「无参、返回 CharSequence」的方法都调一遍，取最长的。
     * 不知道微信把文字存在哪个字段/哪个 getter 时，这招最省事（每个类只收集一次方法列表）。
     */
    private fun reflectAnyText(v: View): String {
        val cls = v.javaClass
        val methods = if (anyTextMethods.containsKey(cls)) {
            anyTextMethods[cls]
        } else {
            val found = try {
                cls.methods.filter {
                    it.parameterCount == 0 &&
                        CharSequence::class.java.isAssignableFrom(it.returnType) &&
                        // 「别把控件自述当聊天文字」三道闸：
                        // getAccessibilityClassName() 在 TextView 里被 override 过，declaringClass
                        // 变成 TextView 而不是 View，光排除 View 挡不住；toString() 声明在 Object 上，
                        // 同样挡不住 —— 它俩一个返回 "android.widget.TextView"，
                        // 一个返回 "android.widget.LinearLayout{...}"。
                        it.name !in BAD_TEXT_METHODS &&
                        it.declaringClass != View::class.java &&
                        it.declaringClass != ViewGroup::class.java &&
                        it.declaringClass != Any::class.java
                }.take(30)
            } catch (t: Throwable) {
                emptyList()
            }
            anyTextMethods[cls] = found
            found
        } ?: return ""
        var best = ""
        for (m in methods) {
            val text = try {
                (m.invoke(v) as? CharSequence)?.toString()?.trim().orEmpty()
            } catch (t: Throwable) {
                ""
            }
            if (looksLikeViewDump(text)) continue
            if (text.length in (best.length + 1)..400) best = text
        }
        return best
    }

    /** 第一层兜底：无障碍描述 -> 反射 getText 之类。 */
    private fun fallbackText(v: View): String {
        val desc = v.contentDescription?.toString()?.trim().orEmpty()
        if (okText(desc)) return desc
        val reflected = reflectText(v)
        if (okText(reflected)) return reflected
        val any = reflectAnyText(v)
        if (okText(any)) return any
        return ""
    }

    private fun a11yText(v: View): String = try {
        v.createAccessibilityNodeInfo()?.text?.toString()?.trim().orEmpty()
    } catch (t: Throwable) {
        ""
    }

    /**
     * 文本合法性 —— 所有「可能是聊天内容」的字符串都必须过这里。
     *
     * 除了长度和噪音词，还专门挡「看起来像 Java 类名 / 控件自述」的串。真实事故：
     * `View.getAccessibilityClassName()` 返回 "android.widget.TextView"、
     * `View.toString()` 返回 "android.widget.LinearLayout{...}"，
     * 反射兜底把它们当成「最长的文字」选走了，于是发给模型的聊天记录变成一串类名，
     * 模型只能顺着编 Android 梗（ConstraintLayout / RelativeLayout 就是这么来的）。
     */
    private fun okText(s: String): Boolean =
        s.length in 2..400 && s !in UI_WORDS && !looksLikeViewDump(s)

    /**
     * 形如 `a.b.C` 或 `a.b.C{...}` 的串一律不是聊天内容。
     * 要求末段首字母大写，免得误伤 `www.baidu.com` 这种正常文本。
     */
    private fun looksLikeViewDump(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return false
        if (FQN_RE.matches(t)) return true
        // "android.widget.LinearLayout{...}" 这种：去掉 {...} 之后再看一眼前半段
        val head = t.substringBefore('{').trim()
        if (head.length < t.length && head.length >= 8 && FQN_RE.matches(head)) return true
        // 兜底：框架包名开头的一律不是聊天内容
        return FRAMEWORK_PREFIXES.any { t.startsWith(it) }
    }

    /**
     * 认不出气泡容器时的兜底：
     * 先取最长的非噪音文字，再退到无障碍描述，最后才是「有头像的行 = 图片/表情」。
     */
    private fun fallbackBubble(
        nodes: List<Pair<TextNode, BubbleHit?>>,
        spoken: List<TextNode>,
        avatars: List<AvatarNode>,
    ): Bubble? {
        val texts = nodes.map { it.first }
        val visibleTexts = texts.filter { it.visible }
        (if (visibleTexts.isNotEmpty()) visibleTexts else texts)
            .filter { it.kind == Kind.OTHER }
            .maxByOrNull { it.text.length }
            ?.let { return Bubble(it.text, Side.UNKNOWN, null, it.top) }

        val visibleSpoken = spoken.filter { it.visible }
        (if (visibleSpoken.isNotEmpty()) visibleSpoken else spoken)
            .maxByOrNull { it.text.length }
            ?.let { return Bubble(it.text, Side.UNKNOWN, null, it.top) }

        val avatar = avatars.minByOrNull { it.top } ?: return null
        return Bubble(null, Side.UNKNOWN, null, avatar.top)
    }

    // ---------------- 诊断 ----------------

    /**
     * 结构快照：真机上「为什么读不到 / 为什么全是图片」靠它定位，不用猜。
     * 会写进 LSPosed 日志，同时广播回 App 首页，可以直接复制发出来。
     */
    fun diagnose(root: View, list: ViewGroup?, input: View?, rowCount: Int = 3): String {
        val sb = StringBuilder()
        sb.append("DIAG 屏=").append(width).append('x').append(height)
            .append(" dp=").append(density).append(" 夜间=").append(night).append(NL)
        sb.append("输入框: ").append(input?.let { describe(it) } ?: "未找到").append(NL)
        sb.append("消息列表: ").append(list?.let { describe(it) } ?: "未找到").append(NL)
        sb.append("候选列表: ").append(lastCandidates).append(NL)
        if (list != null) {
            for (r in 0 until minOf(rowCount, list.childCount)) {
                if (sb.length > 5600) break
                val row = list.getChildAt(r)
                sb.append("行").append(r).append(' ').append(describe(row)).append(NL)
                // 先给出「读取器对这几行的判定」，一眼看出卡在哪
                val snap = runCatching { readRow(row) }.getOrNull()
                sb.append("  判定: 气泡=")
                    .append(snap?.bubble?.text?.take(20) ?: "null")
                    .append(" 头像=").append(snap?.avatars?.size ?: 0)
                    .append(" 文字=")
                    .append(snap?.texts?.take(4)?.joinToString("|") { it.kind.name + ":" + it.text.take(12) } ?: "-")
                    .append(NL)
                dumpRow(sb, row, 40)
            }
        }
        return sb.toString().take(6200)
    }

    /**
     * 把一行控件全部摊开打印 —— 定位「正文到底藏在哪个控件里」。
     * 每条形如 `#2 MMTextView 700x120@180,320 v0S t="在吗"`
     * v 后面是 visibility（0=VISIBLE / 4=INVISIBLE / 8=GONE），S=isShown、s=不是。
     */
    private fun dumpRow(sb: StringBuilder, row: View, maxEntries: Int) {
        var count = 0
        fun dump(v: View, depth: Int) {
            if (count >= maxEntries || sb.length > 5600) return
            count++
            sb.append("  ".repeat(depth))
                .append('#').append(count - 1).append(' ')
                .append(v.javaClass.name).append(' ')
                .append(v.width).append('x').append(v.height)
                .append('@').append(leftOf(v)).append(',').append(topOf(v))
                .append(" v").append(v.visibility).append(if (v.isShown) 'S' else 's')
            val text = (v as? TextView)?.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) sb.append(" t=\"").append(text.take(18)).append('"')
            val desc = v.contentDescription?.toString()?.trim().orEmpty()
            if (desc.isNotEmpty()) sb.append(" d=\"").append(desc.take(18)).append('"')
            val caught = TextCapture.textOf(v)?.toString()?.trim().orEmpty()
            if (caught.isNotEmpty()) sb.append(" c=\"").append(caught.take(18)).append('"')
            if (text.isEmpty() && desc.isEmpty() && v !is ViewGroup) {
                val reflected = reflectText(v)
                if (reflected.isNotEmpty()) sb.append(" r=\"").append(reflected.take(18)).append('"')
            }
            if (text.isEmpty() && desc.isEmpty() && v !is ViewGroup && count <= 10) {
                val a11y = try {
                    v.createAccessibilityNodeInfo()?.text?.toString()?.trim().orEmpty()
                } catch (t: Throwable) {
                    ""
                }
                if (a11y.isNotEmpty()) sb.append(" a=\"").append(a11y.take(18)).append('"')
            }
            sb.append(NL)
            // 头像（正方形）那层不展开，省下预算给气泡内部
            val avatarLike = v is ImageView && v.width in dp(24)..dp(84) && abs(v.width - v.height) <= dp(4)
            if (v is ViewGroup && depth < 5 && !avatarLike) {
                for (i in 0 until minOf(v.childCount, 12)) dump(v.getChildAt(i), depth + 1)
            }
        }
        dump(row, 1)
    }

    private fun describe(v: View): String {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val kids = if (v is ViewGroup) v.childCount else 0
        return "${v.javaClass.simpleName} ${v.width}x${v.height}@${loc[0]},${loc[1]} 子=$kids"
    }

    private fun leftOf(v: View): Int {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return loc[0]
    }

    // ---------------- 气泡与取色 ----------------

    /**
     * 从文字块往上找气泡容器：第一个「不满屏宽、且看起来像气泡」的背景。
     * 满屏宽度的背景是消息行自己的底色，不算气泡 —— 这条判断避免整行被误判。
     */
    private fun bubbleOf(v: View, row: View): BubbleHit? {
        bubbleCache[v]?.let { return it }
        var c: View? = v
        var hit: BubbleHit? = null
        while (c != null) {
            val bg = c.background
            if (bg != null) {
                val w = if (c.width > 0) c.width else c.measuredWidth
                val ratio = if (width > 0) w.toDouble() / width else 1.0
                if (ratio < 0.92) {
                    val px = sample(bg)
                    if (((px ushr 24) and 0xFF) >= 24) {
                        val looksLikeBubble = bg is NinePatchDrawable || bg is BitmapDrawable ||
                            bg.javaClass.name.contains("Bubble", ignoreCase = true) ||
                            colorDistance(px, pageBg) > 20
                        if (looksLikeBubble) {
                            hit = BubbleHit(classify(px), centerX(c).toDouble() / width.coerceAtLeast(1), ratio)
                            break
                        }
                    }
                }
            }
            if (c === row) break
            c = c.parent as? View
        }
        if (hit != null) bubbleCache[v] = hit
        return hit
    }

    private fun sample(drawable: Drawable): Int = try {
        val d = drawable.constantState?.newDrawable()?.mutate() ?: drawable
        canvas.setBitmap(scratch)
        scratch.eraseColor(0)
        d.setBounds(0, 0, 64, 64)
        d.draw(canvas)
        canvas.setBitmap(null)
        scratch.getPixel(32, 32)
    } catch (t: Throwable) {
        0
    }

    private fun classify(px: Int): Side {
        if (((px ushr 24) and 0xFF) < 24) return Side.UNKNOWN
        val r = (px shr 16) and 0xFF
        val g = (px shr 8) and 0xFF
        val b = px and 0xFF
        return if (g - r > 16 && g - b > 10) Side.ME else Side.OTHER
    }

    private fun colorDistance(a: Int, b: Int): Int {
        val dr = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        val dg = abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        val db = abs((a and 0xFF) - (b and 0xFF))
        return dr + dg + db
    }

    private fun detectPageBackground(): Int {
        val decor = a.window?.decorView
        val fallback = if (night) 0xFF121212.toInt() else 0xFFFFFFFF.toInt()
        if (decor == null) return fallback
        var found = 0
        walk(decor) { v ->
            if (found == 0) {
                val bg = v.background ?: return@walk
                val px = sample(bg)
                if (((px ushr 24) and 0xFF) == 0xFF) found = px
            }
        }
        return if (found != 0) found else fallback
    }

    // ---------------- 遍历与小工具 ----------------

    /**
     * 迭代式深度优先遍历（Kotlin 不允许递归的 inline 函数，用显式栈）。
     *
     * @param includeInvisible true 时只跳过 GONE。
     *   读正文必须为 true：微信会把正文放在 INVISIBLE 的占位控件里。
     *   找输入框/列表时保持 false（只认真正显示的控件）。
     */
    internal fun walk(root: View, includeInvisible: Boolean = false, action: (View) -> Unit) {
        val stack = ArrayDeque<View>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 5000) {
            val v = stack.removeLast()
            visited++
            if (v !== root) {
                val blocked = if (includeInvisible) v.visibility == View.GONE else !v.isShown
                if (blocked) continue
            }
            action(v)
            if (v is ViewGroup) {
                for (i in v.childCount - 1 downTo 0) stack.addLast(v.getChildAt(i))
            }
        }
    }

    /** 判断 parent 是否是 child 的祖先（往上走，比往下遍历便宜）。 */
    private fun isAncestor(parent: View, child: View): Boolean {
        var p: ViewParent? = child.parent
        var guard = 0
        while (p is View && guard++ < 60) {
            if (p === parent) return true
            p = p.parent
        }
        return false
    }

    private fun topOf(v: View): Int {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return loc[1]
    }

    private fun centerX(v: View): Int {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val w = if (v.width > 0) v.width else v.measuredWidth
        return loc[0] + w / 2
    }

    private companion object {
        /** 避免在源码里写转义序列。 */
        val NL: String = System.lineSeparator()

        /** 反射取正文时依次尝试的方法名。 */
        val TEXT_METHODS = listOf("getText", "getTextContent", "getTextString", "getMessage")

        /** 这些方法返回的 CharSequence 一定不是聊天内容（控件自述 / 状态描述）。 */
        val BAD_TEXT_METHODS = setOf(
            "toString",
            "getAccessibilityClassName",
            "getClass",
            "getTransitionName",
            "getStateDescription",
            "getContentDescription",
            "getTooltipText",
            "getError",
            "getHint",
        )

        /**
         * `android.widget.TextView` 这种：全是点分标识符、末段首字母大写。
         * 刻意不把 `$` 写进字符类 —— 在 Kotlin 字符串里它是模板起始符，容易出幺蛾子，
         * 而带 `$` 的内部类名字符串还有 FRAMEWORK_PREFIXES 那条兜底。
         */
        // matches() 本身就是整串匹配，不用 ^ / $ 锚点（也避开 Kotlin 字符串里的 $ 模板歧义）
        val FQN_RE = Regex("([A-Za-z_][A-Za-z0-9_]*\\.)+[A-Z][A-Za-z0-9_]*")

        /** 这些包名开头的一律不是聊天内容。 */
        val FRAMEWORK_PREFIXES = listOf(
            "android.", "androidx.", "java.", "javax.", "kotlin.", "dalvik.",
            "com.tencent.", "com.android.", "com.google.android.",
        )

        /** 纯 UI 文案的无障碍描述，不当消息正文。 */
        val UI_WORDS = setOf(
            "头像", "表情", "更多功能", "更多", "返回", "发送", "语音输入", "加号",
            "图片", "视频", "按住 说话", "切换键盘", "菜单", "关闭", "搜索", "聊天信息",
        )
    }
}
