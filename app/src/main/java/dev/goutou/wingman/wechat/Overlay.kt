package dev.goutou.wingman.wechat

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.content.SharedPreferences
import dev.goutou.wingman.XposedApi
import dev.goutou.wingman.Heartbeat
import dev.goutou.wingman.config.ConfigData
import dev.goutou.wingman.config.chatBlocked
import dev.goutou.wingman.config.Keys
import dev.goutou.wingman.config.Roles
import dev.goutou.wingman.config.RoleMsg
import dev.goutou.wingman.llm.LlmClient
import dev.goutou.wingman.llm.LlmException
import dev.goutou.wingman.llm.Graded
import dev.goutou.wingman.llm.gradedWaitMs
import dev.goutou.wingman.llm.isReplyTooLong
import dev.goutou.wingman.proxy.ProxyProtocol
import dev.goutou.wingman.llm.REWRITE_PRESETS
import dev.goutou.wingman.llm.Reply
import dev.goutou.wingman.llm.Suggestion
import dev.goutou.wingman.config.SELF_ROLE_KEY

/** 每个微信 Activity 一个面板；面板自己判断「现在是不是聊天页」。 */
internal object PanelRegistry {
    private val panels = java.util.WeakHashMap<Activity, Panel>()

    fun onResume(activity: Activity) {
        val existing = panels[activity]
        if (existing != null) existing.onResume() else panels[activity] = Panel(activity).also { it.onResume() }
    }

    fun onPause(activity: Activity) {
        panels[activity]?.onPause()
    }
}

/**
 * 聊天页右上角那个折叠按钮 ——「军师」。
 *
 * 默认只有一个小按钮，不挡消息；**点开**才是「风险判断 + 候选回复 + 刷新」那张卡片，
 * 点标题栏的 ▾（或再点一次按钮）就收回去。以前是一张常驻的大卡片铺在消息列表顶上，
 * 聊天时一直挡着最上面那几条 —— 这就是把它改成折叠的原因。
 *
 * 折叠按钮上的字会跟着状态走：没结果时「↻ 识别」、正在调接口「思考中…」、
 * 有结果「风险低 · 3条」（底色也按低绿/中黄/高红变）。
 *
 * 和原版的区别：
 * - 不在 Activity.onResume 时一次性建面板，而是绑定生命周期（onPause 停轮询、摘掉视图，省电、也不会残留在后台任务里）。
 * - 不再用 dp(96) 这种写死的顶部偏移：卡片贴在「消息列表顶部」的真实位置上，各机型/各版本都不用改代码。
 * - 深色模式有对应的配色（原版写死白色卡片，深色主题下非常刺眼）。
 * - 轮询间隔自适应：在聊天页 900ms，不在聊天页 2.6s；并且只有「最后一行变了」才会真正读列表 + 调接口。
 * - 结果按消息指纹缓存，来回切页面不会重复烧 token；两次调用之间有最短间隔限制。
 */
internal class Panel(private val a: Activity) {

    private val reader = ViewReader(a)
    private val parser = ChatParser(a.resources.displayMetrics.widthPixels)
    /** 图片文字识别：把行里的图送去 App 认字（同一张图只发一次，认不出来回落成占位）。 */
    private val ocr = ImageOcr()
    private val handler = Handler(Looper.getMainLooper())
    /**
     * 配置。注入进程里**只读**：App 侧写本地 SharedPreferences，框架把改动实时推到这里。
     * （迁移前是 XSharedPreferences 直接读 App 的 XML —— 那套机制已被标记废弃。）
     */
    private val prefs: SharedPreferences?
        get() = XposedApi.prefs()

    private val density = a.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val night = (a.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    /**
     * 微信里这张卡片的底色 —— 和 App 里是同一套玻璃语义：**半透明 + 一道边**。
     *
     * 浅色场景从「白 0.95」改成**白 0.72**：以前那块近乎不透明的白压在聊天背景上，
     * 跟 App 里被吐槽的「一圈玻璃围着一块白板」是同一个毛病。深色场景本来就够暗，维持原值。
     */
    private val colorCard = if (night) 0xF21B1A22.toInt() else 0xB8FFFFFF.toInt()
    private val colorStroke = if (night) 0x557C3AED.toInt() else 0x447C3AED.toInt()
    private val colorMain = if (night) 0xFFEDEAF5.toInt() else 0xFF1C1B22.toInt()
    private val colorSub = if (night) 0xFF9A96A8.toInt() else 0xFF6E6A7C.toInt()
    private val colorReply = if (night) 0xFF2A2440.toInt() else 0xFFEDE7FA.toInt()
    private val colorAccent = 0xFF7C3AED.toInt()
    private val colorWarn = 0xFFE8A317.toInt()
    /** 「中」当实底用的深一档琥珀（白字压在上面要够对比）。 */
    private val colorWarnSolid = 0xFFC97A00.toInt()
    private val colorBad = 0xFFD64545.toInt()
    private val colorOk = 0xFF2FA566.toInt()
    /**
     * 风险等级 → 语义色（**唯一一份**映射）。
     *
     * 以前 chip 底色和标题文字各写了一个 `when`，四档里有三档其实写成了不同的色号 ——
     * 看着是「同一件事」，改的时候却得改两处，还容易越改越不一致。
     */
    private val riskMap = mapOf("低" to colorOk, "中" to colorWarn, "高" to colorBad)

    private val card = LinearLayout(a)
    private val title = TextView(a)
    private val bodyBox = LinearLayout(a)
    private val chip = TextView(a)

    private var attached = false
    private var running = false
    private var onChat = false
    /**
     * 手上的异步任务 —— 注入侧状态机**唯一的**「在忙」真值。
     *
     * 以前是三个字段各管一摊（busy / rewriteBusy / pendingRequestKey），于是状态会自相矛盾：
     * 改写途中「分析」也以为自己在跑、在飞的请求和当前这一屏对不上号。
     * 收敛成一个 job 之后，「忙不忙、忙的是哪一屏」只有一个来源（[busy] / [rewriteBusy] 都是它的派生值）。
     */
    private var job: Job = Job.None
    /** 正在为某一屏问模型（[job] 的派生值）。 */
    private val busy: Boolean get() = job is Job.Analyze
    /** 正在改写某一条候选（[job] 的派生值）。 */
    private val rewriteBusy: Boolean get() = job is Job.Rewrite
    /** 在飞的那次分析挂在哪一屏（没在分析时是空串）。 */
    private val pendingRequestKey: String get() = (job as? Job.Analyze)?.key.orEmpty()
    /**
     * 用户**明确要求重看这一屏**（点了「重新识别」/ 放行了敏感内容）。
     *
     * 只做一件事：让这一轮跳过「没变就不重读」那道早退，并且「读不到消息列表」也照样报给用户
     * （否则点了按钮什么都不会发生）。它是一次性的：那一轮走完就清掉。
     * （换掉了原来那个 force —— 名字说不清它是干嘛的，而且读它的人得自己猜。）
     */
    private var recheck = false
    private var generation = 0
    private var cardTop = -1
    private var lastCallAt = 0L
    private var lastFingerprint = ""
    /** 这一屏最后解析出来的消息 —— 点按钮要「摆出缓存」时拿它渲染（[onChipClick] 的 ③）。 */
    private var lastMsgs: List<ChatMsg> = emptyList()
    private var skipSensitiveFor = ""
    /**
     * 「这一屏」的稳定身份：会话名 + 消息列表指纹 + 这几条消息的内容。
     *
     * **刻意不含角色档案 / 设置 / 说话风格** —— 那些东西每一轮都在长（[recordToRoles] 一直在往档案里补），
     * 拿它们算出来的键下一轮就变了。而敏感闸门的「用户已经确认过这一屏」必须挂在一个**稳定**键上：
     * 挂错键的后果就是「点了『仍然分析这一条』→ 下一轮又命中 → 弹回风险卡」，点几次都一样。
     */
    private var screenKey = ""
    /** 敏感卡上一次渲染的那一批命中词：同一屏同一批就不再重建（重建会让它每轮闪一次，像卡死）。 */
    private var sensitiveHits: List<String> = emptyList()
    /** 这一屏为什么用不了（[showStuck] 写的）：卡片开着时直接摊给用户看。 */
    private var stuckReason = ""
    /** 上一次 showMessage 的正文：同一句话复现时不再重建、也不再自动展开（防抖）。 */
    private var lastMessage = ""
    /** 上一次 render 的内容签名：同一份结果不重复重建（防抖，见 render）。 */
    private var renderedSig = ""
    /** 标题抖动保护：候选新标题 + 它连续出现了几轮（见 tick 里那段）。 */
    private var pendingName = ""
    private var pendingNameTicks = 0
    /** 抖动保护计数：机器自己在一段时间内改了几次折叠状态（用户点的不算）。 */
    private var autoToggles = 0
    private var autoToggleAt = 0L
    /** 上一轮的设置指纹四个分组：用来在轨迹里指出是哪一组在抖。 */
    private var lastSettingsGroups: List<String> = emptyList()
    /**
     * 折叠 / 展开（默认折叠 —— 不打开的话，脸上就只有一个小按钮）。
     *
     * ⚠️ **只有三处允许改动它**：
     * ① 用户自己点 —— 折叠按钮 [onChipClick] 或卡片右上角那个 ▾；
     * ② 离开现场 —— [hideAll]（不在聊天页 / 总开关关着）与 [onPause]；
     * ③ 换会话（上一屏的内容已经不属于这里）。
     *
     * 为什么要把这条写成规矩：tick 每 900ms 跑一轮，里面有一堆「这一屏没什么可展示的」分支
     * （最后一条是我发的 / 读不到文字 / 白名单外…）。这些分支以前都顺手 `setExpanded(false)`，
     * 于是**用户刚把卡片点开，下一轮就被自己的状态机收走** —— 看起来就是「点开又自动关闭」，
     * 偏偏在「没什么可回」的那几屏最容易复现。现在它们改走 [showIdle]：只换内容与按钮文案，
     * 收不收由用户自己决定。
     */
    private var expanded = false
    /** 卡片里现在是不是一份「能看的结果」（候选回复 / 敏感拦截）。不是的话，点按钮该去重新识别。 */
    /**
     * 卡片正文现在是什么 —— **唯一的「内容类型」真值**。
     *
     * 以前靠一堆布尔互相推断（hasResult / bodyBox.childCount / sensitiveHits），于是出现自相矛盾的界面：
     * 正文显示着上一次的结果、按钮却写着「敏感内容」；用户点 ▾ 收起来、下一轮又被弹开。
     * 规矩：判定一律判**类型**，不判「非空」。
     */
    private var body = Body.None
    /** 手上有没有「能看的内容」（点按钮是打开还是去识别，看它）。 */
    private val hasResult: Boolean
        get() = body == Body.Result || body == Body.Sensitive || body == Body.Message
    /** 折叠按钮上的字，跟着状态走。 */
    private var chipText = ""

    private var lastDiagAt = 0L
    /** 自动回传决策轨迹的水位：上次真正发出去时的内容版本号 + 时间（见 [maybeSendTrace]）。 */
    private var lastTraceVer = -1
    private var lastTraceAt = 0L
    private var lastDiagReq = 0L
    /** 已经回执过的那个探测请求（见 [maybeAnswerProbe]）。 */
    private var lastProbeReq = 0L
    private var emptyNotified = false
    /** 「白名单开着但认不出这个会话的名字」只提示一次，免得每屏都弹。 */
    private var whitelistNameNotified = false
    private var chatName = ""
    private var lastScreenFingerprint = ""
    private var contextRevision = ""
    // （pendingRequestKey 现在是 job 的派生值，见下面的 Job）
    /** 打开会话时顺手认出来的名字（给「白名单」页当候选）；白名单关着时一条都不收。 */
    private val seenChats = LinkedHashSet<String>()
    /** 上一次读会话列表的时间 / 已处理到哪个请求 / 上次回传过的那一批（免得每 2.6 秒重发）。 */
    private var lastConvPullAt = 0L
    private var lastConvReq = 0L
    /** 已经回过「收到了」的那个请求（回执只发一次，别每 900ms 刷一条）。 */
    private var convReqAck = 0L
    private var lastConvNames = ""
    private val sentMsgs = HashSet<String>()
    private var lastAttachTry = 0L
    private var noListTicks = 0
    private var config: ConfigData? = null
    private var inputRef: EditText? = null
    private var listRef: ViewGroup? = null
    private val cache = LinkedHashMap<String, Suggestion>()

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                tick()
            } catch (t: Throwable) {
                XposedApi.log("tick: $t")
                Trace.note("崩", "这一轮抛异常了：${t.javaClass.simpleName} ${t.message.orEmpty().take(80)}")
            } finally {
                // 放在 finally：tick 里那些 return 全都要走到这儿，
                // 否则「刚好卡住的那一轮」恰好不回传，最该看的时候没得看。
                maybeSendTrace()
            }
            handler.postDelayed(this, if (onChat) 900L else 2600L)
        }
    }

    /**
     * 自动回传决策轨迹。
     *
     * 为什么自动发：轨迹的价值全在「刚复现完那一刻」—— 用户不会记得切回 App 点按钮。
     * 为什么不能每轮发：tick 900ms 一轮，如实回传会把广播通道淹掉，也一直把 App 进程唤醒。
     * 所以拿 [Trace.version]（只有**新增**条目才会变）当水位：有新内容、且距上次 ≥20 秒才发一次。
     * 连续重复的记录只合并计数、不算新内容，所以正常聊天时它几乎不发。
     */
    private fun maybeSendTrace() {
        val now = System.currentTimeMillis()
        val ver = Trace.version()
        // 没记新东西：跳过（合并计数不算，否则那行一直在长的「跳过」会每 20 秒顶一次水位）
        if (ver == lastTraceVer) return
        // 有变化但还不到间隔：水位不推，等下一次 tick 到点了自然会发
        if (now - lastTraceAt < Trace.SEND_MIN_MS) return
        lastTraceVer = ver
        lastTraceAt = now
        runCatching { Heartbeat.send(a, 0, trace = Trace.dump(now)) }
    }

    fun onResume() {
        try {
            if (!attached) attach()
        } catch (t: Throwable) {
            XposedApi.log("attach failed: $t")
            return
        }
        running = true
        lastScreenFingerprint = ""
        inputRef = null
        listRef = null
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 400L)
    }

    fun onPause() {
        running = false
        if (expanded) Trace.note("卡片", "收起（微信这一页暂停 / 切走）")
        handler.removeCallbacks(ticker)
        resetPanel()
    }

    // ---------------- 视图 ----------------

    private fun attach() {
        val decor = a.window?.decorView as? ViewGroup ?: return

        card.orientation = LinearLayout.VERTICAL
        card.background = roundRect(colorCard, 20, colorStroke)
        card.setPadding(dp(14), dp(10), dp(14), dp(10))
        card.elevation = dp(8).toFloat()
        card.visibility = View.GONE

        val head = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        title.textSize = 13f
        title.setTextColor(colorAccent)
        // 长按标题：把当前 View 树结构写成诊断（App 首页可复制，日志里也有一份）
        title.setOnLongClickListener {
            a.window?.decorView?.let { decor -> dumpDiagnosis(decor, listRef, inputRef, "手动诊断", manual = true) }
            toast("诊断已写入：App 首页「诊断」卡片可复制，或看 LSPosed 日志 [Goutou]")
            true
        }
        val refresh = label("↻ 重新识别", 12f, 0xFFFFFFFF.toInt()) {
            background = roundRect(colorAccent, 14)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        refresh.setOnClickListener { regenerate() }
        // 收起：只是折回小按钮，不拉黑这一条 —— 再点按钮随时能打开
        val collapse = label("▾", 16f, colorSub) { setPadding(dp(12), 0, 0, 0) }
        collapse.setOnClickListener { setExpanded(false, "用户点卡片右上角 ▾", byUser = true) }
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(refresh)
        head.addView(collapse)

        bodyBox.orientation = LinearLayout.VERTICAL
        card.addView(head)
        card.addView(bodyBox)

        chipText = "↻ 识别"
        chip.text = chipText
        chip.textSize = 13f
        chip.setTextColor(0xFFFFFFFF.toInt())
        chip.setPadding(dp(14), dp(8), dp(14), dp(8))
        chip.background = roundRect(0xE67C3AED.toInt(), 18)
        chip.elevation = dp(6).toFloat()
        chip.visibility = View.GONE
        chip.setOnClickListener { onChipClick() }

        // 打标记：ViewReader 遍历时会整棵跳过我们自己的浮层（见 OVERLAY_TAG 的说明）
        card.tag = OVERLAY_TAG
        chip.tag = OVERLAY_TAG
        decor.addView(card, matchTop(dp(10), dp(96), dp(10)))
        decor.addView(chip, wrapTopEnd(dp(10), dp(96)))
        // 探测轮询从这里起步（**刻意不放在 onResume**）：微信切到后台时 ticker 是停的，
        // 但微信进程还活着 —— 探测挂在 ticker 上就会得到「没回应」的假警报。
        handler.removeCallbacks(prober)
        handler.postDelayed(prober, PROBE_POLL_MS)
        attached = true
    }

    private fun label(text: String, size: Float, color: Int, style: TextView.() -> Unit = {}): TextView =
        TextView(a).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            style()
        }

    private fun roundRect(color: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun matchTop(left: Int, top: Int, right: Int) =
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
            .apply { setMargins(left, top, right, 0) }

    private fun wrapTopEnd(right: Int, top: Int) =
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
            .apply { setMargins(0, top, right, 0) }

    /** 卡片贴在消息列表顶部 —— 不再依赖 dp(96) 这种写死的偏移。 */
    private fun place(list: ViewGroup?) {
        val decor = a.window?.decorView ?: return
        val inset = statusBarHeight()
        val target = if (list != null && list.isShown && list.height > 0) {
            val listLoc = IntArray(2)
            list.getLocationOnScreen(listLoc)
            val decorLoc = IntArray(2)
            decor.getLocationOnScreen(decorLoc)
            maxOf(inset + dp(40), listLoc[1] - decorLoc[1] + dp(4))
        } else {
            inset + dp(80)
        }
        if (target == cardTop) return
        cardTop = target
        for (v in arrayOf<View>(card, chip)) {
            val lp = v.layoutParams as? FrameLayout.LayoutParams ?: continue
            lp.topMargin = target
            v.layoutParams = lp
        }
    }

    @Suppress("DEPRECATION")
    private fun statusBarHeight(): Int {
        val insets = a.window?.decorView?.rootWindowInsets
        if (insets != null) return insets.systemWindowInsets.top
        val id = a.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) a.resources.getDimensionPixelSize(id) else dp(24)
    }

    // ---------------- 主循环 ----------------

    private fun tick() {
        val decor = a.window?.decorView ?: run {
            Trace.note("窗口", "拿不到 decorView（页面还没建好，或正在销毁）")
            return
        }
        (decor as? ViewGroup)?.let { ensureAttached(it) }

        // 配置文件变了就地重读。提到最前面：下面几个早退分支都依赖它，尤其是「手动抓取」。
        refreshPrefs()

        // App 里点了「抓当前微信界面」→ 把当前页面结构 dump 回去。
        // 刻意排在**所有**判定之前（包括 hasWindowFocus）：
        // 出问题的那几个聊天页可能是在下面任意一个分支提前 return 的，而诊断入口原本是长按卡片标题 ——
        // 卡片都不弹，那个入口根本够不着；连「窗口没焦点」这种原因也要能抓到证据。
        val req = prefs?.getLong(Keys.DIAG_REQ, 0L) ?: 0L
        if (req > lastDiagReq) {
            lastDiagReq = req
            Trace.note("抓取", "App 点了「抓当前微信界面」→ 手动 dump 结构（连带这份轨迹一起回传）")
            val in0 = reader.findChatInput(decor)
            val ls0 = in0?.let { reader.findList(decor, it) }
            dumpDiagnosis(
                decor, ls0, in0,
                "手动抓取（App 触发）｜hasWindowFocus=${decor.hasWindowFocus()}｜输入框=${if (in0 != null) "有" else "无"}",
                manual = true,
                extra = stateReport(ls0),
            )
            return
        }

        // App 里点了「拉取会话列表」→ 处理（回执 + 读名字）。
        // 位置和上面「手动抓取」一个道理：**排在 hasWindowFocus 之前**。读一屏列表不需要窗口焦点，
        // 而后面的早退分支一个比一个苛刻 —— 挡在哪儿都会让请求石沉大海，用户那边只看到「没反应」。
        maybePullConversations(decor)

        // App 的实时探测：位置同样在所有早退之前 —— 「这一屏读不出来」时最需要知道模块还活着。
        maybeAnswerProbe()

        if (!decor.hasWindowFocus()) {
            // 没焦点通常什么都不做 —— 但「有些对话连气泡都不出现」就是从这类早退里溜走的。
            // 只要这一屏**像**聊天页就留一个能点的按钮（宁松勿紧，见 [chatPageEvidence]）。
            val why = chatPageEvidence(reader.findInputCandidate(decor), decor, cheap = true)
            if (why != null) {
                Trace.note("焦点", "窗口没焦点，但这一屏像聊天页（$why）→ 留一个按钮说明情况")
                showStuck(
                    "军师 · 窗口没焦点",
                    "现在读不了这一屏：微信不在前台，或者被别的窗口盖着。\n" +
                        "回到微信这一页停一下就恢复。\n（判断依据：$why）",
                )
            } else {
                Trace.note("焦点", "窗口没焦点（微信不在前台，或被别的窗口盖着）")
            }
            return
        }

        val cachedInput = inputRef
        val input = if (cachedInput != null && cachedInput.isShown) {
            cachedInput
        } else {
            reader.findChatInput(decor)?.also { inputRef = it }
        }
        if (input == null) {
            // 以前这里直接 hideAll() 就结束，于是「这几个聊天页为什么连卡片都不弹」永远查不出来。
            // 现在只要屏幕上还有「像输入框」的控件，就把结构 dump 回去（30s 限流）。
            val cand0 = reader.findInputCandidate(decor)
            Trace.note(
                "输入",
                if (cand0 == null) {
                    "整屏没有像输入框的控件（这一页多半不是聊天页）"
                } else {
                    "有候选但判定不可用：${cand0.javaClass.name} ${cand0.width}x${cand0.height} isShown=${cand0.isShown}"
                },
            )
            cand0?.let { cand ->
                dumpDiagnosis(
                    decor, null, cand,
                    "像聊天页但找不到可用输入框：候选=${cand.javaClass.name} " +
                        "${cand.width}x${cand.height} isShown=${cand.isShown}" +
                        "（判定要求 宽>${dp(50)}、位于屏幕下 70%、isShown）",
                )
            }
            // 这是「有些对话连气泡都不出现」的老根因：以前不管这一屏是不是聊天页，一律 hideAll()，
            // 屏幕上就什么都不剩了 —— 用户既看不到状态，也没法自查。
            val why = chatPageEvidence(cand0, decor, cheap = false)
            if (why != null) {
                // 走到这儿的情况：① 微信改了控件；② **按下「按住说话」时输入框被设成 GONE**（连候选都没有）；
                // ③ 这一屏的视图树太大、走到输入框之前就撞上了遍历预算。
                Trace.note("兜底", "读不到输入框，但这一屏像聊天页（$why）→ 留按钮说明")
                showStuck(
                    "军师 · 读不到输入框",
                    "这一屏是聊天页，但读不到能用的输入框。\n" +
                        "常见原因：正在「按住说话」（微信把输入框藏起来了）、用的是分屏 / 小窗，\n" +
                        "或者这一屏的控件太多、没扫到输入框。\n" +
                        "（判断依据：$why）长按标题可以再生成一份诊断。",
                )
            } else {
                Trace.note("兜底", "既没有输入框也没有消息列表，Activity=${a.javaClass.simpleName} → 整块藏起来")
                hideAll("不是聊天页（没有输入框、也没有消息列表）")
            }
            return
        }
        onChat = true
        stuckReason = ""
        if (!reloadConfig()) {
            Trace.note("配置", "读不到配置（prefs 为空）：模块没启用，或没勾选微信")
            return
        }
        val cfg = config ?: return
        if (!cfg.enabled) {
            Trace.note("开关", "总开关关着（设置 → 微信内自动分析）→ 这一页什么都不做")
            hideAll("总开关关着")
            return
        }
        // 从别的页面切回来时，指纹没变会提前 return —— 这里先把按钮的可见性对一遍，
        // 免得「出去转一圈回来，按钮再也不出现了」。
        applyVisibility()

        val cachedList = listRef
        val list = if (cachedList != null && cachedList.isShown) {
            cachedList
        } else {
            reader.findList(decor, input)?.also { listRef = it }
        }
        place(list)
        if (list == null) {
            // 布局刚切换时会短暂读不到，连续两次才算真的找不到
            noListTicks++
            Trace.note(
                "列表",
                if (noListTicks < 2) {
                    "第 $noListTicks 轮没读到消息列表（刚切换布局，先等等）"
                } else {
                    "连续 $noListTicks 轮找不到消息列表（微信可能换了控件类型）"
                },
            )
            if (noListTicks >= 2 || recheck) {
                dumpDiagnosis(decor, null, input, "找到了输入框，但没找到消息列表")
                if (recheck || noListTicks == 2) {
                    recheck = false
                    showMessage(
                        "没找到消息列表（微信版本可能改了控件类型）。\n" +
                            "诊断已保存到 App 首页，长按标题可再次生成。",
                        isError = true,
                    )
                }
            }
            return
        }
        noListTicks = 0

        // A reused Activity can host different conversations with identical last messages.
        // Resolve the title before every content fast-path; a missing title never inherits the old name.
        val observedRawName = Roles.normalizeKey(reader.findChatTitle(decor, list).orEmpty())
        // 微信切换布局时标题控件可能短暂读空。已有会话遇到这个瞬态时，
        // 保留当前上下文并等待下一轮，不能把它当成新会话清掉展开结果。
        if (observedRawName.isBlank() && chatName.isNotBlank()) {
            Trace.note("标题", "会话标题暂时为空，保留「$chatName」并等待下一轮")
            return
        }
        // 标题抖动保护：微信的标题区会短暂显示「对方正在输入…」，会话被复用时也可能先读到上一个名字。
        // 只有同一个新名字**连着读到两轮**才算真换了人；否则这一轮仍按当前名字处理（不改上下文、不重置）。
        // 用户报的「折叠、展开、折叠、展开往复循环」最可能的一种来源就是这里：标题在「真名」和
        // 「正在输入…」之间来回跳，每跳一次都被当成换了会话 → 收起卡片 → 下一轮又变回来。
        var observedName = observedRawName
        if (observedName != chatName) {
            if (observedName == pendingName) {
                pendingNameTicks++
            } else {
                pendingName = observedName
                pendingNameTicks = 1
            }
            if (pendingNameTicks < 2 && chatName.isNotBlank()) {
                Trace.note("标题", "读到新标题「$observedName」但只出现一轮 → 当作抖动，这一轮仍按「$chatName」处理")
                observedName = chatName
            }
        } else {
            pendingName = ""
            pendingNameTicks = 0
        }
        // 设置指纹拆成四组（跟高级设置的分组分法一致）：万一它还在抖，轨迹里能直接说是哪一组，
        // 不用再猜「设置 / 档案 / 说话风格之一」到底是哪个（这一条就是靠用户那份轨迹才定位的）。
        val settingsGroups = listOf(
            conversationDigest(cfg.prompt, cfg.model, cfg.baseUrl, cfg.apiKey, cfg.model2, cfg.baseUrl2, cfg.apiKey2),
            conversationDigest(
                cfg.graded.toString(), cfg.temperature.toString(), cfg.maxTokens.toString(),
                cfg.jsonMode.toString(), cfg.ctx.toString(), cfg.allowSensitive.toString(),
            ),
            conversationDigest(cfg.whitelistEnabled.toString(), cfg.whitelist.sorted().toString()),
            conversationDigest(cfg.proxyEnabled.toString(), cfg.proxyPort.toString(), cfg.proxyToken),
        )
        val settings = conversationDigest(*settingsGroups.toTypedArray())
        // 「要不要重置这一屏」**只看两件事**：这是不是同一个会话（名字）+ 设置有没有改。
        //
        // ⚠️ 以前还把「角色档案 / 说话风格」算进来 —— 那两样是**内容**（ask 时才用），而且会一直变：
        // 用户实测那份轨迹里，它们每 1~2 秒就让 revision 变一次 → 每轮都重置上下文 → 立刻重新问模型
        // → 卡片正文在「正在读这一屏…」和结果之间来回跳（用户报的「折叠、展开往复循环」就是它，
        // 第 7 版那句「还是自己关」也是同一个原因）。
        // 档案 / 风格确实是内容，但它们**不该影响「这一屏是谁」** —— 内容变化由缓存键（resultKey）管，
        // 而且刻意不让它使缓存失效（档案长一条不该重新烧一次 token）。
        val revision = conversationDigest(observedName, settings)
        if (revision != contextRevision) {
            val anotherChat = observedName != chatName
            val prevGroups = lastSettingsGroups
            lastSettingsGroups = settingsGroups
            generation++ // Discard replies and rewrites started for the previous conversation or profile.
            job = Job.None
            contextRevision = revision
            chatName = observedName
            lastScreenFingerprint = ""
            lastFingerprint = ""
            // ⚠️ 节流与「敏感放行」**只在真的换了人**时清零。同名而只是设置改了的话，
            // 清零 lastCallAt 等于把最短间隔这道闸也拆了 → 每轮都能重新问模型（用户那份轨迹里
            // 「调用 发起分析」每 1~2 秒一次就是这么来的，白烧 token）。
            if (anotherChat) {
                lastCallAt = 0L
                skipSensitiveFor = ""
            }
            body = Body.None
            // 换了会话（或提示词 / 档案变了）：上一屏的内容已经不属于这里 ——
            // 这是少数几处「替用户收」的地方之一，不收就会拿着别人的结果改这条消息。
            // 轨迹里必须分清是哪一种：同名但指纹变了 = 设置 / 角色档案 / 说话风格在抖（那是真 bug）。
            Trace.note(
                "换会话",
                if (anotherChat) {
                    "会话名变了（$chatName → $observedName）→ 重置这一屏"
                } else {
                    val names = listOf("接入（接口/Key/模型）", "生成（模式/温度/条数）", "白名单", "代理")
                    val which = names.indices
                        .filter { i -> prevGroups.getOrNull(i)?.let { it != settingsGroups[i] } == true }
                        .map { names[it] }
                    "同名，但设置变了（${if (which.isEmpty()) "未识别" else which.joinToString("、")}）→ 重置内容，不动折叠"
                },
            )
            // **只有真的换了人才替用户收起卡片**。同名而指纹抖一下（改了设置、档案长了一条、说话风格更新）
            // 不该把用户正开着的那张卡拍下去 —— 这正是「点开又自己关」最隐蔽的一种来源。
            if (anotherChat) setExpanded(false, "换了会话")
            showIdle()
        }
        // 指纹只由「看得见的内容 + 这一屏的身份」决定。
        // 以前还塞了 historyRevision（整份角色档案的 digest）：那是**每 tick 对最多 400 条记录算一次哈希**，
        // 而且档案每长一句话就被当成「这一屏变了」→ 缓存必 miss、在飞的请求被作废。
        // 「档案变了要不要重新问」是 ask() 那一刻用 roleContext 决定的事，不该让「这一屏变没变」跟着抖。
        val fingerprint = conversationDigest(observedName, reader.fingerprint(list), revision)
        if (!recheck && !ocr.takeDirty() && fingerprint == lastScreenFingerprint) {
            Trace.note("跳过", "会话、资料和这一屏未变，不重复读")
            return
        }
        recheck = false
        lastScreenFingerprint = fingerprint

        // 白名单：没勾的会话「彻底关闭」—— **连图都不认**。
        //
        // 位置从 recordToRoles 之后提到了**识图之前**：以前识图排在闸门前面，理由是
        // 「会话名得等解析结果」—— 其实会话名读的是聊天页**标题**（上面 revision 那一段就取到了），
        // 跟解析没关系，所以这道闸完全可以提前，白名单外的会话连图都不会被送去认字。
        // 认出来的名字照旧回传当候选（否则「白名单开着时打开过就会出现在候选里」会失效）。
        if (blockedByWhitelist(cfg)) {
            rememberChatName(chatName)
            Trace.note(
                "白名单",
                if (chatName.isBlank()) {
                    "白名单开着，但这个会话的名字没认出来 → 拦下（宁可不读，也不猜；连图都不认）"
                } else {
                    "会话「$chatName」不在白名单里 → 不分析，也不认图"
                },
            )
            setChip(if (chatName.isBlank()) "白名单 · 认不出会话名" else "白名单外 · 未启用")
            // 认不出名字这种情况最容易让人以为「白名单坏了」—— 第一次把话说清楚，
            // 之后只留按钮上的字（诊断在 recordToRoles 里已经写过，含标题候选）。
            if (chatName.isBlank() && !whitelistNameNotified) {
                whitelistNameNotified = true
                showMessage(
                    "白名单开着，但这个会话的名字没认出来（微信可能改了标题控件），所以先不分析。\n" +
                        "要在这里用：到「设置 → 高级设置 → 会话白名单」里按名字手动加一个。\n" +
                        "诊断已写入 App 首页「诊断」卡片。",
                    isError = true,
                )
            } else {
                // 按钮文案跟着 showIdle 一起写：以前 setChip 写完之后立刻被 showIdle 覆盖成「↻ 识别」，
                // 于是「白名单外」这几个字其实从没在按钮上停住过。
                showIdle(
                    if (chatName.isBlank()) {
                        "白名单开着，但这个会话的名字没认出来 → 先不分析。\n" +
                            "要在这里用：去「设置 → 高级设置 → 会话白名单」按名字手动加一个。"
                    } else {
                        "「$chatName」不在白名单里 —— 按你的设置，这一屏不分析。\n" +
                            "想让它工作就把它加进白名单；想全都分析，把白名单开关关掉。"
                    },
                    chip = if (chatName.isBlank()) "白名单 · 认不出会话名" else "白名单外 · 未启用",
                )
            }
            return
        }

        val rows = reader.snapshot(list)
        // 图片先排进后台去认（这一步不阻塞）。还有图没认完就这一轮先不分析 ——
        // 免得先拿 [图片] 占位问一遍、认完再问一遍（既费 token，两次结果还不一样）。
        //
        // 和白名单的关系：这一步在白名单那道闸**之前**，和「读消息」是同一边的东西 ——
        // 图片只在本机两个进程之间走一趟（不外发、不落盘），而真正会把内容送出去的**分析**
        // 仍然被下面那道闸拦着。要更严（白名单外的会话连图都不认）也行，但那需要把会话名
        // 提前读出来，而会话名本来就依赖解析结果 —— 环形依赖，先记在这里。
        if (ocr.prepare(rows, cfg)) {
            Trace.note("识图", "还有图片在后台认字，这一轮先不分析（免得拿占位先问一遍）")
            setChip("正在认图…")
            return
        }
        // 到这儿每张图都已经有确定结果了（认到的文字 / 确实没字 / 失败冷却），解析只是个纯查询
        val msgs = parser.parse(rows) { img -> ocr.textOf(img) }.takeLast(cfg.ctx)
        // 「这一屏」的稳定键：只跟**看得见的内容**有关（纯函数，单测见 ConversationStateTest）。
        screenKey = screenIdentity(observedName, reader.fingerprint(list), msgs)
        // 只记**条数和方向**，不记正文：轨迹会顺着心跳回传、常驻 App 本地，
        // 不该比诊断包更容易泄露聊天内容。一条都没解析出来时这一条不记 —— 紧接着的「空」会说清楚。
        if (msgs.isNotEmpty()) {
            Trace.note(
                "读到",
                "解析出 ${msgs.size} 条，最后一条" + if (msgs.last().fromMe) "是我发的" else "是对方发的",
            )
        }
        // 识图状态变了就回传一句给设置页（没变不发，别把广播通道淹了）
        ocr.takeLine()?.let { Heartbeat.send(a, 0, ocr = it) }

        // 自愈必须排在「一条都没读到」的判断**之前**。
        // 正文控件是「看到才挂钩子」的，而文字早在挂钩之前就设好了 —— 只有让微信重绑一次，
        // 才能把原文喂进钩子。原来这段写在下面 msgs.isEmpty() 的 return 之后，
        // 等于最需要它的时候恰好不执行：读不到 → 立刻 return → 永远读不到。
        val fresh = TextCapture.takeLearned()
        if (fresh.isNotEmpty()) {
            Trace.note("自愈", "学到 ${fresh.size} 个正文控件类 → 请求列表重绑一次，好让 setText 钩子抓到原文")
            fresh.forEach { Heartbeat.send(a, 0, learned = it) }
            nudgeRebind(list)
        }

        if (msgs.isEmpty()) {
            // 原来这里只是默默把卡片收掉：用户什么都看不到、也没有任何提示。
            // 现在第一次把话说清楚，并留下诊断。
            if (!emptyNotified) {
                emptyNotified = true
                Trace.note("空", "选到了消息列表，但一条文字都没解析出来（正文可能是自绘控件）")
                dumpDiagnosis(decor, list, input, "选到了消息列表，但一行文字都没解析出来（指纹=${fingerprint.take(60)}）")
                showMessage(
                    "这个聊天读不到文字（正文可能是自绘控件）。\n" +
                        "已请求微信重绑一次，等一两秒看看；还不行就把 App 首页的「诊断」发我。",
                    isError = true,
                )
            } else {
                showIdle(
                    "这个聊天还没读到文字（正文可能是自绘控件）。\n" +
                        "已经让微信重绑一次，等一两秒；还不行就把 App 首页的「诊断」发我。",
                )
            }
            return
        }
        emptyNotified = false

        // 记进「角色」页（认不出会话名就整页跳过，宁可漏记也不记错人）
        recordToRoles(decor, list, msgs)

        // 安全网：一条文字都没读到，说明「读的东西」本身就不对。
        // 这时候去调模型只会浪费 token 并给出荒谬建议，所以先停下、留诊断、明确告诉用户。
        if (msgs.size >= 2 && msgs.all { it.attachment }) {
            Trace.note("全图", "读到 ${msgs.size} 条全是图片/表情占位，一条文字都没有 → 多半选错列表了")
            dumpDiagnosis(decor, list, input, "读到 ${msgs.size} 条消息，但全部是图片/表情占位，一条文字都没有")
            showMessage(
                "读到的全是图片占位、一条文字都没有 —— 多半是消息列表选错了。\n" +
                    "诊断已写入 App 首页「诊断」卡片，长按标题也能重新生成。",
                isError = true,
            )
            return
        }
        // 最后一条是我发的：没什么可回的。卡片开着就只把说明换掉，**不替用户收**（见 [expanded] 的规矩）
        if (msgs.last().fromMe) {
            Trace.note("方向", "最后一条是我发的 → 没什么可回，只更新按钮与卡片说明")
            showIdle("最后一条是你发的 —— 这条没什么可回，收到新消息时这里会自动更新。")
            return
        }
        val roleContext = roleContextFor(chatName, msgs)
        // 这把键必须**稳定**：cache / pendingRequestKey / 敏感放行 全挂在它上面。
        // 以前混进了 roleContext（角色档案每轮都在长）→ 缓存必 miss（白烧 token）、在飞的请求被
        // generation++ 作废、刚点过的「敏感放行」下一轮就失效（点了没完没了地弹回风险卡）。
        val requestKey = resultKey(screenKey, settings)
        lastFingerprint = requestKey
        // 留着这一屏的消息：点按钮要「摆出缓存」时用它渲染历史行（[onChipClick] 的 ③）
        lastMsgs = msgs
        if (rewriteBusy) return
        if (busy && pendingRequestKey == requestKey) return
        if (busy) {
            // 这一屏换了（同一会话里内容变了）：作废在飞的那次，接着走缓存 / 重新分析
            generation++
            job = Job.None
        }
        cache[requestKey]?.let {
            Trace.note("缓存", "这一屏之前问过，直接用缓存（不烧 token）")
            render(it, msgs, fromCache = true)
            return
        }

        val joined = msgs.joinToString("\n") { it.text }
        // 用户点过「仍然分析这一条」→ 这一屏不再拦。
        // ⚠️ 判定必须用 [screenKey]（屏稳定），**不能用 requestKey**：requestKey 里含角色档案，
        // 而档案每轮都在变 → 上一轮记下的键永远对不上 → 每轮重新命中，点多少次都弹回风险卡。
        val hits = if (cfg.allowSensitive || screenKey == skipSensitiveFor) emptyList() else Sensitive.hits(joined)
        if (hits.isNotEmpty()) {
            Trace.note("敏感", "命中敏感词：${hits.joinToString("、")} → 只提示，不外发")
            renderSensitive(hits)
            return
        }

        if (System.currentTimeMillis() - lastCallAt < cfg.minIntervalSec * 1000L) {
            lastScreenFingerprint = "" // A throttled screen has not been analyzed yet.
            Trace.note("间隔", "距上次调用不到 ${cfg.minIntervalSec}s（设置里可调小）→ 只在按钮上留倒计时")
            // 不弹卡片打断聊天，只在按钮上留个倒计时（「设置」里可以把这个间隔调小）
            val left = (cfg.minIntervalSec * 1000L - (System.currentTimeMillis() - lastCallAt) + 999L) / 1000L
            setChip("$left s 后可再识别")
            return
        }
        ask(cfg, msgs, requestKey, roleContext)
    }

    /**
     * 把这一轮读到的消息记进「角色」页。
     *
     * 会话名（= 角色名）从聊天页顶部标题认，认不出来就整页跳过 —— 宁可不记，也不能记到别人头上。
     * 本页内先用「方向+文本」去一次重（模块 900ms 就会重读同一屏），App 侧还有
     * 「1 小时内重复只留一条」的兜底。
     */
    /**
     * 白名单判定（判定本体在 [chatBlocked]，注入侧与单测共用同一份）。
     *
     * 会话名认不出来时是**拦下**而不是放行 —— 白名单是隐私开关，宁可这一屏什么都不做，
     * 也不能因为「标题没认出来」就把内容读出来发出去。为了不变成「完全没反应」，
     * 上面那道闸会把原因写在按钮和提示里。
     */
    private fun blockedByWhitelist(c: ConfigData): Boolean =
        chatBlocked(c.whitelistEnabled, c.whitelist, chatName)

    /**
     * 顺手把一个「认出来的会话名」记回去，给「白名单」页当候选。
     *
     * 只在白名单开着时收：这份数据本来就是给白名单用的，不打算用它的人，
     * 模块不该去攒他的联系人名单（`publish/PRIVACY.md` 里就是这么承诺的）。
     * 也就是说 —— 白名单开着却一条都没加时，你打开过的那些会话会自己出现在候选里，
     * 不用去微信首页翻。
     */
    private fun rememberChatName(name: String) {
        if (name.isBlank() || !seenChats.add(name)) return
        val c = config ?: return
        if (!c.whitelistEnabled) return
        Heartbeat.send(a, 0, chats = name)
    }

    /**
     * 会话名：读「当前这一屏看得见的」回传给 App（白名单页的候选）。
     *
     * 两种触发：白名单开着（要用它，随 tick 限流地做），或 App 里刚点过「拉取会话列表」。
     *
     * **每一步都要回话**：收到请求先回执、读之前先说在读哪一屏、读不出说为什么、抛异常也回一句。
     * 因为「请求没送到」和「送到了但没读出名字」的排查方向完全相反，不给回执就只能瞎猜。
     */
    /**
     * 「App 问一句，回一句」—— 实时探测。
     *
     * 用户实测点过的问题：App 里显示的「模块状态」其实是「**曾经**在微信里跑过没有」。
     * 真正该回答的是「**现在**还在不在」，所以改成双向探测：App 写请求 → 这里回执 → 界面显示
     * 「x 秒前回过话 / 没回应」。
     *
     * 和 ticker 分开跑：[ticker] 在 onPause 就停了，而微信进程还活着。探测也挂在 ticker 上的话，
     * 用户在 App 里点探测会拿到假警报。这条低频轮询只做一次 SharedPreferences 读，很轻。
     */
    private val prober = object : Runnable {
        override fun run() {
            // 泄漏防线：这个匿名对象持有 Panel，Panel 持有 Activity。页面销毁后还一直自我重投的话，
            // 主线程消息队列会永远替它排队 —— 微信打开过的 Activity 一个都回收不了。
            if (a.isFinishing || a.isDestroyed) {
                handler.removeCallbacks(this)
                return
            }
            runCatching { maybeAnswerProbe() }
            handler.postDelayed(this, PROBE_POLL_MS)
        }
    }

    /** 看到新的探测请求就回执（带上「当时在哪一屏」+ 微信版本，App 那边直接显示）。 */
    private fun maybeAnswerProbe() {
        refreshPrefs()
        val req = prefs?.getLong(Keys.PROBE_REQ, 0L) ?: 0L
        if (req <= lastProbeReq) return
        lastProbeReq = req
        val ver = runCatching {
            a.packageManager.getPackageInfo("com.tencent.mm", 0).versionName
        }.getOrNull().orEmpty()
        Heartbeat.send(
            a, 0,
            // 带上请求号（= 那次请求的时间戳）：App 就能分辨「这一轮探测被回答了」还是「答的是更早那一轮」
            probe = "#$req · 页面=" + a.javaClass.simpleName + (if (ver.isBlank()) "" else " · 微信 $ver"),
        )
    }

    private fun maybePullConversations(decor: View) {
        val c = config
        val req = prefs?.getLong(Keys.CHAT_REQ, 0L) ?: 0L
        val fresh = req > lastConvReq
        if (!fresh && c?.whitelistEnabled != true) return
        val now = System.currentTimeMillis()
        // 这一屏每 2.6 秒 tick 一次，没必要次次把整棵视图树读一遍
        if (!fresh && now - lastConvPullAt < CONV_PULL_MIN_MS) return
        // 判「是不是聊天页」要读一遍视图树，所以放在两个早退**之后**：聊天页读到的是消息正文，不是会话名
        val onChatPage = reader.findChatInput(decor) != null
        if (fresh && req > convReqAck) {
            convReqAck = req
            // 把「当时在哪一屏」也带上（LauncherUI / ChattingUI…）：微信一改版本，
            // 光靠猜永远猜不到，这一句顶一次来回。
            val where = "页面=${a.javaClass.simpleName}"
            Heartbeat.send(
                a, 0,
                chatsInfo = where + "｜" + if (onChatPage) {
                    "你在聊天页 —— 切到「微信」或「通讯录」的列表再停两秒，我会自动读"
                } else {
                    "正在读这一屏…"
                },
            )
        }
        // 聊天页读到的是消息正文，不是会话名。这一屏跳过，**请求留着**（不消费），
        // 等切到列表页再读 —— 消费掉的话，用户切过去就什么都不会发生了。
        if (onChatPage) {
            // 请求**不消费**（切到列表页还能用），但别每 0.9 秒重走一遍视图树、再刷一条同样的轨迹 ——
            // 用户发来的那份 80 条轨迹里，一半被这一对「跳过」占满了。
            if (fresh && now - lastConvPullAt > CONV_PULL_MIN_MS) {
                Trace.note("会话", "App 要拉会话列表，但当前在聊天页（读到的是消息正文）→ 跳过，等切到列表页")
                lastConvPullAt = now
            }
            return
        }
        lastConvPullAt = now
        if (fresh) lastConvReq = req
        try {
            val (names, info) = reader.conversationNames(decor)
            val tagged = "页面=${a.javaClass.simpleName}｜$info"
            val joined = names.joinToString("\n")
            // 只在「App 明确要」或「名字真的变了」时留痕：白名单开着时会 15 秒自动扫一次，
            // 每次都记的话，轨迹会被这一行刷满、把真正的判定挤出去。
            if (fresh || (names.isNotEmpty() && joined != lastConvNames)) {
                Trace.note("会话", "读到 ${names.size} 个会话名｜$tagged")
            }
            if (fresh) {
                Heartbeat.send(a, 0, chats = joined, chatsInfo = tagged)
                return
            }
            // 名字没变就不重复发（用户滚动列表时才会变）
            if (names.isNotEmpty() && joined != lastConvNames) {
                lastConvNames = joined
                Heartbeat.send(a, 0, chats = joined, chatsInfo = tagged)
            }
        } catch (t: Throwable) {
            XposedApi.log("读会话列表失败: $t")
            // 出错也要回话，否则 App 那边看起来就是「请求石沉大海」
            if (fresh) {
                Heartbeat.send(a, 0, chats = "", chatsInfo = "读这一屏出错了（页面=${a.javaClass.simpleName}）：$t")
            }
        }
    }

    private fun recordToRoles(decor: View, list: ViewGroup, msgs: List<ChatMsg>) {
        try {
            if (chatName.isBlank()) {
                dumpDiagnosis(decor, list, null, "认不出会话名，这一页不记录", extra = reader.describeTitleCandidates(decor, list))
            } else {
                rememberChatName(chatName)
            }
            val c = config
            if (c != null && blockedByWhitelist(c)) return
            val now = System.currentTimeMillis()
            if (sentMsgs.size > 400) sentMsgs.clear()
            val fresh = ArrayList<Pair<String, RoleMsg>>()
            // 隐私口径：**命中敏感词的消息不写进角色档案**。
            //
            // 以前敏感闸门排在记录**之后**，于是被判敏感的正文照样进了档案，下一轮又作为
            // 「角色背景」进提示词发出去 ——「只提示、不外发」当时只对当次那一次调用成立。
            // 现在在**归档这一步**就滤掉：既不留档，也就永远进不了提示词。
            // 用户自己关掉了敏感检查（allowSensitive）时按他的选择走，照常记录。
            // ⚠️ 放行过一次（点「仍然分析这一条」）也**不**补记：那只是同意「这一次发」，
            // 不等于同意「以后每次都作为背景发」。
            val sensitiveGate = c != null && !c.allowSensitive
            // 一次算好：哪些能进档案、被拦下几条（纯函数，单测见 SensitiveArchiveTest）
            val (archived, sensitiveSkipped) = sensitiveArchiveFilter(msgs, sensitiveGate)

            // ①「本人」这条线（可选开关）：只收我发出去的，而且和「现在聊的是谁」无关 ——
            //    要提炼的是「我怎么说话」，跟对方是谁没关系。所以刻意排在认会话名**之前**：
            //    认不出会话名的那些页面，我自己的话照样有效。开关关着就一条都不收。
            if (prefs?.getBoolean(Keys.SELF_STYLE_ON, false) == true) {
                for (m in archived) {
                    if (!m.fromMe || m.attachment || m.text.isBlank()) continue
                    if (!sentMsgs.add("s|" + m.text)) continue
                    fresh.add(SELF_ROLE_KEY to RoleMsg(true, m.text, now))
                }
            }

            // ② 按联系人归档：认不出会话名就整页跳过（宁可漏记也不能记错人）
            if (chatName.isNotBlank()) {
                for (m in archived) {
                    if (m.attachment || m.text.isBlank()) continue
                    if (!sentMsgs.add(roleObservationKey(chatName, m.fromMe, m.text))) continue
                    fresh.add(chatName to RoleMsg(m.fromMe, m.text, now))
                }
            }

            if (sensitiveSkipped > 0) {
                // 只记条数，不记正文 —— 轨迹会回传、常驻 App 本地
                Trace.note(
                    "敏感",
                    "$sensitiveSkipped 条命中敏感词，没有记进角色档案（不进档 = 以后也不会作为背景发出去）",
                )
            }
            if (fresh.isEmpty()) return
            // 分批，别把广播的 extras 撑爆
            fresh.chunked(20).forEach { Heartbeat.send(a, 0, roles = Roles.encodeIncoming(it)) }
        } catch (t: Throwable) {
            XposedApi.log("record: $t")
        }
    }

    /**
     * 拼「这次要带给模型的角色背景」：TA 是你什么人 + 平时的关系 + 之前攒下的聊天记录。
     *
     * 当前屏幕上已经有的那几行不再重复带（否则同一句话会出现两遍，模型容易当成说了两次）。
     */
    /**
     * 拼「这次要带给模型的额外背景」。
     *
     * 两块内容：
     * ①「我的说话风格」—— App 每天自动提炼的 skill（开了才有）。和当前聊的是谁无关，
     *    但它决定「怎么说」；放在最前面，因为它管的是语气和用词，比关系描述更"贴脸"。
     * ② 对方档案 —— TA 是你什么人 + 平时的关系 + 之前攒下的聊天记录（原来那套）。
     *
     * 当前屏幕上已经有的那几行不再重复带（否则同一句话会出现两遍，模型容易当成说了两次）。
     */
    private fun roleContextFor(name: String, current: List<ChatMsg>): String? {
        val p = prefs
        // 说话风格：**开关关着就绝不注入**，哪怕以前生成过 —— 「关闭」就该是关闭。
        // 关掉时 App 会把采集到的原始样本清掉，但已生成的那份留着，所以重新打开立刻就能用。
        val style = try {
            if (p != null && p.getBoolean(Keys.SELF_STYLE_ON, false)) {
                p.getString(Keys.SELF_SKILL, "").orEmpty().trim()
            } else {
                ""
            }
        } catch (t: Throwable) {
            ""
        }
        val role = if (name.isBlank()) null else try {
            p?.getString(Keys.ROLES, "")?.takeIf { it.isNotBlank() }
                ?.let { raw -> Roles.decode(raw).firstOrNull { it.key == name } }
        } catch (t: Throwable) {
            null
        }
        val hasRole = role != null &&
            (role.relation.isNotBlank() || role.note.isNotBlank() || role.msgs.isNotEmpty())
        if (style.isBlank() && !hasRole) return null

        val seen = current.map { (if (it.fromMe) "1" else "0") + "|" + it.text }.toHashSet()
        return buildString {
            if (style.isNotBlank()) {
                append("【我的说话风格（由我自己发过的话自动提炼）】\n").append(style).append('\n')
                append("候选回复要贴合这个说话风格：用词、语气、句子长短、标点与表情习惯都要像。\n")
            }
            if (hasRole && role != null) {
                append("【对方档案】微信名「").append(role.name).append('」')
                if (role.relation.isNotBlank()) append("｜TA 是用户的：").append(role.relation)
                append('\n')
                if (role.note.isNotBlank()) append("【平时的关系】").append(role.note).append('\n')
                val older = role.msgs
                    .filter { (if (it.fromMe) "1" else "0") + "|" + it.text !in seen }
                    .takeLast(20)
                if (older.isNotEmpty()) {
                    append("【更早的聊天记录（按时间顺序，本模块平时记录的）】\n")
                    older.forEach { append(if (it.fromMe) "我: " else "对方: ").append(it.text.take(60)).append('\n') }
                }
                append("回复要符合以上关系；不确定的地方不要脑补。")
            }
        }
    }

    /** 让列表适配器重新绑定可见行（只在新学到控件类时调用一次）。 */
    private fun nudgeRebind(list: ViewGroup) {
        try {
            val adapter = list.javaClass.getMethod("getAdapter").invoke(list) ?: return
            adapter.javaClass.getMethod("notifyDataSetChanged").invoke(adapter)
            XposedApi.log("已请求列表重新绑定（好让 setText 钩子抓到原文）")
        } catch (t: Throwable) {
            // 不是 RecyclerView 就算了；下次滚动/新消息时会自然重新绑定
        }
    }

    private fun dumpDiagnosis(
        decor: View,
        list: ViewGroup?,
        input: View?,
        why: String,
        manual: Boolean = false,
        extra: String? = null,
    ) {
        val now = System.currentTimeMillis()
        if (!manual && now - lastDiagAt < 30_000L) return
        lastDiagAt = now
        try {
            val diag = buildString {
                append(why).append('\n')
                if (!extra.isNullOrBlank()) append(extra).append('\n')
                append(reader.diagnose(decor, list, input))
            }
            XposedApi.log("$diag")
            Heartbeat.send(a, 0, diag)
        } catch (t: Throwable) {
            XposedApi.log("生成诊断失败: $t")
        }
    }

    /**
     * 面板自己的状态快照。
     *
     * 为什么需要：「卡片不弹」有好几个分支都能造成（一条都没解析出来 / 最后一条是我发的 /
     * 没找到输入框 / 没找到列表 / 被关掉 / 还在最短间隔内 / 卡片挂在了旧的 decor 上），
     * 光看 View 树区分不出来，必须把面板内部的判定变量一起报回来。
     */
    private fun stateReport(list: ViewGroup?): String = try {
        val cfg = config
        val decor = a.window?.decorView
        val rows = list?.let { runCatching { reader.snapshot(it) }.getOrNull() }
        val msgs = rows?.let {
            runCatching { parser.parse(it).takeLast(cfg?.ctx ?: 8) }.getOrNull()
        }
        buildString {
            append("—— 面板状态 ——\n")
            append("running=$running attached=$attached onChat=$onChat job=$job recheck=$recheck\n")
            append("card=${vis(card)} isShown=${card.isShown} 挂在当前decor=${card.parent === decor}\n")
            append("chip=${vis(chip)} isShown=${chip.isShown} 文本=「${chipText}」 挂在当前decor=${chip.parent === decor}\n")
            append("折叠：expanded=$expanded hasResult=$hasResult\n")
            append("inputRef=${inputRef?.let { "${it.javaClass.simpleName} ${it.width}x${it.height} isShown=${it.isShown}" } ?: "null"}\n")
            append("listRef=${listRef?.let { "${it.javaClass.simpleName} ${it.width}x${it.height} 子=${it.childCount}" } ?: "null"}\n")
            append("noListTicks=$noListTicks emptyNotified=$emptyNotified\n")
            append("距上次调用=${System.currentTimeMillis() - lastCallAt}ms（最短间隔 ${cfg?.minIntervalSec}s）\n")
            append("cfg: enabled=${cfg?.enabled} skill=${cfg?.skillId} prompt=${cfg?.prompt?.length}字 ctx=${cfg?.ctx}\n")
            // 图片识别状态 + 每一行「找图」的现场：排查「图上有字却没认出来」就看这两行
            append("识图：" + ocr.debugLine() + "\n")
            rows?.mapNotNull { it.imageProbe }?.take(2)?.forEach { append("行内找图：$it\n") }
            if (msgs == null) {
                append("解析：列表为空，没跑\n")
            } else {
                append("解析出 ${msgs.size} 条；最后一条" +
                    if (msgs.lastOrNull()?.fromMe == true) "是【我】发的 → 按设计收起卡片（小气泡应仍可见）" else "是对方发的" + "\n")
                append("最后 3 条：" + msgs.takeLast(3).joinToString(" ‖ ") {
                    val who = if (it.fromMe) "我" else if (it.who.isNotBlank()) "对方(${it.who})" else "对方"
                    "$who:${it.text.take(16)}"
                } + "\n")
            }
        }
    } catch (t: Throwable) {
        "—— 面板状态 ——\n(生成失败 $t)\n"
    }

    private fun vis(v: View) = if (v.visibility == View.VISIBLE) "显示" else "隐藏"

    /**
     * 兜底重挂。
     *
     * 有些页面会把 decorView 换掉（Activity 复用、窗口重建），那时卡片还挂在**上一个** decor 上，
     * 于是「读取一切正常，但屏幕上什么都看不见」。挂错地方时重新挂一次即可。
     */
    private fun ensureAttached(decor: ViewGroup) {
        if (attached && card.parent === decor && chip.parent === decor) return
        val now = System.currentTimeMillis()
        if (now - lastAttachTry < 3_000L) return
        lastAttachTry = now
        XposedApi.log("decor 变了，重新挂卡片（card.parent=${card.parent}）")
        runCatching { (card.parent as? ViewGroup)?.removeView(card) }
        runCatching { (chip.parent as? ViewGroup)?.removeView(chip) }
        runCatching { card.removeAllViews() }
        attached = false
        attach()
    }

    /**
     * 配置每 tick 重读。
     *
     * 迁移前靠「比对文件 mtime + reload()」才敢重读 —— 因为读的是磁盘上的 XML，
     * 中间隔着一层。现在配置由框架直接推到本进程的内存里，读到的永远是最新值，
     * 既没有文件也没有 reload()，所以直接重建一份 ConfigData 就行
     * （对象很小，900ms 重建一次无所谓）。
     */
    private fun refreshPrefs() {
        try {
            val p = prefs ?: return
            config = ConfigData.from(p)
        } catch (t: Throwable) {
            XposedApi.log("prefs: $t")
        }
    }

    private fun reloadConfig(): Boolean {
        if (prefs == null) {
            showMessage("读不到配置：确认模块已在 LSPosed 启用并勾选了微信，然后强杀微信重开")
            return false
        }
        return try {
            refreshPrefs()
            true
        } catch (t: Throwable) {
            XposedApi.log("prefs: $t")
            showMessage("配置读取异常：${t.message}")
            false
        }
    }

    private fun ask(cfg: ConfigData, msgs: List<ChatMsg>, fingerprint: String, roleContext: String?) {
        job = Job.Analyze(fingerprint)
        lastCallAt = System.currentTimeMillis()
        // 只记「几条 · 哪个会话 · 走哪条路」，**不记正文**
        Trace.note(
            "调用",
            "发起分析：${msgs.size} 条 · 会话「${chatName.ifBlank { "?" }}」 · 路线=" +
                (if (ProxyProtocol.routeOf(cfg.proxyEnabled, cfg.proxyToken) == ProxyProtocol.ROUTE_PROXY) {
                    "本地代理"
                } else {
                    "直连"
                }),
        )
        val gen = ++generation
        showThinking()
        Thread {
            var suggestion: Suggestion? = null
            var tokens = 0
            var error: Throwable? = null
            // 分级模式会跑两路，两边的 trace 都要留着 —— 用同步表收，普通 StringBuilder 会互相踩
            val traces = java.util.Collections.synchronizedList(mutableListOf<String>())
            try {
                if (cfg.graded) {
                    val out = Graded.run(
                        replyClient = LlmClient(cfg, onTrace = { traces.add("【写回复一路】\n$it") }),
                        riskClient = LlmClient(
                            cfg.riskEndpoint(),
                            onTrace = { traces.add("【风险评估一路】\n$it") },
                            second = true,
                        ),
                        skillPrompt = cfg.prompt,
                        msgs = msgs,
                        roleContext = roleContext,
                        waitMs = gradedWaitMs(cfg.probeReplyMs, cfg.probeRiskMs),
                    )
                    suggestion = out.suggestion
                    tokens = out.totalTokens
                    // 两路都挂了才当整体失败；只挂一路时 suggestion 里带着 warnings，照常渲染
                    if (out.suggestion == null) {
                        error = LlmException(
                            "两路都没跑通",
                            "风险一路：${out.riskError ?: "-"}\n写回复一路：${out.replyError ?: "-"}",
                        )
                    }
                } else {
                    val result = LlmClient(cfg, onTrace = { traces.add(it) }).analyze(msgs, roleContext)
                    suggestion = result.suggestion
                    tokens = result.totalTokens
                }
            } catch (t: Throwable) {
                error = t
            }
            val trace = traces.joinToString("\n\n")
            // 顺带回传「这次实际发出去的那一份」，App 首页可以对着核对 skill 有没有真的生效
            Heartbeat.send(
                a,
                tokens,
                call = trace.takeIf { it.isNotBlank() },
                // 这次实际走哪条路：App 的「本地代理」卡片会显示，用来确认代理到底有没有被用上
                route = ProxyProtocol.routeOf(cfg.proxyEnabled, cfg.proxyToken),
            )
            val ok = suggestion
            val err = error
            handler.post {
                if (gen != generation) return@post
                job = Job.None
                Trace.note(
                    "结果",
                    when {
                        ok != null -> "模型回来了：风险=${ok.risk} · 候选 ${ok.replies.size} 条" +
                            (if (ok.warnings.isEmpty()) "" else " · 告警：${ok.warnings.joinToString("；")}")
                        err is LlmException -> "失败：${err.message}"
                        else -> "失败：${err?.message ?: "未知错误"}"
                    },
                )
                when {
                    ok != null -> {
                        cache[fingerprint] = ok
                        while (cache.size > 12) cache.remove(cache.keys.first())
                        render(ok, msgs, fromCache = false)
                    }
                    err is LlmException -> showMessage(
                        "生成失败：${err.message}${err.hint?.let { "\n$it" } ?: ""}",
                        isError = true,
                    )
                    else -> showMessage("生成失败：${err?.message ?: "未知错误"}", isError = true)
                }
            }
        }.start()
    }

    private fun regenerate() {
        cache.remove(lastFingerprint)
        lastCallAt = 0
        // 「没变就不重读」那道早退要放过这一轮；再读不到消息列表也要照样报出来
        lastScreenFingerprint = ""
        recheck = true
        handler.post {
            runCatching { tick() }.onFailure { XposedApi.log("refresh: $it") }
        }
    }

    // ---------------- 渲染 ----------------

    /**
     * 单条改写：只改用户点的那一条。
     *
     * 结果写回 [cache]（下一次 tick 重渲染还是新文本），同时就地更新这个 TextView。
     * 全程 runCatching —— 这段跑在微信进程里，漏一个异常就是微信崩。
     */
    private fun rewriteReply(
        index: Int,
        reply: Reply,
        body: TextView,
        head: String,
        instruction: String,
        opts: LinearLayout,
        shown: Suggestion,
    ) {
        val c = config
        if (c == null) {
            showMessage("读不到配置，改写用不了", isError = true)
            return
        }
        if (job != Job.None) return
        opts.visibility = View.GONE
        val gen = generation
        val requestKey = lastFingerprint
        val original = reply.text
        body.text = "$head｜改写中…"
        job = Job.Rewrite(requestKey)
        Thread {
            var text: String? = null
            var err: Throwable? = null
            var used = 0
            try {
                val (t, tokens) = LlmClient(c).rewrite(original, instruction)
                text = t
                used = tokens
            } catch (t: Throwable) {
                err = t
            }
            val done = text
            val failure = err
            handler.post {
                runCatching {
                    if (gen != generation) return@runCatching
                    job = Job.None
                    if (done != null) {
                        body.text = "$head｜$done"
                        val cur = cache[requestKey] ?: shown
                        val list = cur.replies.toMutableList()
                        if (index in list.indices) list[index] = list[index].copy(text = done)
                        cache[requestKey] = cur.copy(replies = list)
                        if (used > 0) Heartbeat.send(a, used)
                    } else {
                        body.text = "$head｜$original"
                        showMessage("改写失败：${failure?.message ?: "未知错误"}", isError = true)
                    }
                }.onFailure { XposedApi.log("rewrite: $it") }
            }
        }.start()
    }

    private fun render(s: Suggestion, msgs: List<ChatMsg>, fromCache: Boolean) {
        // **幂等**：同一份结果、同样几条消息，就不再重建正文。
        // 重建 = removeAllViews + 重新加一遍，会肉眼可见地闪一下；如果指纹不稳定导致每轮都重建，
        // 用户看到的就是「卡片折叠、展开、折叠、展开」那种往复抖动（用户实测报过）。
        val sig = conversationDigest(
            s.intent, s.risk, s.note, s.why, s.best.toString(), s.partial.toString(),
            s.replies.joinToString("\u0000") { it.style + it.text }, msgs.size.toString(),
        )
        if (body == Body.Result && renderedSig == sig) {
            setChip("军师 · 风险${s.risk} · ${s.replies.size}条", chipColor(s.risk))
            return
        }
        renderedSig = sig
        body = Body.Result
        title.text = "军师 · ${s.intent} · 风险${s.risk}" + if (fromCache) " · 缓存" else ""
        title.setTextColor(riskColor(s.risk))
        bodyBox.removeAllViews()
        bodyBox.addView(label(lastThree(msgs), 10f, colorSub) { setPadding(0, dp(2), 0, dp(2)) })
        if (s.note.isNotBlank()) {
            bodyBox.addView(label(s.note, 12f, colorSub) { setPadding(0, dp(4), 0, dp(2)) })
        }
        // 分级模式：哪一路挂了直接写在卡片上（另一路的内容照常显示）
        s.warnings.forEach { w ->
            bodyBox.addView(label(w, 11f, colorWarn) { setPadding(0, dp(4), 0, 0) })
        }
        s.replies.forEachIndexed { index, r ->
            val starred = s.best == index
            // 偏长的候选标一下：提示词要求 40~45 字，太长的不像人话（只提示，不拦）
            val head = buildString {
                append(if (starred) "★ ${r.style}" else r.style)
                if (isReplyTooLong(r.text)) append(" · 偏长")
            }
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val body = label("$head｜${r.text}", 14f, colorMain) {
                background = roundRect(colorReply, 14)
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            body.setOnClickListener { fill(r.text) }
            body.setOnLongClickListener {
                copyToClipboard(r.text)
                true
            }
            row.addView(body, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            // 「✎」= 对**这一条**做局部改写（再短点 / 更正式 / 换个说法），另外两条不动
            val edit = label("✎", 13f, colorSub) {
                background = roundRect(colorReply, 14)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                gravity = Gravity.CENTER
            }
            row.addView(
                edit,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { leftMargin = dp(4) },
            )
            bodyBox.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(6) },
            )
            if (starred && s.why.isNotBlank()) {
                bodyBox.addView(label("★ 最推荐：${s.why}", 10f, colorSub) { setPadding(dp(12), dp(2), 0, 0) })
            }

            // 三个预设默认不占地方，点「✎」才出现
            val opts = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                visibility = View.GONE
            }
            REWRITE_PRESETS.forEach { preset ->
                val option = label(preset.first, 11f, colorSub) {
                    background = roundRect(colorReply, 12)
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    gravity = Gravity.CENTER
                }
                option.setOnClickListener {
                    rewriteReply(index, r, body, head, preset.second, opts, s)
                }
                opts.addView(
                    option,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { rightMargin = dp(4) },
                )
            }
            bodyBox.addView(
                opts,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(4) },
            )
            edit.setOnClickListener {
                opts.visibility = if (opts.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        bodyBox.addView(
            label(
                buildString {
                    append("点一下填入输入框 · 长按复制 · 本模块不会自动发送")
                    append("\nAI 生成，发送前请自行判断")
                    if (s.partial) append("\n⚠ 模型输出被截断，这份是抢救出来的，建议点「↻ 重新识别」重来")
                    if (s.replies.isEmpty()) append("\n⚠ 这次没拿到可用回复，点「↻ 重新识别」重来")
                },
                10f,
                colorSub,
            ) { setPadding(0, dp(6), 0, 0) },
        )
        // 默认不打扰：只有你已经把卡片打开着，才把新结果摊在眼前；
        // 折叠着的时候只更新按钮上的「风险 + 条数」，点一下才展开。
        setChip("军师 · 风险${s.risk} · ${s.replies.size}条", chipColor(s.risk))
    }

    private fun renderSensitive(hits: List<String>) {
        // 「有变化」= 第一次进敏感态、或命中词换了。**只有变化时才动正文与折叠状态**：
        // 同一批词复现时只更新按钮 —— 以前这里无条件 setExpanded(true)，
        // 用户点 ▾ 收起来、900ms 后又被弹开，根本按不住。
        val changed = body != Body.Sensitive || sensitiveHits != hits
        body = Body.Sensitive
        if (!changed) {
            setChip("⚠ 敏感内容 · 点开看", 0xE6C97A00.toInt())
            return
        }
        sensitiveHits = hits
        title.text = "⚠ 这条含敏感内容"
        title.setTextColor(colorWarn)
        bodyBox.removeAllViews()
        bodyBox.addView(
            label("命中：${hits.joinToString("、")}\n默认不发给模型。要发就点下面，或到「设置」里关掉这个检查。", 12f, colorSub),
        )
        val go = label("仍然分析这一条", 14f, colorMain) {
            background = roundRect(colorReply, 14)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        go.setOnClickListener {
            // 记住「这一屏已被你放行」：用屏稳定键，点一次就一直有效（换了屏 / 换了会话自然失效）
            skipSensitiveFor = screenKey
            lastScreenFingerprint = ""
            recheck = true
            Trace.note("敏感", "用户点了「仍然分析这一条」→ 这一屏放行（不再拦）")
            handler.post { runCatching { tick() } }
        }
        bodyBox.addView(go, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        // 这条要你亲自拍板「发不发」，所以直接摊开，不折叠
        setChip("⚠ 敏感内容 · 点开看", 0xE6C97A00.toInt())
        setExpanded(true, "敏感命中（状态转移）")
    }

    private fun showMessage(message: String, isError: Boolean = false) {
        val changed = body != Body.Message || lastMessage != message
        body = Body.Message
        lastMessage = message
        // 同一句话复现时只更新按钮：**不再重复自动展开** —— 否则「每轮都弹一次」会和收起动作
        // 打起架来，看起来就是折叠 / 展开往复循环（用户实测报过）。
        if (changed) {
            title.text = if (isError) "生成失败" else "TalkTact"
            title.setTextColor(if (isError) colorBad else colorAccent)
            bodyBox.removeAllViews()
            bodyBox.addView(label(message, 13f, if (isError) colorBad else colorMain))
        }
        // 出错和提示这类「你必须看一眼」的事，直接摊开，不折叠
        setChip(
            if (isError) "⚠ 军师出错 · 点开看" else "军师 · 点开看",
            if (isError) 0xE6D64545.toInt() else 0xE67C3AED.toInt(),
        )
        if (changed) setExpanded(true, if (isError) "出错，要你看一眼" else "提示，要你看一眼")
    }

    // ---------------- 折叠 / 展开 ----------------

    /**
     * 折叠态：聊天页右上角只有一个**按钮**。
     * 展开态：才是「风险判断 + 候选回复 + 刷新」那张卡片。
     *
     * 为什么要折叠：卡片是铺在消息列表顶上的，聊天时一直挡着最上面那几条消息。
     * 改成「不点开就只是个按钮」，想看了点开，看完一点就收回去，不打扰看消息。
     */
    private fun applyVisibility() {
        card.visibility = if (expanded) View.VISIBLE else View.GONE
        chip.visibility = if (!expanded && onChat) View.VISIBLE else View.GONE
    }

    /**
     * 折叠 / 展开的**唯一出口**。
     *
     * 不只改状态，还把「谁让它变的」写进轨迹 —— 以前没有这一条，于是用户说的
     * 「卡片自己关了」在轨迹里**完全看不见**（这也是它拖了两轮没查出来的原因）。
     */
    private fun setExpanded(visible: Boolean, why: String = "", byUser: Boolean = false) {
        if (expanded == visible) {
            applyVisibility()
            return
        }
        if (!byUser) {
            // **抖动保护**：机器自己在一段时间内反复改折叠状态 → 停手。
            // 用户实测报过「折叠、展开、折叠、展开往复循环」——那是 tick 里两个分支在互相打架；
            // 不管根因是哪两个，这种抖动都不该由用户承受。顶到上限就保持现状，并把原因记进轨迹
            // （轨迹会连着写出前几次是谁让它变的，根因照样查得到）。
            val now = System.currentTimeMillis()
            if (now - autoToggleAt > AUTO_TOGGLE_WINDOW_MS) {
                autoToggles = 0
                autoToggleAt = now
            }
            autoToggles++
            if (autoToggles > AUTO_TOGGLE_MAX) {
                Trace.note("卡片", "抖动保护：${AUTO_TOGGLE_WINDOW_MS / 1000} 秒内机器已改过 $AUTO_TOGGLE_MAX 次 → 不再自动改（这次的原因：$why）")
                applyVisibility()
                return
            }
        }
        Trace.note("卡片", (if (visible) "展开" else "收起") + "（$why）" + if (byUser) " · 用户点的" else "")
        expanded = visible
        applyVisibility()
    }

    /** 折叠按钮上的文案 + 底色（低绿 / 中黄 / 高红，一眼看出这条多危险）。 */
    private fun setChip(text: String, color: Int = 0xE67C3AED.toInt()) {
        chipText = text
        chip.text = text
        chip.background = roundRect(color, 18)
        applyVisibility()
    }

    /**
     * 风险 → **实底**（chip 底色）：白字压在上面，所以走「同色 + 降不透明度」这一条规则，
     * 不再单独维护第二套色号。
     *
     * 「中」是唯一的例外：「文字档」的琥珀（0xFFE8A317）当底色压白字太浅、读不出来 ——
     * 所以实底取同色系**深一档**的 [colorWarnSolid]，而不是换一个颜色。
     */
    private fun chipColor(risk: String): Int = when (risk) {
        "中" -> solidOf(colorWarnSolid)
        else -> solidOf(riskColor(risk))
    }

    /** 语义色 → 实底（0.9 不透明度）。 */
    private fun solidOf(c: Int): Int = 0xE6000000.toInt() or (c and 0x00FFFFFF)

    /**
     * 点按钮：开着就收起；有内容就打开；都没有才去识别一次（判定见纯函数 [chipTap]）。
     *
     * 这几步都在尽量不烧 token、也不动用户的折叠意图：
     * ① 卡片开着 → 收起（用户自己点的，允许）；
     * ② 正在调接口 / 手上就有结果 → 直接打开（看进度或看结果）；
     * ③ **这一屏问过、缓存里那份结果还在 → 直接摆出来**（fromCache，不重新调模型）；
     * ④ 都没有 → 打开卡片 + 重新识别一次（开着才有进度可看，不然就是「点了没反应」）。
     *
     * ⚠️ ③ 以前是缺的：按钮一律走 [regenerate]，而它会**先把缓存删掉**再去问一次模型 ——
     * 于是「有缓存可看」的那一屏点一下等于白烧一次 token，还要等模型回来才有内容。
     */
    private fun onChipClick() {
        val action = chipTap(expanded, busy, hasResult, cache.containsKey(lastFingerprint))
        Trace.note(
            "点击",
            "点按钮 → $action（expanded=$expanded busy=$busy 有内容=$hasResult 有缓存=${cache.containsKey(lastFingerprint)} 正文=$body）",
        )
        when (action) {
            ChipTap.Collapse -> setExpanded(false, "用户点按钮收起", byUser = true)
            ChipTap.Expand -> setExpanded(true, "用户点按钮展开（手上已有内容）", byUser = true)
            ChipTap.Restore -> {
                val cached = cache[lastFingerprint] ?: return
                Trace.note("缓存", "点按钮：这一屏的结果还在缓存里，直接摆出来（不重新调模型）")
                setExpanded(true, "用户点按钮展开（摆缓存）", byUser = true)
                render(cached, lastMsgs, fromCache = true)
            }
            ChipTap.Regenerate -> {
                // 先把卡片打开：重新识别要等模型，开着才有「正在读这一屏」可看
                setExpanded(true, "用户点按钮展开（去重新识别）", byUser = true)
                regenerate()
            }
        }
    }

    /**
     * 这一屏没什么可展示的（最后一条是我发的 / 读不到文字 / 白名单外…）。
     *
     * [reason] 只在**卡片已经开着**的时候写进正文（折叠着就不用说这么多）；[chip] 是折叠按钮上的字。
     *
     * ⚠️ 这里**不收起卡片**（见 [expanded] 上那条规矩）：以前它就是 `setExpanded(false)`，
     * 也就是「点开又自动关闭」的来源。要收，用户点 ▾；离开聊天页时 [hideAll] 会收。
     */
    private fun showIdle(reason: String = "", chip: String = "↻ 识别") {
        body = Body.Idle
        setChip(chip)
        if (!expanded || reason.isBlank()) return
        // 卡片开着：就地换成一句说明，别把它抽走（抽走就是「自动关闭」）
        title.text = "军师 · 待命"
        title.setTextColor(colorAccent)
        bodyBox.removeAllViews()
        bodyBox.addView(label(reason, 13f, colorSub))
    }

    /** 正在调接口：按钮上就能看出来，默认不摊开卡片打扰人（但展开着的那份也同步成进度）。 */
    private fun showThinking() {
        body = Body.Thinking
        setChip("军师 · 思考中…")
        title.text = "军师 · 思考中…"
        title.setTextColor(colorAccent)
        bodyBox.removeAllViews()
        bodyBox.addView(label("正在读这一屏、调接口…", 13f, colorMain))
    }

    /**
     * 整块藏掉（card + chip 一起）。
     *
     * [why] 只进轨迹：这是**唯一**能让用户屏幕上「什么都没有」的地方，所以必须留下是谁让它藏的
     * （用户实测抱怨的「一句话也没有」就是它 —— 以前它在轨迹里没有痕迹）。
     */
    private fun hideAll(why: String) {
        Trace.note("隐藏", "整块藏起来（$why）")
        resetPanel()
    }

    /**
     * 把面板复位成「不在场」：作废在飞的任务 + 收起卡片 + 清内容类型与指纹。
     *
     * 抽出来是因为 [onPause] 与 [hideAll] 原本各写了一份，两边的字段清单还不一样 ——
     * 于是「切走再回来」和「离开聊天页」两条路径的行为会悄悄分叉（这类分叉最难查）。
     * 现在只有这**一份**。
     */
    private fun resetPanel() {
        // 非聊天页每 2.6 秒就会走一次 hideAll，别每次都白抬 generation（没在飞的任务就不用作废）
        if (onChat || job != Job.None) generation++
        job = Job.None
        onChat = false
        noListTicks = 0
        expanded = false
        body = Body.None
        stuckReason = ""
        // 离开这一屏也要把指纹作废，回来才会重新渲染一次
        lastScreenFingerprint = ""
        card.visibility = View.GONE
        chip.visibility = View.GONE
    }

    /**
     * 「这一屏像不像聊天页」的证据（**宁松勿紧**）。
     *
     * 松的代价：可能在不该出现的地方留一个小按钮（看得见、也能忽略，而且按钮上就写着原因）；
     * 紧的代价：屏幕上**什么都没有** —— 既看不到状态，也没法自查（用户实测就是这么抱怨的）。
     * 三个信号任一成立就算数：认得出输入框 / 认得出消息列表 / Activity 名字像聊天页。
     *
     * [cheap] = true 时不翻列表（省一次整树遍历，给「窗口没焦点」这种每轮都会走到的路径用）。
     */
    private fun chatPageEvidence(cand: View?, decor: View, cheap: Boolean): String? = when {
        cand != null -> "有像输入框的控件"
        !cheap && reader.findList(decor, null) != null -> "有像消息列表的容器"
        a.javaClass.simpleName.contains("Chatting", ignoreCase = true) ->
            "Activity 名字像聊天页（${a.javaClass.simpleName}）"
        else -> null
    }

    /**
     * 「人在聊天页，但这一屏读不了」：**绝不把 card + chip 一起藏掉**。
     *
     * 用户实测抱怨过「有些对话连展开前的小气泡都不出现」和「出了结果又折叠、点不开了」——
     * 根因就是以前这种时候直接 [hideAll]：屏幕上什么都不剩，用户既看不到状态、也没法自查。
     * 现在永远留一个**能点的折叠按钮**（点一下把原因摊出来），按钮文案本身也说明了情况。
     *
     * 刻意不动 [expanded]（用户开着就开着），也不作废在飞的请求 —— 这一屏只是「读不出来」，不是「走了」。
     */
    private fun showStuck(chip: String, reason: String) {
        onChat = true // 折叠按钮的可见性要求 onChat
        stuckReason = reason
        body = Body.Stuck
        // ⚠️ 必须把「这一屏渲染过了」作废：否则弹出的窗口一关、指纹又变回原样，
        //   下一轮在指纹那一步就提前 return —— 界面永远停在「读不到这一屏」，点按钮还会白烧一次 token。
        lastScreenFingerprint = ""
        // 上面这行的道理同 hideAll 里的同名写法。
        setChip(chip, 0xE6C97A00.toInt())
        if (expanded) renderStuck()
    }

    /** 把「这一屏为什么用不了」摊进卡片（点开按钮就能看到，也能把诊断拿到手）。 */
    private fun renderStuck() {
        title.text = "军师 · 这一屏用不了"
        title.setTextColor(colorWarn)
        bodyBox.removeAllViews()
        bodyBox.addView(label(stuckReason, 13f, colorSub))
        bodyBox.addView(
            label("长按标题可以生成一份诊断；App 首页「诊断」卡片里能复制出来发我。", 13f, colorSub) {
                setPadding(0, dp(6), 0, 0)
            },
        )
    }

    private fun lastThree(msgs: List<ChatMsg>): String =
        "读取：" + msgs.takeLast(3).joinToString(" ‖ ") {
            val who = if (it.fromMe) "我" else if (it.who.isNotBlank()) "对方(${it.who})" else "对方"
            "$who:${it.text.take(16)}"
        }

    /** 风险 → 语义色（文字档）：和 chip 底色同一份来源（[riskMap]），只有「中」的实底会再压深一档。 */
    private fun riskColor(risk: String): Int = riskMap[risk] ?: colorAccent

    // ---------------- 交互 ----------------

    private fun fill(text: String) {
        val decor = a.window?.decorView ?: return
        val input = reader.findChatInput(decor) ?: return
        try {
            input.setText(text)
            input.setSelection(text.length)
            input.requestFocus()
            title.text = "已填入 · 按发送即可"
            toast("已填入输入框")
        } catch (t: Throwable) {
            XposedApi.log("fill: $t")
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.setPrimaryClip(ClipData.newPlainText("goutou", text))
            toast("已复制")
        } catch (t: Throwable) {
            XposedApi.log("copy: $t")
        }
    }

    private fun toast(message: String) {
        try {
            Toast.makeText(a, message, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            // 忽略
        }
    }
}

/** 「被动收集会话名」的最小间隔：那一屏每 2.6 秒 tick 一次，没必要次次都读整棵视图树。 */
private const val CONV_PULL_MIN_MS = 15_000L

/** 折叠抖动保护：这个窗口内机器自己最多改 [AUTO_TOGGLE_MAX] 次折叠状态（用户点的不受限）。 */
private const val AUTO_TOGGLE_WINDOW_MS = 10_000L
private const val AUTO_TOGGLE_MAX = 3

/**
 * 实时探测的轮询间隔。
 *
 * 为什么单独一条轮询：微信切到后台时 [Panel] 的 ticker 会停（onPause），但微信进程还在 ——
 * 探测要是挂在 ticker 上，用户在 App 里一点就是「没回应」。6 秒一次、只读一次 SharedPreferences，
 * 换来「App 问一句，微信那边一定有人应」。 */
private const val PROBE_POLL_MS = 6_000L

/**
 * 「点一下聊天页那个折叠按钮」该干什么。
 *
 * 抽成纯函数是为了能单测 —— 这段判断以前写在 [Panel.onChipClick] 里，
 * 「缓存里有结果却去重新调模型」那个 bug 就藏在这儿（见 [chipTap] 的注释）。
 */
enum class ChipTap { Collapse, Expand, Restore, Regenerate }

/**
 * 卡片正文的类型（注入侧状态机唯一的「内容类型」真值）。
 *
 * 存在的理由：以前用 `hasResult` + `bodyBox.childCount` + `sensitiveHits` 互相推断正文是什么，
 * 于是守卫会误命中（正文是结果、却按敏感卡处理），而且用户点 ▾ 也收不起来。
 */
private enum class Body { None, Idle, Thinking, Stuck, Sensitive, Result, Message }

/**
 * 手上的异步任务（注入侧状态机唯一的「在忙」真值，见 [Panel.job]）。
 *
 * 以前拆成 busy / rewriteBusy / pendingRequestKey 三个字段：改写途中「分析」也以为自己在跑，
 * 而在飞的那次挂在哪一屏又只能靠另一个字段去猜。合成一个类型之后，状态不可能再自相矛盾。
 */
private sealed interface Job {
    /** 手上没有在跑的任务。 */
    object None : Job
    /** 正在为 [key] 这一屏问模型（同一屏不用重发）。 */
    data class Analyze(val key: String) : Job
    /** 正在改写 [key] 那一屏里的一条候选。 */
    data class Rewrite(val key: String) : Job
}

/**
 * 点折叠按钮的判定（纯函数，单测见 ChipTapTest）。
 *
 * 顺序：开着 → 收起；有进度或结果 → 打开；**缓存里还有这一屏的结果 → 直接摆出来**；
 * 都没有 → 重新识别。
 *
 * ⚠️ 第三档是补上的：以前只有「有结果」和「重新识别」两档，而「缓存里有结果、手上没有」
 * 这一档（hasResult 会被换会话 / 读不到文字那些分支清掉，缓存却还在）落进了「重新识别」——
 * 点一下等于白删缓存、白烧一次 token，还要等模型回来，看着就像「点了没反应」。
 */
fun chipTap(expanded: Boolean, busy: Boolean, hasResult: Boolean, hasCache: Boolean): ChipTap = when {
    expanded -> ChipTap.Collapse
    busy || hasResult -> ChipTap.Expand
    hasCache -> ChipTap.Restore
    else -> ChipTap.Regenerate
}
