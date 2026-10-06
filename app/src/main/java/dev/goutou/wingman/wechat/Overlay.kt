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

    private val colorCard = if (night) 0xF21B1A22.toInt() else 0xF2FFFFFF.toInt()
    private val colorStroke = if (night) 0x557C3AED.toInt() else 0x447C3AED.toInt()
    private val colorMain = if (night) 0xFFEDEAF5.toInt() else 0xFF1C1B22.toInt()
    private val colorSub = if (night) 0xFF9A96A8.toInt() else 0xFF6E6A7C.toInt()
    private val colorReply = if (night) 0xFF2A2440.toInt() else 0xFFEDE7FA.toInt()
    private val colorAccent = 0xFF7C3AED.toInt()
    private val colorWarn = 0xFFE8A317.toInt()
    private val colorBad = 0xFFD64545.toInt()
    private val colorOk = 0xFF2FA566.toInt()

    private val card = LinearLayout(a)
    private val title = TextView(a)
    private val bodyBox = LinearLayout(a)
    private val chip = TextView(a)

    private var attached = false
    private var running = false
    private var onChat = false
    private var busy = false
    private var force = false
    private var generation = 0
    private var cardTop = -1
    private var lastCallAt = 0L
    private var lastFingerprint = ""
    private var skipSensitiveFor = ""
    /** 折叠 / 展开。默认折叠 —— 不打开的话，脸上就只有一个小按钮。 */
    private var expanded = false
    /** 卡片里现在是不是一份「能看的结果」（候选回复 / 敏感拦截）。不是的话，点按钮该去重新识别。 */
    private var hasResult = false
    /** 折叠按钮上的字，跟着状态走。 */
    private var chipText = ""

    private var lastDiagAt = 0L
    /** 自动回传决策轨迹的水位：上次真正发出去时的内容版本号 + 时间（见 [maybeSendTrace]）。 */
    private var lastTraceVer = -1
    private var lastTraceAt = 0L
    private var lastDiagReq = 0L
    private var emptyNotified = false
    /** 「白名单开着但认不出这个会话的名字」只提示一次，免得每屏都弹。 */
    private var whitelistNameNotified = false
    private var chatName = ""
    private var lastScreenFingerprint = ""
    private var contextRevision = ""
    private var pendingRequestKey = ""
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
        busy = false
        onChat = false
        generation++
        expanded = false
        hasResult = false
        handler.removeCallbacks(ticker)
        card.visibility = View.GONE
        chip.visibility = View.GONE
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
        collapse.setOnClickListener { setExpanded(false) }
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

        decor.addView(card, matchTop(dp(10), dp(96), dp(10)))
        decor.addView(chip, wrapTopEnd(dp(10), dp(96)))
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

        if (!decor.hasWindowFocus()) {
            Trace.note("焦点", "窗口没焦点（微信不在前台，或被别的窗口盖着）")
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
            hideAll()
            return
        }
        onChat = true
        if (!reloadConfig()) {
            Trace.note("配置", "读不到配置（prefs 为空）：模块没启用，或没勾选微信")
            return
        }
        val cfg = config ?: return
        if (!cfg.enabled) {
            Trace.note("开关", "总开关关着（设置 → 微信内自动分析）→ 这一页什么都不做")
            hideAll()
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
            if (noListTicks >= 2) {
                dumpDiagnosis(decor, null, input, "找到了输入框，但没找到消息列表")
                if (force || noListTicks == 2) {
                    force = false
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
        val observedName = Roles.normalizeKey(reader.findChatTitle(decor, list).orEmpty())
        val settings = conversationDigest(
            cfg.prompt, cfg.model, cfg.baseUrl, cfg.apiKey, cfg.model2, cfg.baseUrl2, cfg.apiKey2,
            cfg.graded.toString(), cfg.temperature.toString(), cfg.maxTokens.toString(),
            cfg.jsonMode.toString(), cfg.ctx.toString(), cfg.allowSensitive.toString(),
            cfg.whitelistEnabled.toString(), cfg.whitelist.sorted().toString(),
            cfg.proxyEnabled.toString(), cfg.proxyPort.toString(), cfg.proxyToken,
        )
        val profile = runCatching {
            Roles.decode(prefs?.getString(Keys.ROLES, "").orEmpty()).firstOrNull { it.key == observedName }
        }.getOrNull()
        // History changes require a fresh parse; the final key excludes already visible records to avoid repeat calls.
        val revision = conversationDigest(
            observedName, settings, profile?.name.orEmpty(), profile?.relation.orEmpty(), profile?.note.orEmpty(),
            prefs?.getBoolean(Keys.SELF_STYLE_ON, false).toString(),
            if (prefs?.getBoolean(Keys.SELF_STYLE_ON, false) == true) prefs?.getString(Keys.SELF_SKILL, "").orEmpty() else "",
        )
        if (revision != contextRevision) {
            generation++ // Discard replies and rewrites started for the previous conversation or profile.
            busy = false
            contextRevision = revision
            chatName = observedName
            lastScreenFingerprint = ""
            lastFingerprint = ""
            lastCallAt = 0L
            skipSensitiveFor = ""
            hasResult = false
            showIdle()
        }
        val historyRevision = conversationDigest(*profile?.msgs.orEmpty().map {
            roleObservationKey(observedName, it.fromMe, it.text)
        }.toTypedArray())
        val fingerprint = conversationDigest(observedName, reader.fingerprint(list), revision, historyRevision)
        if (!force && !ocr.takeDirty() && fingerprint == lastScreenFingerprint) {
            Trace.note("跳过", "会话、资料和这一屏未变，不重复读")
            return
        }
        force = false
        lastScreenFingerprint = fingerprint

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
                showIdle()
            }
            return
        }
        emptyNotified = false

        // 记进「角色」页（认不出会话名就整页跳过，宁可漏记也不记错人）
        recordToRoles(decor, list, msgs)

        // 白名单：没勾的会话「彻底关闭」。位置放在 recordToRoles **之后** ——
        // 只有跑完它，这一屏的 chatName 才是刚认出来的（不然第一帧用的还是上一个聊天的名字）。
        // recordToRoles 里面也有一道同样的闸，那道负责「连记录都不做」。
        if (blockedByWhitelist(cfg)) {
            Trace.note(
                "白名单",
                if (chatName.isBlank()) {
                    "白名单开着，但这个会话的名字没认出来 → 拦下（宁可不读，也不猜）"
                } else {
                    "会话「$chatName」不在白名单里 → 不分析"
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
                showIdle()
            }
            return
        }

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
        // 最后一条是我发的：没什么可回的，收起卡片（按钮留在场上，点一下就是重新识别）
        if (msgs.last().fromMe) {
            Trace.note("方向", "最后一条是我发的 → 没什么可回，收起卡片")
            showIdle()
            return
        }
        val roleContext = roleContextFor(chatName, msgs)
        val requestKey = conversationRequestKey(chatName, settings, roleContext, msgs)
        lastFingerprint = requestKey
        if (busy) {
            if (pendingRequestKey == requestKey) return
            generation++
            busy = false
        }
        cache[requestKey]?.let {
            Trace.note("缓存", "这一屏之前问过，直接用缓存（不烧 token）")
            render(it, msgs, fromCache = true)
            return
        }

        val joined = msgs.joinToString("\n") { it.text }
        val hits = if (cfg.allowSensitive || requestKey == skipSensitiveFor) emptyList() else Sensitive.hits(joined)
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
            if (fresh) Trace.note("会话", "App 要拉会话列表，但当前在聊天页（读到的是消息正文）→ 跳过，等切到列表页")
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

            // ①「本人」这条线（可选开关）：只收我发出去的，而且和「现在聊的是谁」无关 ——
            //    要提炼的是「我怎么说话」，跟对方是谁没关系。所以刻意排在认会话名**之前**：
            //    认不出会话名的那些页面，我自己的话照样有效。开关关着就一条都不收。
            if (prefs?.getBoolean(Keys.SELF_STYLE_ON, false) == true) {
                for (m in msgs) {
                    if (!m.fromMe || m.attachment || m.text.isBlank()) continue
                    if (!sentMsgs.add("s|" + m.text)) continue
                    fresh.add(SELF_ROLE_KEY to RoleMsg(true, m.text, now))
                }
            }

            // ② 按联系人归档：认不出会话名就整页跳过（宁可漏记也不能记错人）
            if (chatName.isNotBlank()) {
                for (m in msgs) {
                    if (m.attachment || m.text.isBlank()) continue
                    if (!sentMsgs.add(roleObservationKey(chatName, m.fromMe, m.text))) continue
                    fresh.add(chatName to RoleMsg(m.fromMe, m.text, now))
                }
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
            append("running=$running attached=$attached onChat=$onChat busy=$busy force=$force\n")
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
        busy = true
        pendingRequestKey = fingerprint
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
                busy = false
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
        force = true
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
        if (busy) return
        opts.visibility = View.GONE
        val gen = generation
        val requestKey = lastFingerprint
        val original = reply.text
        body.text = "$head｜改写中…"
        busy = true
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
                    busy = false
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
        hasResult = true
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
        hasResult = true
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
            skipSensitiveFor = lastFingerprint
            force = true
            handler.post { runCatching { tick() } }
        }
        bodyBox.addView(go, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        // 这条要你亲自拍板「发不发」，所以直接摊开，不折叠
        setChip("⚠ 敏感内容 · 点开看", 0xE6C97A00.toInt())
        setExpanded(true)
    }

    private fun showMessage(message: String, isError: Boolean = false) {
        hasResult = false
        title.text = if (isError) "生成失败" else "TalkTact"
        title.setTextColor(if (isError) colorBad else colorAccent)
        bodyBox.removeAllViews()
        bodyBox.addView(label(message, 13f, if (isError) colorBad else colorMain))
        // 出错和提示这类「你必须看一眼」的事，直接摊开，不折叠
        setChip(
            if (isError) "⚠ 军师出错 · 点开看" else "军师 · 点开看",
            if (isError) 0xE6D64545.toInt() else 0xE67C3AED.toInt(),
        )
        setExpanded(true)
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

    private fun setExpanded(visible: Boolean) {
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

    private fun chipColor(risk: String): Int = when (risk) {
        "低" -> 0xE62FA566.toInt()
        "中" -> 0xE6C97A00.toInt()
        "高" -> 0xE6D64545.toInt()
        else -> 0xE67C3AED.toInt()
    }

    /**
     * 点按钮：开着就收起；有结果就打开；什么都没有就去识别一次。
     *
     * 正在调接口时不重新发请求 —— 那会把这一轮的结果丢掉，等于白烧一次 token；
     * 这时点一下只是把卡片打开看进度。
     */
    private fun onChipClick() {
        if (expanded) {
            setExpanded(false)
            return
        }
        if (busy || hasResult) {
            setExpanded(true)
            return
        }
        regenerate()
    }

    /** 这一屏没什么可展示的：收起卡片，按钮留在场上（点一下就是重新识别）。 */
    private fun showIdle() {
        hasResult = false
        setExpanded(false)
        setChip("↻ 识别")
    }

    /** 正在调接口：按钮上就能看出来，默认不摊开卡片打扰人（但展开着的那份也同步成进度）。 */
    private fun showThinking() {
        setChip("军师 · 思考中…")
        title.text = "军师 · 思考中…"
        title.setTextColor(colorAccent)
        bodyBox.removeAllViews()
        bodyBox.addView(label("正在读这一屏、调接口…", 13f, colorMain))
    }

    private fun hideAll() {
        onChat = false
        noListTicks = 0
        expanded = false
        hasResult = false
        card.visibility = View.GONE
        chip.visibility = View.GONE
    }

    private fun lastThree(msgs: List<ChatMsg>): String =
        "读取：" + msgs.takeLast(3).joinToString(" ‖ ") {
            val who = if (it.fromMe) "我" else if (it.who.isNotBlank()) "对方(${it.who})" else "对方"
            "$who:${it.text.take(16)}"
        }

    private fun riskColor(risk: String): Int = when (risk) {
        "低" -> colorOk
        "中" -> colorWarn
        "高" -> colorBad
        else -> colorAccent
    }

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
