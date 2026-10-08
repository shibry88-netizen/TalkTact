package dev.goutou.wingman.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.unit.sp
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.goutou.wingman.config.ConfigData
import dev.goutou.wingman.config.Backup
import dev.goutou.wingman.config.ConfigStore
import dev.goutou.wingman.config.DiagExport
import androidx.core.content.ContextCompat
import dev.goutou.wingman.proxy.ProxyProtocol
import dev.goutou.wingman.proxy.ProxyState
import dev.goutou.wingman.proxy.ProxyService
import dev.goutou.wingman.config.GLASS_QUALITY_AUTO
import dev.goutou.wingman.config.GLASS_QUALITY_HIGH
import dev.goutou.wingman.config.GLASS_QUALITY_LOW
import dev.goutou.wingman.config.Role
import dev.goutou.wingman.config.Roles
import dev.goutou.wingman.llm.BUILT_IN_SKILLS
import dev.goutou.wingman.llm.Geo
import dev.goutou.wingman.llm.LlmClient
import dev.goutou.wingman.llm.LlmException
import dev.goutou.wingman.llm.NetInfo
import dev.goutou.wingman.llm.RemoteSkill
import dev.goutou.wingman.llm.Graded
import dev.goutou.wingman.llm.REWRITE_PRESETS
import dev.goutou.wingman.llm.Suggestion
import dev.goutou.wingman.llm.ModelList
import dev.goutou.wingman.llm.gradedWaitMs
import dev.goutou.wingman.llm.isReplyTooLong
import dev.goutou.wingman.wechat.ChatMsg
import dev.goutou.wingman.wechat.Sensitive
import dev.goutou.wingman.wechat.filterNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.goutou.wingman.config.SELF_ROLE_KEY
import dev.goutou.wingman.llm.StyleSkill
import dev.goutou.wingman.style.SelfStyle
import kotlin.math.roundToInt

private enum class Level(val label: String) { OK("通过"), WARN("待确认"), BAD("有问题") }

private data class Check(val title: String, val desc: String, val level: Level, val onClick: (() -> Unit)? = null)

/**
 * 状态等级 → **图形档**状态色（8dp 点 / hero 那个圈 / 卡片描边）。
 *
 * 批 4c：状态色只有这一个出口，别再各写一份 when。
 * 第 7 版又拆了一档：浅色下当**文字**用对比度不够（warn 只有 2.2:1），所以图形用 [Palette.okMark]
 * 这一组，文字用 [levelText] 那一组（更深）。
 */
private fun levelColor(palette: Palette, level: Level): Color = when (level) {
    Level.OK -> palette.okMark
    Level.WARN -> palette.warnMark
    Level.BAD -> palette.badMark
}

/** 状态等级 → **文字档**状态色（正文里那行说明）。 */
private fun levelText(palette: Palette, level: Level): Color = when (level) {
    Level.OK -> palette.ok
    Level.WARN -> palette.warn
    Level.BAD -> palette.bad
}

/**
 * 页面顶栏上的小按钮（返回 / 刷新）。
 *
 * 以前这两个动作是纯文字（TextButton），看着像标题的一部分、点起来也没有「按钮」的反馈；
 * 现在统一给它们一个描边框 —— 一眼能看出是能点的东西。
 */
/**
 * 会折叠的玻璃卡片：标题行一直在（带一行「现在是什么」的摘要），点一下才展开内容。
 *
 * 和「运行状态」页那个 [DetailToggle] 是同一套观感，区别是这里直接包成一张卡片 ——
 * 页面上那些「配一次就不常看」的大块内容用它，页面能短一大截。
 */
@Composable
private fun FoldCard(
    title: String,
    summary: String,
    expanded: Boolean,
    glassAlpha: Float,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalPalette.current
    val angle by animateFloatAsState(if (expanded) 180f else 0f, tween(180))
    GlassCard(glassAlpha) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                Text(summary, fontSize = 13.sp, color = palette.sub)
            }
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = palette.sub,
                modifier = Modifier.rotate(angle),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column { content() }
        }
    }
}

/**
 * 长说明的折叠显示 —— 只做呈现，不参与任何逻辑。
 *
 * 默认最多显示 [collapsedLines] 行；**只有真的被截断**（hasVisualOverflow）
 * 才在下面给一个「展开 / 收起」。短文案不会多出任何按钮，
 * 所以调大调小 [collapsedLines] 只影响观感，不影响功能。
 */
@Composable
private fun HintText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 11.sp,
    collapsedLines: Int = 2,
) {
    var expanded by remember { mutableStateOf(false) }
    var overflowed by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(
            text = text,
            fontSize = fontSize,
            color = color,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result -> if (!expanded) overflowed = result.hasVisualOverflow },
        )
        if (overflowed) {
            Text(
                text = if (expanded) "收起" else "展开",
                fontSize = fontSize,
                fontWeight = FontWeight.Medium,
                color = LocalPalette.current.primary,
                modifier = Modifier
                    .padding(top = 3.dp)
                    .clickable(onClickLabel = if (expanded) "收起这段说明" else "展开这段说明") {
                        expanded = !expanded
                    },
            )
        }
    }
}


/** 一行「标题 + 值」的网络信息。值取不到就显示 null —— 不编。 */
@Composable
private fun NetRow(label: String, value: String?, hint: String? = null) {
    val palette = LocalPalette.current
    val missing = value.isNullOrBlank()
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = palette.sub, modifier = Modifier.width(76.dp))
        Column(Modifier.weight(1f)) {
            Text(value ?: "null", fontSize = 13.sp, color = if (missing) palette.warn else palette.text)
            if (!hint.isNullOrBlank()) Text(hint, fontSize = 10.sp, color = palette.sub)
        }
    }
}

/** 把「IP · 省份 · 运营商」拼成一行；全空返回 null（UI 那边显示 null）。 */
private fun joinInfo(vararg parts: String?): String? =
    parts.filter { !it.isNullOrBlank() }.joinToString(" · ").ifBlank { null }

@Composable
private fun HeaderButton(text: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.height(36.dp),
        shape = RoundedCornerShape(RadiusR1),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
    ) { Text(text, fontSize = 13.sp, color = palette.primary) }
}

@Composable
private fun CheckRow(check: Check, glassAlpha: Float) {
    val palette = LocalPalette.current
    val color = levelColor(palette, check.level)
    // 状态行是顶层元素（直接躺在 LazyColumn 上），所以用玻璃面板：背后是真实的背景模糊
    GlassSurface(
        shape = RoundedCornerShape(RadiusR2),
        glassAlpha = glassAlpha,
        // 直接铺在渐变上的行也是 L1 —— 统一走 L1 的填充数值（以前自己写一套 0.26/0.18，
        // 结果比卡片还亮，一眼就分成两种材质）
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        onClick = check.onClick,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            // 批 4c：状态点统一 8dp（原来 10dp / 8dp 各写各的）
            StatusDot(color)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(check.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                Text(check.desc, fontSize = 13.sp, color = palette.sub)
            }
            Text(check.level.label, fontSize = 13.sp, color = levelText(palette, check.level), fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * 「详细状态」的折叠开关。
 *
 * 顶上那张「全部就绪」卡片保持原样不动，7 项明细收在这一行下面 ——
 * 不点开就只占一行，点一下才铺出来。
 */
@Composable
private fun DetailToggle(
    expanded: Boolean,
    summary: String,
    glassAlpha: Float,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val angle by animateFloatAsState(if (expanded) 180f else 0f, tween(180))
    GlassSurface(
        shape = RoundedCornerShape(RadiusR2),
        glassAlpha = glassAlpha,
        // 直接铺在渐变上的行也是 L1 —— 统一走 L1 的填充数值（以前自己写一套 0.26/0.18，
        // 结果比卡片还亮，一眼就分成两种材质）
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        onClick = onClick,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("详细状态", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                Text(summary, fontSize = 13.sp, color = palette.sub)
            }
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = palette.sub,
                modifier = Modifier.rotate(angle),
            )
        }
    }
}

// ================= 运行状态 =================

@Composable
fun StatusScreen(store: ConfigStore, onTrial: () -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    var cfg by remember { mutableStateOf(store.load()) }
    val usage = remember(tick) { store.usage() }
    val beat = remember(tick) { store.heartbeatAt() }
    val probeReqAt = remember(tick) { store.probeAt() }
    val probeAckAt = remember(tick) { store.probeAck() }
    val probeWhere = remember(tick) { store.probeInfo() }
    var probeVerdict by remember { mutableStateOf<String?>(null) }
    var probeDetail by remember { mutableStateOf<String?>(null) }
    var probeOk by remember { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(0) }
    /** 「详细状态」展开了没有。默认收起：这一页只需要一眼看健康。 */
    var detail by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val glass = cfg.glassAlpha
    val active = moduleActive(store)
    val fresh = beat > 0 && System.currentTimeMillis() - beat < 6 * 3600_000L
    val confirmed = store.scopeConfirmed()

    // 实时探测的结论（问题 5）：模块「现在还在不在」只认这条，心跳时间戳退居「历史」。
    val nowMs = System.currentTimeMillis()
    // 回执里带着请求号（「#<那次请求的时间戳>」）：只有**号和当前这一轮对得上**才算「这一轮被回答了」。
    // 否则新建的面板会去答一个很久以前的旧请求，界面会误报「刚刚回过话」。
    val ackId = probeWhere.removePrefix("#").substringBefore(' ').toLongOrNull() ?: 0L
    val answered = probeReqAt > 0 && ackId == probeReqAt
    val whereText = probeWhere.substringAfter(" · ", "").ifBlank { probeWhere }

    val liveText: String
    val liveLevel: Level
    when {
        answered -> {
            val secs = (nowMs - probeAckAt) / 1000
            liveText = "刚刚回过话：${secs} 秒前" + if (whereText.isBlank()) "" else " · $whereText"
            liveLevel = if (nowMs - probeAckAt < 120_000L) Level.OK else Level.WARN
        }
        probeReqAt > 0 && nowMs - probeReqAt < 10_000L -> {
            liveText = "已发出探测，正在等微信进程回话…（要微信在运行）"
            liveLevel = Level.WARN
        }
        probeReqAt > 0 -> {
            liveText = "探测没有回应（${formatTime(probeReqAt)}）—— 确认微信在运行、作用域勾了微信，再强杀微信重开；也可以点这一行重试"
            liveLevel = Level.BAD
        }
        else -> {
            liveText = "还没探测过 —— 点这一行探测一下（会在微信进程里跑一轮再回话）"
            liveLevel = Level.WARN
        }
    }

    // 进这一页自动探一次；等回话的那十几秒里每秒刷一下。
    // 有界轮询（repeat 12 次）—— 无限协程会把截图回归测试挂住（这个坑踩过）。
    LaunchedEffect(Unit) {
        store.requestProbe()
        tick++
    }
    LaunchedEffect(answered) {
        if (!answered) repeat(12) {
            kotlinx.coroutines.delay(1000)
            tick++
        }
    }

    val checks = listOf(
        Check(
            "模块激活",
            moduleActiveReason(store) ?: "未激活：在框架里启用本模块并勾选微信，然后强杀微信重开",
            if (active) Level.OK else Level.BAD,
        ),
        Check(
            "模块是否在跑（实时探测）",
            liveText,
            liveLevel,
        ) {
            // 点这一行 = 重新探一次
            store.requestProbe()
            tick++
        },
        Check(
            "微信内已生效（历史）",
            when {
                fresh -> "微信进程最近报过心跳：${formatTime(beat)}"
                confirmed -> "你手动确认过（还没收到心跳）"
                else -> "没收到微信进程心跳：确认作用域勾了微信并强杀重开"
            },
            if (fresh) Level.OK else Level.WARN,
        ) {
            store.setScopeConfirmed(!confirmed)
            cfg = store.load()
        },
        Check(
            "API Key",
            if (cfg.apiKey.isBlank()) "未填写，到「设置」填" else "已填写（明文存本机，见设置页说明）",
            if (cfg.apiKey.isBlank()) Level.BAD else Level.OK,
        ),
        Check(
            "接口与模型",
            if (cfg.graded) {
                "${cfg.model}（写回复）+ ${cfg.riskEndpoint().model}（风险） · 分级模式"
            } else {
                "${cfg.model} · ${cfg.baseUrl}"
            },
            if (cfg.baseUrl.startsWith("http")) Level.OK else Level.BAD,
        ),
        Check(
            "当前军师",
            "${skillName(cfg.skillId)}" +
                " · ${cfg.prompt.length} 字 · " + if (cfg.prompt.contains("replies")) "含 JSON 契约" else "缺少 JSON 契约",
            if (cfg.prompt.length >= 80 && cfg.prompt.contains("replies")) Level.OK else Level.WARN,
        ),
        Check(
            "调用节奏",
            "参考最近 ${cfg.ctx} 条 · 最短间隔 ${cfg.minIntervalSec}s · " +
                if (cfg.maxTokens == 0) "token 无限制" else "单次上限 ${cfg.maxTokens} token",
            Level.OK,
        ),
        Check(
            "敏感内容检查",
            if (cfg.allowSensitive) "已关闭：聊天内容会直接发给接口" else "开启：命中验证码/银行卡/转账等会先问一次",
            if (cfg.allowSensitive) Level.WARN else Level.OK,
        ),
    )
    val bad = checks.count { it.level == Level.BAD }
    val warn = checks.count { it.level == Level.WARN }
    val ok = checks.count { it.level == Level.OK }
    val overall = if (bad > 0) Level.BAD else if (warn > 0) Level.WARN else Level.OK
    // 明细按「**异常优先**」排：有问题 → 待确认 → 通过（同档内保持原来的顺序 —— Kotlin 的 sortedBy 是稳定排序）。
    // 以前固定按声明顺序铺开，出问题时最该动的那一条可能排在第 5 位，一眼看不出该先处理哪个。
    val ordered = checks.sortedBy {
        when (it.level) {
            Level.BAD -> 0
            Level.WARN -> 1
            Level.OK -> 2
        }
    }
    val shown = when (filter) {
        1 -> ordered.filter { it.level == Level.BAD }
        2 -> ordered.filter { it.level == Level.WARN }
        3 -> ordered.filter { it.level == Level.OK }
        else -> ordered
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            ScreenHeader("运行状态", "WeChat · 聊天助手 ${appVersion(context)}") {
                HeaderButton("↻ 刷新") { tick++ }
                Button(
                    onClick = onTrial,
                    shape = RoundedCornerShape(RadiusR2),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                ) { Text("试聊") }
            }
        }
        item {
            val tone = levelColor(palette, overall)
            GlassCard(glass, border = tone.copy(alpha = CardBorderAlpha)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 批 4c：不再铺一块 46dp 实心色 —— 淡底 + 1dp 描边 + 状态色符号。
                    // 全屏「大面积」只留这一处，而且是空心的圈，红起来也不刺眼。
                    Box(
                        Modifier.size(46.dp).clip(CircleShape)
                            .background(tone.copy(alpha = ChipBgAlpha))
                            .border(1.dp, tone.copy(alpha = CardBorderAlpha), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            when (overall) { Level.OK -> "✓"; Level.WARN -> "!"; Level.BAD -> "✕" },
                            fontSize = 24.sp,
                            color = tone,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(
                            when (overall) { Level.OK -> "全部就绪"; Level.WARN -> "基本可用"; Level.BAD -> "需要处理" },
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.text,
                        )
                        Text(
                            "${checks.size} 项已检测 · 通过 $ok · 待确认 $warn · 有问题 $bad",
                            fontSize = 13.sp,
                            color = palette.sub,
                        )
                        // hero 的职责收窄成两件事：一眼看健康 + 直接点出**最该处理的那一条**。
                        // 完整明细整包交给下面的「详细状态」（那边是异常优先排序）。
                        val firstIssue = ordered.firstOrNull { it.level != Level.OK }
                        if (firstIssue != null) {
                            Text(
                                "最该处理：${firstIssue.title} —— ${firstIssue.desc.take(70)}",
                                fontSize = 13.sp,
                                color = levelText(palette, firstIssue.level),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCell("${usage.first}", "调用次数", Modifier.weight(1f))
                    StatCell("${usage.second}", "累计 token", Modifier.weight(1f))
                    StatCell(formatTime(beat).takeIf { beat > 0 } ?: "—", "最近心跳", Modifier.weight(1f))
                }
            }
        }
        item {
            Column {
                // 原来那 7 项明细是直接铺在页面上的，把这一页撑得很长；现在收在折叠菜单里
                DetailToggle(
                    expanded = detail,
                    // 摘要里把「异常优先」写明：展开后第一眼就是该动的那几条，不用自己往下翻
                    summary = "详细状态（异常优先）· ${checks.size} 项：有问题 $bad · 待确认 $warn · 通过 $ok",
                    glassAlpha = glass,
                ) { detail = !detail }
                AnimatedVisibility(
                    visible = detail,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column {
                        GlassCard(glass) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                GlassPill("全部 ${checks.size}", filter == 0, Modifier.weight(1f)) { filter = 0 }
                                GlassPill("有问题 $bad", filter == 1, Modifier.weight(1f)) { filter = 1 }
                                GlassPill("待确认 $warn", filter == 2, Modifier.weight(1f)) { filter = 2 }
                                GlassPill("通过 $ok", filter == 3, Modifier.weight(1f)) { filter = 3 }
                            }
                        }
                        shown.forEach { CheckRow(it, glass) }
                    }
                }
            }
        }
        item {
            GlassCard(glass) {
                Text("接口自检", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                Text(
                    "只看「连不连得上、要多久」：发一次最小请求（一句 ping、只让它回 1 个 token）。\n" +
                        "不拼当前 skill、也不看模型回了什么 —— 模型没按 JSON 回复算不上连接问题，" +
                        "那种情况去「试一试」页验证。",
                    fontSize = 13.sp,
                    color = palette.sub,
                )

                val baseUrl = cfg.baseUrl
                val host = remember(baseUrl) { NetInfo.hostOf(baseUrl) }
                // 内网 IP / 目标 IP：本地或 DNS 就能拿到，不依赖任何第三方
                val localIp by produceState<String?>(null, tick) {
                    value = withContext(Dispatchers.IO) { NetInfo.localIpv4() }
                }
                val targetIp by produceState<String?>(null, tick) {
                    value = withContext(Dispatchers.IO) { NetInfo.resolve(host) }
                }
                // 归属地：要问第三方，拿不到就 null（缓存 10 分钟；点「开始自检」会强制重查）。
                // 关掉开关就一次都不问；地址也能换成自己的（见「高级设置 → 诊断 → 网络信息」）。
                val geoUrl = remember(cfg.geoEndpoint) { cfg.geoEndpoint.trim().ifBlank { NetInfo.GEO_ENDPOINT } }
                val localGeo by produceState<Geo?>(null, tick, cfg.geoEnabled, geoUrl) {
                    value = if (!cfg.geoEnabled) null
                    else withContext(Dispatchers.IO) { NetInfo.geo(geoUrl) }
                }
                val targetGeo by produceState<Geo?>(null, tick, targetIp, cfg.geoEnabled, geoUrl) {
                    value = if (!cfg.geoEnabled || targetIp == null) null
                    else withContext(Dispatchers.IO) { NetInfo.geo(geoUrl, ip = targetIp) }
                }

                Spacer(Modifier.height(10.dp))
                NetRow("本机内网", localIp)
                NetRow(
                    "本机公网",
                    if (!cfg.geoEnabled) "（归属地查询已关闭）"
                    else joinInfo(localGeo?.ip, localGeo?.province ?: localGeo?.country, localGeo?.isp),
                )
                NetRow("目标服务器", joinInfo(targetIp, targetGeo?.province ?: targetGeo?.country), host)
                Spacer(Modifier.height(4.dp))
                HintText(
                    if (!cfg.geoEnabled) "归属地查询已在「高级设置 → 诊断 → 网络信息」里关掉（IP 仍然只在本机解析）。"
                    else "省份来自第三方 IP 库，仅供参考；取不到就显示 null。可在「高级设置 → 诊断 → 网络信息」里关掉或换接口。",
                    fontSize = 10.sp,
                    color = palette.sub,
                )

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            probing = true
                            probeVerdict = null
                            probeDetail = null
                            val conf = store.load()
                            scope.launch {
                                var verdict: String
                                var detail: String? = null
                                try {
                                    val r = withContext(Dispatchers.IO) { LlmClient(conf).probe() }
                                    if (r.totalTokens > 0) store.addUsage(r.totalTokens)
                                    probeOk = true
                                    verdict = "通 · 往返 ${r.totalMs}ms · 模型 ${r.model} · ${r.totalTokens} token"
                                    val sb = StringBuilder()
                                    sb.append("写回复一路：连接 ")
                                        .append(r.connectMs?.let { "${it}ms" } ?: "未测得")
                                        .append(" · DNS ${r.dnsMs}ms · 往返 ${r.totalMs}ms")
                                    r.targetIp?.let { sb.append(" · 目标 ").append(it) }

                                    // 分级模式：第二套接口也得单独测 —— 它可能是完全不同的服务商。
                                    // 两路的延迟要分别列出来；慢的那一路决定整张卡片什么时候能出来。
                                    var riskMs = 0L
                                    if (conf.graded) {
                                        try {
                                            val r2 = withContext(Dispatchers.IO) {
                                                LlmClient(conf.riskEndpoint(), second = true).probe()
                                            }
                                            if (r2.totalTokens > 0) store.addUsage(r2.totalTokens)
                                            riskMs = r2.totalMs
                                            sb.append("\n风险一路：连接 ")
                                                .append(r2.connectMs?.let { "${it}ms" } ?: "未测得")
                                                .append(" · DNS ${r2.dnsMs}ms · 往返 ${r2.totalMs}ms")
                                            r2.targetIp?.let { sb.append(" · 目标 ").append(it) }
                                            val diff = kotlin.math.abs(r.totalMs - r2.totalMs)
                                            if (diff > 300) {
                                                sb.append("\n两路差 ${diff}ms —— 快的那路要等慢的那路（自检只量最小请求，实际生成更久）")
                                            }
                                        } catch (t: Throwable) {
                                            sb.append("\n风险一路：失败 —— ").append(t.message)
                                        }
                                    }
                                    detail = sb.toString()
                                    // 记下来：设置页的公告栏要显示，运行时「两路对齐等待」也用它
                                    store.saveProbeMs(r.totalMs, riskMs)
                                } catch (t: Throwable) {
                                    probeOk = false
                                    verdict = "失败：${t.message}"
                                    detail = (t as? LlmException)?.hint
                                }
                                // 让归属地跟着这次自检重新查一遍
                                NetInfo.clearGeoCache()
                                tick++
                                probeVerdict = verdict
                                probeDetail = detail
                                probing = false
                            }
                        },
                        enabled = !probing,
                        shape = RoundedCornerShape(RadiusR2),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                    ) { Text(if (probing) "测试中…" else "开始自检") }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        probeVerdict ?: "",
                        fontSize = 13.sp,
                        color = if (probeOk) palette.ok else palette.bad,
                    )
                }
                probeDetail?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, fontSize = 13.sp, color = palette.sub)
                }
            }
        }
    }
}

// ================= 试一试 =================

@Composable
fun TrialScreen(store: ConfigStore, glassAlpha: Float) {
    val palette = LocalPalette.current
    var input by remember { mutableStateOf("对方: 在吗\n我: 在\n对方: 周末有空吗，想约你吃个饭") }
    var suggestion by remember { mutableStateOf<Suggestion?>(null) }
    var rewriting by remember { mutableStateOf<Int?>(null) }
    /** 哪一条的「✎」被点开了 —— 预设只在点开时出现（和微信里那张卡片同一套做法）。 */
    var presetsOpen by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val hits = remember(input) { Sensitive.hits(input) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 120.dp)) {
        ScreenHeader("试一试", "粘贴聊天 · 预览回复 · 验证接口")
        GlassCard(glassAlpha) {
            OutlinedTextField(
                colors = glassFieldColors(),
                shape = RoundedCornerShape(RadiusR2),
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp),
                label = { Text("每行一句，以「我:」或「对方:」开头") },
            )
            if (hits.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("⚠ 含敏感内容：${hits.joinToString("、")}（微信里会先问你一次）", fontSize = 13.sp, color = palette.warn)
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    loading = true
                    error = null
                    info = null
                    suggestion = null
                    val conf = store.load()
                    scope.launch {
                        try {
                            val msgs = input.lines().filter { it.isNotBlank() }.map {
                                val me = it.startsWith("我:") || it.startsWith("我：")
                                ChatMsg(me, it.substringAfter(':').substringAfter('：').trim().ifEmpty { it })
                            }
                            if (conf.graded) {
                                val out = withContext(Dispatchers.IO) {
                                    Graded.run(
                                        replyClient = LlmClient(conf),
                                        riskClient = LlmClient(conf.riskEndpoint(), second = true),
                                        skillPrompt = conf.prompt,
                                        msgs = msgs,
                                        roleContext = null,
                                        waitMs = gradedWaitMs(conf.probeReplyMs, conf.probeRiskMs),
                                    )
                                }
                                suggestion = out.suggestion
                                if (out.totalTokens > 0) store.addUsage(out.totalTokens)
                                info = "${out.millis}ms · ${out.totalTokens} token · 分级模式（两路并行）"
                                if (out.suggestion == null) {
                                    error = "两路都没跑通：风险一路 ${out.riskError ?: "-"}；写回复一路 ${out.replyError ?: "-"}"
                                }
                            } else {
                                val result = withContext(Dispatchers.IO) { LlmClient(conf).analyze(msgs) }
                                suggestion = result.suggestion
                                if (result.totalTokens > 0) store.addUsage(result.totalTokens)
                                info = "${result.millis}ms · ${result.totalTokens} token · ${result.model}"
                            }
                        } catch (t: Throwable) {
                            error = t.message
                        }
                        loading = false
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                enabled = !loading,
                shape = RoundedCornerShape(RadiusR2),
                colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
            ) { Text(if (loading) "思考中…" else "生成候选回复") }
        }
        error?.let { msg ->             GlassCard(glassAlpha, border = palette.badMark.copy(alpha = CardBorderAlpha)) { Text(msg, color = palette.bad, fontSize = 13.sp) } }
        info?.let { GlassCard(glassAlpha) { Text(it, fontSize = 13.sp, color = palette.sub) } }
        suggestion?.let { s ->
            GlassCard(glassAlpha) {
                Text("意图：${s.intent}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = palette.text)
                Text("风险：${s.risk}　${s.note}", fontSize = 13.sp, color = palette.sub)
                Text("AI 生成 · 发送前请自行判断", fontSize = 13.sp, color = palette.sub)
            }
            if (s.partial) {
                Text(
                    "⚠ 模型输出被截断，这条是从残缺 JSON 里抢救出来的，建议重新生成",
                    fontSize = 13.sp,
                    color = palette.warn,
                )
            }
            // 分级模式：哪一路挂了/没拿到回复，直接写出来，别让人对着空卡片猜
            s.warnings.forEach { w -> Text(w, fontSize = 13.sp, color = palette.warn) }
            if (s.replies.isEmpty()) {
                Text("⚠ 这次没拿到可用回复，点上面「生成候选回复」重来", fontSize = 13.sp, color = palette.warn)
            }
            // 与微信里那张卡片**同一套规格**（设计师 P1）：三条候选装在**同一张卡**里，
            // 每条是一个圆角填充块（头行「★ 风格 · 偏长」+ 右侧 ✎），预设**点 ✎ 才出现**。
            // 以前这里是「每条候选一张 GlassCard + 三个预设永远铺在外面」，和微信卡片是两套做法 ——
            // 同一个东西两副长相，用户得学两遍。
            GlassCard(glassAlpha) {
                s.replies.forEachIndexed { index, reply ->
                    val starred = s.best == index
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(RadiusR2))
                            .background(palette.soft)
                            .clickable { clipboard.setText(AnnotatedString(reply.text)) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                buildString {
                                    append(if (starred) "★ " else "")
                                    append(reply.style)
                                    if (isReplyTooLong(reply.text)) append(" · 偏长")
                                },
                                color = palette.primary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                            )
                            // ✎ = 展开这一条的预设（再短点 / 更正式 / 换个说法），另外两条不动
                            Text(
                                "✎",
                                fontSize = 13.sp,
                                color = palette.sub,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(RadiusR1))
                                    .background(palette.primary.copy(alpha = ChipBgAlpha))
                                    .clickable { presetsOpen = if (presetsOpen == index) null else index }
                                    .padding(horizontal = 10.dp, vertical = 3.dp),
                            )
                        }
                        Text(reply.text, fontSize = 15.sp, color = palette.text)
                        if (starred && s.why.isNotBlank()) {
                            Text("★ 最推荐：${s.why}", fontSize = 13.sp, color = palette.sub)
                        }
                        if (isReplyTooLong(reply.text)) {
                            Text(
                                "${reply.text.length} 字，偏长 —— 发出去不太像人话，点右上角 ✎ 选「再短点」",
                                fontSize = 13.sp,
                                color = palette.warn,
                            )
                        }
                        if (presetsOpen == index) {
                            Spacer(Modifier.height(6.dp))
                            // 只改这一条：走 complete()（一句话进一句话出），另外两条不动
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                REWRITE_PRESETS.forEach { preset ->
                                    Text(
                                        text = if (rewriting == index) "改写中…" else preset.first,
                                        fontSize = 13.sp,
                                        color = palette.primary,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(RadiusR1))
                                            .background(palette.primary.copy(alpha = ChipBgAlpha))
                                            .clickable(enabled = rewriting == null) {
                                                val old = suggestion?.replies?.getOrNull(index)?.text ?: return@clickable
                                                rewriting = index
                                                val conf = store.load()
                                                scope.launch {
                                                    try {
                                                        val (newText, tokens) = withContext(Dispatchers.IO) {
                                                            LlmClient(conf).rewrite(old, preset.second)
                                                        }
                                                        if (tokens > 0) store.addUsage(tokens)
                                                        suggestion = suggestion?.let { cur ->
                                                            val list = cur.replies.toMutableList()
                                                            if (index in list.indices) {
                                                                list[index] = list[index].copy(text = newText)
                                                            }
                                                            cur.copy(replies = list)
                                                        }
                                                    } catch (t: Throwable) {
                                                        error = "改写失败：${t.message}"
                                                    }
                                                    rewriting = null
                                                }
                                            }
                                            .padding(horizontal = 8.dp, vertical = 3.dp),
                                    )
                                }
                            }
                        }
                        Text("点一下复制", fontSize = 13.sp, color = palette.sub)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

// ================= 军师（提示词 / skill，两级） =================

@Composable
fun MentorScreen(store: ConfigStore, glassAlpha: Float, onSaved: () -> Unit) {
    val palette = LocalPalette.current
    var cfg by remember { mutableStateOf(store.load()) }
    // 「新手 / 进阶」以前是用 cfg.maxTokens == 0 推断出来的，会粘住：
    // 只要进过一次进阶（token 档位被改成无限制），以后每次进来都自动是进阶，切回新手也甩不掉。
    // 现在改成独立的界面偏好记下来。
    var advanced by remember { mutableStateOf(cfg.mentorAdvanced) }
    var tweak by remember { mutableStateOf(false) }
    // skill 那两块默认收起：进来先看到「现在用的是哪个」，想换再点开
    var skillsOpen by remember { mutableStateOf(false) }
    var importOpen by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(cfg.prompt) }
    var url by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 「有没有还没保存的改动」= 草稿提示词和**上次落盘的那份**比（跟「外观」「高级设置」同一套判据）。
    // 不能只看一个「保存过没有」的标志 —— 刚进页面它就说你没保存，红字等于天天亮着。
    val dirty = text != cfg.prompt

    fun persist(prompt: String, skillId: String, unlimited: Boolean) {
        store.save(store.load().copy(prompt = prompt, skillId = skillId, maxTokens = if (unlimited) 0 else store.load().maxTokens))
        cfg = store.load()
        text = cfg.prompt
        onSaved()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 120.dp)) {
        ScreenHeader("军师", "提示词 · skill")
        GlassCard(glassAlpha) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassPill("新手设置", !advanced, Modifier.weight(1f)) {
                    advanced = false
                    tweak = false
                    store.save(store.load().copy(mentorAdvanced = false))
                    cfg = store.load()
                }
                GlassPill("进阶设置", advanced, Modifier.weight(1f)) {
                    advanced = true
                    tweak = false
                    val wasLimited = cfg.maxTokens != 0
                    store.save(store.load().copy(maxTokens = 0, mentorAdvanced = true))
                    cfg = store.load()
                    onSaved()
                    dialog = if (wasLimited) {
                        "已切到进阶设置。\n\n为避免 skill 输出被截断，Token 限制已改为：无限制。"
                    } else {
                        "已切到进阶设置。"
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (!advanced) "用内置的原版狗头军师，提示词可以直接编辑微调。"
                else "从内置 skill 里选一个，或者粘一个 GitHub 上开源 skill 的地址导入。",
                fontSize = 13.sp,
                color = palette.sub,
            )
        }

        if (!advanced) {
            GlassCard(glassAlpha) {
                Text(
                    if (text.contains("replies")) "✓ 含 JSON 输出契约 · ${text.length} 字" else "⚠ 缺少 JSON 契约，卡片会解析不了",
                    fontSize = 13.sp,
                    color = if (text.contains("replies")) palette.ok else palette.warn,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    colors = glassFieldColors(),
                    shape = RoundedCornerShape(RadiusR2),
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp),
                )
                Spacer(Modifier.height(10.dp))
                if (dirty) {
                    Text("未保存，你所做出的改动不会被保存", fontSize = 13.sp, color = palette.bad)
                    Spacer(Modifier.height(6.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { text = ConfigData().prompt },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(RadiusR2),
                    ) { Text("恢复默认") }
                    Button(
                        onClick = { persist(text, "classic", unlimited = false) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(RadiusR2),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                    ) { Text(if (dirty) "保存" else "已保存") }
                }
            }
        } else {
            GroupTitle("当前军师")
            // 「当前军师」是当前生效的状态，不能藏起来 —— 所以它是一张常驻卡，放在最前面；
            // 换 skill 的入口收进下面的「Skill 库」分组，全页最多一层折叠。
            GlassCard(glassAlpha) {
                val src = BUILT_IN_SKILLS.firstOrNull { it.id == cfg.skillId }
                val canReset = src != null && text != src.prompt
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(skillName(cfg.skillId), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                            Spacer(Modifier.width(8.dp))
                            SourceChip(cfg.skillId)
                        }
                        Text(
                            "${text.length} 字 · " +
                                if (text.contains("replies")) "含 JSON 契约" else "⚠ 缺少 JSON 契约",
                            fontSize = 13.sp,
                            color = if (text.contains("replies")) palette.sub else palette.warn,
                        )
                    }
                    if (canReset) {
                        TextButton(onClick = { confirmReset = true }) { Text("重置") }
                    }
                    TextButton(onClick = { tweak = !tweak }) { Text(if (tweak) "收起" else "微调") }
                }
                Spacer(Modifier.height(6.dp))
                // 这里只给「这是什么」；真要逐字看/改，点右上那个「微调」—— 那块才是编辑区。
                HintText(
                    src?.summary ?: text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty(),
                    fontSize = 13.sp,
                    color = palette.sub,
                )
                if (tweak) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        colors = glassFieldColors(),
                        shape = RoundedCornerShape(RadiusR2),
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    if (dirty) {
                        Text("未保存，你所做出的改动不会被保存", fontSize = 13.sp, color = palette.bad)
                        Spacer(Modifier.height(6.dp))
                    }
                    Button(
                        onClick = {
                            // 内容跟哪个内置 skill 逐字一样，就还算那个 skill；动过了才算「自定义」
                            val known = BUILT_IN_SKILLS.firstOrNull { it.prompt == text }?.id
                            persist(text, known ?: "custom", unlimited = true)
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(RadiusR2),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                    ) { Text(if (dirty) "保存" else "已保存") }
                }
            }

            GroupTitle("Skill 库")
            FoldCard(
                title = "内置 skill",
                summary = "当前是「${skillName(cfg.skillId)}」· 点开可换",
                expanded = skillsOpen,
                glassAlpha = glassAlpha,
                onToggle = { skillsOpen = !skillsOpen },
            ) {
                Text("点一下直接启用（会替换当前提示词）", fontSize = 13.sp, color = palette.sub)
                Spacer(Modifier.height(4.dp))
                BUILT_IN_SKILLS.forEach { skill ->
                    val selected = cfg.skillId == skill.id && cfg.prompt == skill.prompt
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .clip(RoundedCornerShape(RadiusR2))
                            .background(palette.glassTint.copy(alpha = if (selected) 0.18f else 0.09f))
                            .clickable {
                                store.save(store.load().copy(prompt = skill.prompt, skillId = skill.id, maxTokens = 0))
                                cfg = store.load()
                                text = cfg.prompt
                                tweak = false
                                onSaved()
                                dialog = "已启用「${skill.name}」。\n\nToken 限制已改为：无限制。"
                            }
                            .padding(14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(skill.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text, modifier = Modifier.weight(1f))
                            Text(
                                if (selected) "使用中" else "启用",
                                fontSize = 13.sp,
                                color = if (selected) palette.ok else palette.primary,
                            )
                        }
                        Text(skill.summary, fontSize = 13.sp, color = palette.sub)
                    }
                }
            }

            FoldCard(
                title = "从 GitHub 导入 skill",
                summary = "粘 SKILL.md 的地址，导入并启用",
                expanded = importOpen,
                glassAlpha = glassAlpha,
                onToggle = { importOpen = !importOpen },
            ) {
                HintText(
                    "粘 SKILL.md 的地址即可（网页地址也行，会自动换成 raw 直链）。导入时会自动补齐 JSON 输出契约。",
                    fontSize = 13.sp,
                    color = palette.sub,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    colors = glassFieldColors(),
                    shape = RoundedCornerShape(RadiusR2),
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("https://github.com/…/SKILL.md") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        importing = true
                        note = null
                        val target = url
                        scope.launch {
                            note = try {
                                val fetched = withContext(Dispatchers.IO) { RemoteSkill.fetch(target) }
                                text = fetched
                                persist(fetched, "custom", unlimited = true)
                                dialog = "已导入并启用这个 skill。\n\nToken 限制已改为：无限制。"
                                "导入成功 · ${fetched.length} 字"
                            } catch (t: Throwable) {
                                "导入失败：${t.message}"
                            }
                            importing = false
                        }
                    },
                    enabled = !importing && url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(RadiusR2),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                ) { Text(if (importing) "导入中…" else "导入并启用") }
                note?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 13.sp, color = if (it.startsWith("导入成功")) palette.ok else palette.bad)
                }
            }
        }
    }

    dialog?.let { msg ->
        AlertDialog(
            onDismissRequest = { dialog = null },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("好") } },
            title = { Text("进阶设置") },
            text = { Text(msg) },
        )
    }

    // 重置是**不可逆**的（改过的那份不留备份），所以先问一句再动。
    if (confirmReset) {
        val target = BUILT_IN_SKILLS.firstOrNull { it.id == cfg.skillId }
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    target?.let { persist(it.prompt, it.id, unlimited = true) }
                    tweak = false
                }) { Text("重置") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } },
            title = { Text("重置提示词") },
            text = { Text("把提示词恢复成「${skillName(cfg.skillId)}」的原版内容。你改过的那一份不会留备份。") },
        )
    }
}

// ================= 设置 =================

/**
 * 设置首页。
 *
 * 只留日常会动的东西：外观（玻璃 / 背景）和关于。「接口地址 / 微信内自动分析 / 备份迁移 /
 * 诊断」这些配一次就不常动的，全收进「高级设置」二级页 —— 原来它们铺在这里，把外观挤到了下面。
 */
@Composable
fun SettingsScreen(
    store: ConfigStore,
    ui: ConfigData,
    onUi: (ConfigData) -> Unit,
    onOpenAdvanced: () -> Unit,
) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    var d by remember { mutableStateOf(ui) }
    // 见「高级设置」里同名的那段：拿草稿和上次落盘的那份比，才知道有没有没保存的改动
    var persisted by remember { mutableStateOf(ui) }
    val dirty = d != persisted

    fun update(next: ConfigData) {
        d = next
        onUi(next)
    }

    fun saveAppearance() {
        store.save(d)
        d = store.load()
        persisted = d
        onUi(d)
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (t: Throwable) {
                // 有的来源不支持持久化授权，那就只在本次运行里有效
            }
            update(d.copy(bgUri = uri.toString()))
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 120.dp)) {
        ScreenHeader("设置", "外观 · 高级设置 · 关于")

        GlassCard(d.glassAlpha) {
            Text("外观", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
            Spacer(Modifier.height(6.dp))
            Text("玻璃不透明度：${(d.glassAlpha * 100).toInt()}%", fontSize = 13.sp, color = palette.sub)
            Slider(value = d.glassAlpha, onValueChange = { update(d.copy(glassAlpha = it)) }, valueRange = 0.3f..1f)
            Spacer(Modifier.height(4.dp))
            HintText(
                "玻璃背景模糊：${d.glassBlur.toInt()}dp（面板背后做一次真实模糊；内置渐变背景没有细节可模糊，会自动跳过）",
                fontSize = 13.sp,
                color = palette.sub,
            )
            Slider(value = d.glassBlur, onValueChange = { update(d.copy(glassBlur = it)) }, valueRange = 0f..40f)
            Spacer(Modifier.height(4.dp))
            // 设备分级：把「自动」实际判成了哪一档直接写出来，省得猜为什么效果不一样
            val lowRamDevice = remember { isLowRamDevice(context) }
            val effectiveQuality = decideGlassQuality(d.glassQuality, lowRamDevice, Build.VERSION.SDK_INT)
            Text(
                "玻璃效果：${glassQualityLabel(effectiveQuality)}" +
                    if (d.glassQuality == GLASS_QUALITY_AUTO) "　（自动判定）" else "　（手动选的）",
                fontSize = 13.sp,
                color = palette.sub,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GlassPill("自动", d.glassQuality == GLASS_QUALITY_AUTO, Modifier.weight(1f)) {
                    update(d.copy(glassQuality = GLASS_QUALITY_AUTO))
                }
                GlassPill("高（全开）", d.glassQuality == GLASS_QUALITY_HIGH, Modifier.weight(1f)) {
                    update(d.copy(glassQuality = GLASS_QUALITY_HIGH))
                }
                GlassPill("低（省电）", d.glassQuality == GLASS_QUALITY_LOW, Modifier.weight(1f)) {
                    update(d.copy(glassQuality = GLASS_QUALITY_LOW))
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { pickImage.launch(arrayOf("image/*")) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(RadiusR2),
                ) { Text("选择背景图") }
                OutlinedButton(
                    onClick = { update(d.copy(bgUri = "")) },
                    enabled = d.bgUri.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(RadiusR2),
                ) { Text("恢复默认背景") }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (d.bgUri.isNotBlank()) {
                    "已设置自定义背景：玻璃面板背后会对它做真实的背景模糊 + 折射。"
                } else {
                    "当前用的是内置渐变背景（跟随系统明暗自动切换，不是图片）；" +
                        "选一张自己的图就会替换掉它，点「恢复默认背景」可以换回来。"
                },
                fontSize = 13.sp,
                color = palette.sub,
            )
            // 压暗只对自定义背景图开放：内置渐变本身已经调过明度，
            // 再压一层会把配色一起糊掉，所以没选图时这个滑杆根本不出现。
            if (d.bgUri.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("背景压暗：${(d.bgDim * 100).toInt()}%", fontSize = 13.sp, color = palette.sub)
                Slider(value = d.bgDim, onValueChange = { update(d.copy(bgDim = it)) }, valueRange = 0f..0.8f)
            }
            Spacer(Modifier.height(14.dp))
            // 外观现在自己带一个保存按钮：接口那套已经搬进「高级设置」，别再共用一个「保存」了。
            // 滑杆是即时预览的，但**不点这个按钮就不会落盘** —— 红字挨着它。
            if (dirty) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "未保存，你所做出的改动不会被保存",
                        modifier = Modifier.weight(1f),
                        fontSize = 13.sp,
                        color = palette.bad,
                    )
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = { saveAppearance() },
                        modifier = Modifier.height(46.dp),
                        shape = RoundedCornerShape(RadiusR2),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                    ) { Text("保存外观") }
                }
            } else {
                Button(
                    onClick = { saveAppearance() },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(RadiusR2),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                ) { Text("已保存") }
            }
        }

        GlassCard(d.glassAlpha) {
            Text("高级设置", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
            // 三行「现在是什么样」：入口卡原来只有一句名词罗列（接口地址 / 分析 / 备份 / 诊断），
            // 看完仍然不知道当前配成了什么。摘要只**报状态**、不做判断，要改再进去。
            // 三行分别对应高级设置里的「接入 / 微信内 / 识别」三组，找东西时可以对上号。
            Spacer(Modifier.height(6.dp))
            Text(
                "接入：${d.baseUrl.ifBlank { "未填" }}　模型 ${d.model.ifBlank { "未填" }}　" +
                    "${if (d.graded) "模型分级" else "直通"}",
                fontSize = 13.sp,
                color = palette.text,
            )
            Text(
                "微信内：自动分析 ${if (d.enabled) "开" else "关"}　参考 ${d.ctx} 条　间隔 ${d.minIntervalSec}s　" +
                    "白名单 ${if (d.whitelistEnabled) "${d.whitelist.size} 个" else "关"}",
                fontSize = 13.sp,
                color = palette.sub,
            )
            Text(
                "识别：图片文字 ${if (d.ocrEnabled) "开" else "关"}　本地代理 ${if (d.proxyEnabled) "开" else "关"}　" +
                    "归属地查询 ${if (d.geoEnabled) "开" else "关"}",
                fontSize = 13.sp,
                color = palette.sub,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onOpenAdvanced,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(RadiusR2),
                colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
            ) { Text("高级设置") }
        }

        GlassCard(d.glassAlpha) {
            Text("关于", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = palette.text)
            Spacer(Modifier.height(6.dp))
            Text("版本：${appVersion(context)}", fontSize = 13.sp, color = palette.text)
            Spacer(Modifier.height(6.dp))
            HintText(
                "· 开启后，聊天页最近几条消息会发送到你填写的接口地址，请自行确认该服务可信。\n" +
                    "· API Key 以明文存放在本应用私有目录（不能加密：注入微信进程的代码需要跨进程读取，Keystore 密钥按 UID 隔离读不到）。\n" +
                    "· 只读消息、只把候选回复填进输入框，不会自动发送；但仍属于修改微信客户端行为，有风控风险，建议先用小号。\n" +
                    "· 日志关键字：[Goutou]。",
                fontSize = 13.sp,
                color = palette.sub,
            )
        }
    }
}

// ================= 高级设置（设置 → 二级页） =================

/**
 * 高级设置的分组标题：12sp、次级色、上面留 24dp 的呼吸位。
 *
 * 九张卡平铺时是一串并列名词，扫视没有动线；加上「接入 / 生成 / 识别 / 微信内 / 维护与排障」
 * 之后，看到的是五个词而不是九个名词，也更不容易把「要配的」和「排障用的」混在一起。
 * 左对齐到卡片外沿再往右 4dp，跟卡内文字错开半格 —— 看起来是「标题」而不是「正文」。
 */
@Composable
private fun GroupTitle(text: String) {
    val palette = LocalPalette.current
    Text(
        text,
        modifier = Modifier.padding(start = 18.dp, top = 18.dp, bottom = 3.dp),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = palette.sub.copy(alpha = 0.75f),
        letterSpacing = 0.8.sp,
    )
}

/**
 * 来源小标签：一眼看出这份提示词是「内置」的还是「导入 / 自定义」的。
 * 只做染色 + 一个词，不占语义 —— 「现在到底生效的是哪一份」由旁边的名字负责。
 */
@Composable
private fun SourceChip(skillId: String) {
    // 形态统一交给 StatusChip（chip 底色 alpha 0.14 + 1dp 描边 + 13sp 字），别再手搓一份
    StatusChip(if (skillId == "custom") "导入 / 自定义" else "内置", LocalPalette.current.primary)
}

/**
 * 设置 → 高级设置。
 *
 * 从设置首页搬过来的四块：接口地址、微信内自动分析（含敏感检查）、备份 / 迁移、诊断入口。
 * 它们的共同点是「配一次就不常动」，所以单独一层；诊断再往里一层（三级）。
 * 「会话白名单」卡里的候选人名单同理 —— 拉回来的联系人放 [ChatCandidatesScreen]（也是三级）。
 */
@Composable
fun AdvancedScreen(
    store: ConfigStore,
    ui: ConfigData,
    onSaved: () -> Unit,
    onOpenDiag: () -> Unit,
    onOpenCandidates: () -> Unit,
    onBack: () -> Unit,
) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    var d by remember { mutableStateOf(ui) }
    var includeKey by remember { mutableStateOf(true) }
    var backupNote by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // 「拉取模型列表」：拉回来存本机（列表 + 时间），进页面读缓存；手动输入永远保留
    val cachedModels = remember { store.modelList() }
    var models by remember { mutableStateOf(cachedModels.first) }
    var modelsAt by remember { mutableStateOf(cachedModels.second) }
    var modelsOpen by remember { mutableStateOf(false) }
    var modelsNote by remember { mutableStateOf<String?>(null) }
    var pulling by remember { mutableStateOf(false) }
    // 第二套接口（分级模式那一跳）的模型列表：跟第一套各存各的
    val cachedModels2 = remember { store.modelList(second = true) }
    var models2 by remember { mutableStateOf(cachedModels2.first) }
    var models2At by remember { mutableStateOf(cachedModels2.second) }
    var models2Open by remember { mutableStateOf(false) }
    var modelsNote2 by remember { mutableStateOf<String?>(null) }
    var pulling2 by remember { mutableStateOf(false) }
    // 「有没有还没点保存的改动」：拿草稿和**上次落盘的那份**比。
    // 不能只看一个「保存过没有」的标志 —— 它一开始就是「没保存过」，
    // 会把刚打开的页面也说成「未保存」，红字就废了。
    var persisted by remember { mutableStateOf(ui) }
    val dirty = d != persisted

    fun update(next: ConfigData) {
        d = next
    }

    fun saveAll() {
        store.save(d)
        d = store.load()
        persisted = d
        onSaved()
    }

    /** 疑似非对话模型（embedding / tts 这些）排到后面 —— 只排序，不隐藏。 */
    fun orderedModels(list: List<String>): List<String> =
        list.filterNot { ModelList.looksNonChat(it) } + list.filter { ModelList.looksNonChat(it) }

    /**
     * 拉一次模型列表。用的是**当前草稿**里的地址与 Key（可能还没保存）——
     * 「填完 Key 先试一下能不能拉」不用先按保存。失败也不清空手里那份：服务商偶尔抽风。
     */
    fun pullModels(second: Boolean = false) {
        if (second) {
            pulling2 = true
            modelsNote2 = null
        } else {
            pulling = true
            modelsNote = null
        }
        // 第二套留空 = 复用第一套（和「生成模式」里那三格的规矩一致）
        val base = if (second) d.baseUrl2.trim().ifBlank { d.baseUrl } else d.baseUrl
        val key = if (second) d.apiKey2.trim().ifBlank { d.apiKey } else d.apiKey
        scope.launch {
            val got = withContext(Dispatchers.IO) { runCatching { ModelList.fetch(base, key) } }
            got.onSuccess { list ->
                val note = if (list.isEmpty()) "接口通了，但没解析出模型名 —— 手动填吧" else "拉到 ${list.size} 个模型"
                if (second) {
                    models2 = list
                    models2At = System.currentTimeMillis()
                    modelsNote2 = note
                } else {
                    models = list
                    modelsAt = System.currentTimeMillis()
                    modelsNote = note
                }
                store.saveModelList(list, second)
            }.onFailure { t ->
                if (second) modelsNote2 = "拉取失败：${t.message}" else modelsNote = "拉取失败：${t.message}"
            }
            if (second) pulling2 = false else pulling = false
        }
    }

    /**
     * 白名单的开关 / 添加 / 移除都**立刻落盘**（跟「本地代理」那个开关同一个道理）。
     *
     * 这张卡是全页唯一没有自己「保存」按钮的卡 —— 只改草稿 d 的话，注入侧读到的还是旧值，
     * 表现出来就是「白名单明明开了，没加的聊天照样被分析」（用户实测踩到，还以为是缓存）。
     * 只写这两项（`ConfigStore.saveWhitelist`）：这页还有别的卡在编辑、还没点保存，
     * 不能顺手把它们一起定死。二级页也要用同一份写法，所以这件事放在 store 上。
     */
    fun saveWhitelist(enabled: Boolean, chats: Set<String>) {
        store.saveWhitelist(enabled, chats)
        val fresh = store.load()
        // 只把这两项同步回草稿 —— 别的地方可能还有没保存的编辑，不能整份覆盖
        d = d.copy(whitelistEnabled = fresh.whitelistEnabled, whitelist = fresh.whitelist)
        persisted = fresh
        onSaved()
    }

    /**
     * 「图片文字识别」开关也**立刻落盘** —— 和它上面那张「本地代理」卡连着用：
     * 代理开着、识图关着，效果是白装十几 MB；两个开关的行为一致，才不会让人以为坏了。
     */
    fun saveOcr(on: Boolean) {
        store.saveOcr(on)
        val fresh = store.load()
        d = d.copy(ocrEnabled = fresh.ocrEnabled)
        persisted = fresh
        onSaved()
    }

    /**
     * 「生成模式」两个 pill 也**立刻落盘**（原因见 `ConfigStore.saveGraded`）。
     * 只把这一项同步回草稿与「上次落盘的那份」—— 别的卡可能还在编辑、还没点保存，
     * 不能整份覆盖，也不能让假红字亮起来。
     */
    fun saveGraded(on: Boolean) {
        store.saveGraded(on)
        val fresh = store.load()
        d = d.copy(graded = fresh.graded)
        persisted = fresh
        onSaved()
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            backupNote = try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(Backup.export(store.load(), store.roles(), includeKey).toByteArray(Charsets.UTF_8))
                }
                "已导出到所选文件"
            } catch (t: Throwable) {
                "导出失败：${t.message}"
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            backupNote = try {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                val result = Backup.import(text, store.load(), store.roles())
                if (result == null) {
                    "导入失败：不是有效的备份文件"
                } else {
                    store.save(result.config)
                    store.saveRoles(result.roles)
                    d = store.load()
                    persisted = d
                    onSaved()
                    "已导入：配置 + ${result.roleCount} 个角色"
                }
            } catch (t: Throwable) {
                "导入失败：${t.message}"
            }
        }
    }

    var diagNote by remember { mutableStateOf<String?>(null) }
    var proxyNote by remember { mutableStateOf<String?>(null) }
    var ocrNote by remember { mutableStateOf<String?>(null) }
    var whitelistNote by remember { mutableStateOf<String?>(null) }
    // 会话名候选是注入侧用广播回传的，这一页不会自己重组 —— 停在这里时每 1.5 秒自己看一眼。
    // （去微信转一圈再回来，看到的就是刚回传的那份。）
    // 这里的第三项是「图片识别」的最近一次结果 —— 它同样是注入侧广播回传的，
    // 借同一次轮询一起读，省掉第二个定时器。
    //
    // ⚠️ 轮询是**有上限**的（下面 repeat(200)，约 5 分钟）。而典型用法恰好是
    // 「打开这一页看一眼 → 去微信里试 → 切回来」，那往往已经超过 5 分钟了 ——
    // 于是页面还停在旧值上（真机反馈过「明明认了字，App 里还是『还没试过』」就是这个）。
    // 所以每次回到前台（ON_RESUME）都重启一轮。
    val resumed = resumedTick()
    val pulled by produceState(
        initialValue = Triple(store.chatCandidates(), store.chatPullInfo(), store.ocrInfo()),
        key1 = resumed,
    ) {
        // 轮询**有上限**（约 5 分钟）：一个是别让这个页面永远挂着定时任务，
        // 另一个是单元测试里跑渲染回归时，无限循环的协程可能把测试挂住 —— 有界就没有这种风险。
        repeat(200) {
            kotlinx.coroutines.delay(1500)
            value = Triple(store.chatCandidates(), store.chatPullInfo(), store.ocrInfo())
        }
    }
    val cands = pulled.first
    val reqAt = store.chatRequestAt()
    // 拿「我什么时候点的」和「微信侧什么时候回的」比 —— 这两件事分开显示，
    // 才能一眼看出是「请求没送到」还是「送到了但没读出名字」（排查方向完全相反）
    val pullInfo = pulled.second.let { (info, at) ->
        when {
            at > reqAt -> info.ifBlank { "已收到回传" } + " · " + clockText(at)
            reqAt > 0L -> "已请求 ${clockText(reqAt)} · 微信侧还没回过话"
            else -> "还没拉过"
        }
    }
    val ocrStatus = pulled.third.let { (info, at) ->
        val head = info.ifBlank { "还没试过（微信里遇到图片消息才会有记录）" }
        if (at > 0L) "$head · ${clockText(at)}" else head
    }
    var newChat by remember { mutableStateOf("") }
    // 白名单的「长按多选」状态。
    // （候选名单那一份跟着名单一起搬到二级页了，见 ChatCandidatesScreen —— 那边自己持有一份。）
    var whitePicking by remember { mutableStateOf(false) }
    var whitePicked by remember { mutableStateOf(emptySet<String>()) }
    // 被「删掉」的候选个数（记在「已忽略」里）—— 界面靠它显示「恢复」
    var ignoredCount by remember { mutableStateOf(store.chatIgnored().size) }
    val proxyScope = rememberCoroutineScope()
    // 13+ 才有的通知权限。注意：前台服务**没有**它也照样能跑（系统仍会显示这条常驻通知），
    // 但既然要弹，就在用户打开开关时顺手要一下 —— 不然以后那条通知可能被折叠/静音。
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        proxyNote = if (granted) "已授予通知权限" else "没给通知权限 —— 代理照跑，但系统更容易把它回收"
    }
    val diagLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            diagNote = try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(buildDiagZip(context, store, d)) }
                "已导出诊断包 —— 发之前建议先打开看一眼"
            } catch (t: Throwable) {
                "导出失败：${t.message}"
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 120.dp)) {
        ScreenHeader("高级设置", "接口 · 分析 · 备份 · 诊断") {
            HeaderButton("← 返回", onBack)
        }

        // 这一页很长，页底那个红字常常不在视野里 —— 顶部也放一条，滚到哪儿都知道「有东西没存」。
        // 只是提示，不强制：接口地址那种改一半的情况，本来就该由你决定什么时候存。
        if (dirty) {
            Text(
                "⚠ 本页有未保存的改动（白名单 / 识图 / 生成模式是改完立刻生效，其余要按页底那个「保存」）",
                fontSize = 13.sp,
                color = palette.bad,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }

        GroupTitle("接入")

        GlassCard(d.glassAlpha) {
            // 公告栏放最上面：换接口/换模型最该先知道的就是「这俩大概要等多久」
            val (replyMs, riskMs) = store.probeMs()
            NoticeBanner(
                title = "自检延迟（和接口、模型都有关）",
                lines = buildList {
                    add(if (replyMs > 0) "${d.model}（写回复）：${replyMs}ms" else "${d.model}（写回复）：还没测过")
                    if (d.graded) {
                        val m2 = d.model2.ifBlank { d.model }
                        add(if (riskMs > 0) "$m2（风险）：${riskMs}ms" else "$m2（风险）：还没测过")
                        if (replyMs > 0L && riskMs > 0L) {
                            add("两路差 ${kotlin.math.abs(replyMs - riskMs)}ms —— 卡片要等慢的那一路")
                        }
                    }
                    add("换接口或换模型都会让它变；回首页点「接口自检」重新测一次。")
                },
            )
            Spacer(Modifier.height(10.dp))
            Text("接口地址", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                colors = glassFieldColors(),
                shape = RoundedCornerShape(RadiusR2),
                value = d.baseUrl,
                onValueChange = { update(d.copy(baseUrl = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("接口地址（OpenAI 兼容，写到 /v1）") },
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                colors = glassFieldColors(),
                shape = RoundedCornerShape(RadiusR2),
                value = d.apiKey,
                onValueChange = { update(d.copy(apiKey = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                colors = glassFieldColors(),
                shape = RoundedCornerShape(RadiusR2),
                value = d.model,
                onValueChange = { update(d.copy(model = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("模型") },
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { pullModels(second = false) },
                    enabled = !pulling,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(RadiusR2),
                ) { Text(if (pulling) "拉取中…" else "从服务端拉取模型列表") }
                if (models.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Box {
                        OutlinedButton(onClick = { modelsOpen = true }, shape = RoundedCornerShape(RadiusR2)) {
                            Text("选模型（${models.size}）")
                        }
                        DropdownMenu(expanded = modelsOpen, onDismissRequest = { modelsOpen = false }) {
                            orderedModels(models).take(80).forEach { id ->
                                DropdownMenuItem(
                                    text = { Text(id, fontSize = 13.sp) },
                                    onClick = {
                                        update(d.copy(model = id))
                                        modelsOpen = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
            modelsNote?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, fontSize = 13.sp, color = if (it.startsWith("拉取失败")) palette.bad else palette.ok)
            }
            if (modelsAt > 0L) {
                Spacer(Modifier.height(2.dp))
                Text("上次拉取 ${formatTime(modelsAt)}（存在本机；换了接口 / Key 记得重新拉一次）", fontSize = 10.sp, color = palette.sub)
            }
            Spacer(Modifier.height(4.dp))
            HintText(
                "拉不到不代表接口不能用：有的服务商没这个接口，有的中转只回一份目录（列出来 ≠ 你的 Key 能用）。手动填一样用。",
                fontSize = 10.sp,
                color = palette.sub,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("严格 JSON 输出", fontSize = 15.sp, color = palette.text)
                    HintText(
                        "请求里带 response_format=json_object，让服务端保证回的是合法 JSON，" +
                            "能少一些「模型没返回 JSON」。有些中转不支持 —— 开了报错就关掉。" +
                            "只影响生成候选，不影响单条改写和风格提炼。",
                        fontSize = 13.sp,
                        color = palette.sub,
                    )
                }
                Switch(checked = d.jsonMode, onCheckedChange = { update(d.copy(jsonMode = it)) })
            }
            Spacer(Modifier.height(12.dp))
            Text("单次回复的 token 上限", fontSize = 13.sp, color = palette.sub)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GlassPill("200", d.maxTokens == 200, Modifier.weight(1f)) { update(d.copy(maxTokens = 200)) }
                GlassPill("400", d.maxTokens == 400, Modifier.weight(1f)) { update(d.copy(maxTokens = 400)) }
                GlassPill("600", d.maxTokens == 600, Modifier.weight(1f)) { update(d.copy(maxTokens = 600)) }
                GlassPill("无限制", d.maxTokens == 0, Modifier.weight(1f)) { update(d.copy(maxTokens = 0)) }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (d.maxTokens == 0) "无限制：请求里不带 max_tokens，由服务端决定（进阶 skill 建议用这档）"
                else "越小越省额度，但 skill 提示词较长时可能被截断",
                fontSize = 13.sp,
                color = palette.sub,
            )
        }

        GlassCard(d.glassAlpha) {
            Text("本地代理（API Key 不出本应用）", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
            Text(
                "开启后：聊天内容先送到本机的 127.0.0.1，由这个 App 带上 Key 去调你的接口。\n" +
                    "注入到微信里的那段代码从此**拿不到 Key** —— 做法不是加密，是根本不给它。",
                fontSize = 13.sp,
                color = palette.sub,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("启用本地代理", fontSize = 15.sp, color = palette.text)
                    Text(
                        if (d.proxyEnabled) {
                            "已开：App 需要保持运行（前台服务 + 一条常驻通知）"
                        } else {
                            "关着：走直连（Key 明文存在本机，并且会推给微信进程）"
                        },
                        fontSize = 13.sp,
                        color = palette.sub,
                    )
                }
                Switch(
                    checked = d.proxyEnabled,
                    onCheckedChange = { on ->
                        // 开关立刻落盘：注入侧是按配置里的 proxyEnabled 决定走哪条路的，
                        // 等用户再点一次「保存」的话，这里会有一段「看起来开了其实没开」的空窗。
                        store.save(d.copy(proxyEnabled = on))
                        d = store.load()
                        persisted = d
                        if (on) {
                            store.ensureProxyToken()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            ProxyService.start(context)
                            proxyNote = "已开启。到微信里试一次识别；如果一直失败，把这里关掉就退回直连。"
                        } else {
                            ProxyService.stop(context)
                            proxyNote = "已关闭：Key 会重新推给微信进程（下次识别生效）。"
                        }
                    },
                )
            }
            if (d.proxyEnabled) {
                Spacer(Modifier.height(6.dp))
                // 状态行直接读进程内的 ProxyState：服务到底起没起来、为什么没起来，一眼可见
                Text(
                    when {
                        ProxyState.running ->
                            "● 服务在跑：127.0.0.1:${ProxyState.port}" +
                                (ProxyState.lastResult?.let { " · 最近：$it" } ?: " · 还没被请求过")
                        ProxyState.lastError != null -> "● 服务没起来：${ProxyState.lastError}"
                        else -> "● 服务没起来（点上面的开关关掉再开一次；若仍不行点「测试代理」看详情）"
                    },
                    fontSize = 13.sp,
                    color = if (ProxyState.running) palette.ok else palette.bad,
                )
                val (lastRoute, lastRouteAt) = store.lastRoute()
                if (lastRoute.isNotBlank() && lastRouteAt > 0L) {
                    val ok = lastRoute == ProxyProtocol.ROUTE_PROXY
                    Text(
                        "微信侧最近一次走的是：" + (if (ok) "本地代理 ✅" else "直连（代理还没被用上）") +
                            " · " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                                .format(java.util.Date(lastRouteAt)),
                        fontSize = 13.sp,
                        color = if (ok) palette.ok else palette.warn,
                    )
                } else {
                    Text("微信侧还没回传过路线 —— 到微信里点一次「↻ 重新识别」就会有了", fontSize = 13.sp, color = palette.sub)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "提示：识别结果**有缓存**（同一条消息不会重复调接口）。开了代理之后，" +
                        "要对**新消息**点一次「↻ 重新识别」才会真正走代理。",
                    fontSize = 13.sp,
                    color = palette.sub,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    colors = glassFieldColors(),
                    shape = RoundedCornerShape(RadiusR2),
                    value = d.proxyPort.toString(),
                    onValueChange = { update(d.copy(proxyPort = it.toIntOrNull() ?: d.proxyPort)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("端口（1024-65535）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            proxyNote = "测试中…"
                            val port = d.proxyPort
                            proxyScope.launch {
                                proxyNote = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val c = (java.net.URL("http://127.0.0.1:$port${ProxyProtocol.PATH_HEALTH}")
                                            .openConnection() as java.net.HttpURLConnection)
                                        c.connectTimeout = 3_000
                                        c.readTimeout = 3_000
                                        val code = c.responseCode
                                        runCatching { c.disconnect() }
                                        code
                                    }.fold(
                                        { code -> if (code == 200) "代理正常（HTTP 200）" else "代理回话：HTTP $code" },
                                        { err ->
                                            "连不上：${err.message}" + (
                                                ProxyState.lastError?.let { " · 服务端说：$it" }
                                                    ?: " · 服务没起来（把上面的开关关掉再打开一次）"
                                                )
                                        },
                                    )
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(RadiusR2),
                    ) { Text("测试代理") }
                    OutlinedButton(
                        onClick = {
                            store.resetProxyToken()
                            d = store.load()
                            proxyNote = "已换新 token（微信里下次识别生效）"
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(RadiusR2),
                    ) { Text("换新 token") }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "⚠ 微信进程对 http 明文的策略不归我们管：如果开了之后微信里识别一直失败，" +
                        "把这里关掉就退回直连（注入侧给的报错里也会这么提示）。\n" +
                        "重启手机后代理不会自己回来，打开一次 App 就会自动恢复。",
                    fontSize = 13.sp,
                    color = palette.warn,
                )
                Spacer(Modifier.height(4.dp))
                HintText(
                    "如果状态行显示「服务在跑」但过一会儿就没了：这个应用需要常驻，请把它加入系统的" +
                        "「电池优化白名单 / 允许后台运行」（各家名字不同：华为「应用启动管理」、小米「省电策略·无限制」、" +
                        "OPPO / vivo 类似）。",
                    fontSize = 13.sp,
                    color = palette.sub,
                )
            }
            proxyNote?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 13.sp, color = palette.sub)
            }
        }

        GroupTitle("生成")

        GlassCard(d.glassAlpha) {
            Text("生成模式", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GlassPill("直通（一套接口）", !d.graded, Modifier.weight(1f)) { saveGraded(false) }
                GlassPill("模型分级", d.graded, Modifier.weight(1f)) { saveGraded(true) }
            }
            Spacer(Modifier.height(6.dp))
            HintText(
                if (!d.graded) {
                    "直通：所有事都交给一套接口 + 一份提示词，一次调用搞定（默认，和以前一样）。"
                } else {
                    "分级：拆成两路**并行**跑 —— 一路只判意图与风险，另一路只写 3 条回复，最后合成一张卡片。" +
                        "任一路挂了另一路照常显示（风险会标成「未评估」）。"
                },
                fontSize = 13.sp,
                color = palette.sub,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "这两个 pill 点一下就生效（立刻落盘，不用等页底那个「保存」）；下面的接口地址仍然是草稿。",
                fontSize = 13.sp,
                color = palette.ok,
            )
            if (d.graded) {
                Spacer(Modifier.height(10.dp))
                Text("第二套接口（只给「风险评估」那一路用）", fontSize = 13.sp, color = palette.sub)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    colors = glassFieldColors(),
                    shape = RoundedCornerShape(RadiusR2),
                    value = d.baseUrl2,
                    onValueChange = { update(d.copy(baseUrl2 = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("接口地址（留空 = 复用上面那套）") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    colors = glassFieldColors(),
                    shape = RoundedCornerShape(RadiusR2),
                    value = d.apiKey2,
                    onValueChange = { update(d.copy(apiKey2 = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API Key（留空 = 复用）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    colors = glassFieldColors(),
                    shape = RoundedCornerShape(RadiusR2),
                    value = d.model2,
                    onValueChange = { update(d.copy(model2 = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("模型（留空 = 复用）") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { pullModels(second = true) },
                        enabled = !pulling2,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(RadiusR2),
                    ) { Text(if (pulling2) "拉取中…" else "从服务端拉取模型列表（第二套）") }
                    if (models2.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Box {
                            OutlinedButton(onClick = { models2Open = true }, shape = RoundedCornerShape(RadiusR2)) {
                                Text("选模型（${models2.size}）")
                            }
                            DropdownMenu(expanded = models2Open, onDismissRequest = { models2Open = false }) {
                                orderedModels(models2).take(80).forEach { id ->
                                    DropdownMenuItem(
                                        text = { Text(id, fontSize = 13.sp) },
                                        onClick = {
                                            update(d.copy(model2 = id))
                                            models2Open = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                modelsNote2?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, fontSize = 13.sp, color = if (it.startsWith("拉取失败")) palette.bad else palette.ok)
                }
                if (models2At > 0L) {
                    Spacer(Modifier.height(2.dp))
                    Text("上次拉取 ${formatTime(models2At)}（存在本机）", fontSize = 10.sp, color = palette.sub)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "拉取用的是第二套自己的地址 / Key；这两格也留空的话，就跟第一套一模一样。",
                    fontSize = 10.sp,
                    color = palette.sub,
                )
                Spacer(Modifier.height(8.dp))
                GlassPill("同上（三格都清空 = 复用第一套）", false, Modifier.fillMaxWidth()) {
                    update(d.copy(baseUrl2 = "", apiKey2 = "", model2 = ""))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "留空就复用第一套 —— 那等于「同一个模型拆两路提示词」，也完全能用。" +
                        "想省钱可以给风险那一路单独配个便宜的小模型（比如轻量档）。" +
                        "第二套不参与首页的「接口自检」，配错了会在卡片上直接写出来。",
                    fontSize = 13.sp,
                    color = palette.sub,
                )
            }
        }

        GroupTitle("识别")

        GlassCard(d.glassAlpha) {
            Text("图片文字识别（OCR）", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
            Text(
                "聊天里的图片会先在这台手机上认成文字，再和别的消息一起交给模型 —— " +
                    "截图、长图里的话也能被读懂。\n" +
                    "认字完全离线：内置的中文模型，不联网、不下载、不需要 Play 服务，图片也不会上传" +
                    "（只在本机两个进程之间走一趟）。代价是这个安装包大了十几 MB。",
                fontSize = 13.sp,
                color = palette.sub,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("识别聊天里的图片", fontSize = 15.sp, color = palette.text)
                    Text(
                        if (d.ocrEnabled) {
                            "开着：每轮分析前，屏幕上的图会先认一遍字"
                        } else {
                            "关着：图片还是老样子 —— 用 [图片/表情/语音] 占位"
                        },
                        fontSize = 13.sp,
                        color = palette.sub,
                    )
                }
                Switch(
                    checked = d.ocrEnabled,
                    onCheckedChange = { on ->
                        saveOcr(on)
                        ocrNote = if (on) "已开 · 微信里下次识别生效" else "已关 · 图片回落成占位"
                    },
                )
            }
            if (d.ocrEnabled && !d.proxyEnabled) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "⚠ 还差一步：认字这件事在 App 进程里做，所以得先把上面那张卡的「本地代理」打开 —— " +
                        "否则微信那边连不上本机端口，会直接跳过（图片仍是占位）。",
                    fontSize = 13.sp,
                    color = palette.warn,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("最近一次：$ocrStatus", fontSize = 13.sp, color = palette.sub)
            ocrNote?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, fontSize = 13.sp, color = palette.ok)
            }
        }

        GroupTitle("微信内")

        GlassCard(d.glassAlpha) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    colors = glassFieldColors(),
                    shape = RoundedCornerShape(RadiusR2),
                    value = d.ctx.toString(),
                    onValueChange = { update(d.copy(ctx = it.toIntOrNull() ?: d.ctx)) },
                    modifier = Modifier.weight(1f),
                    label = { Text("参考条数 2-20") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    colors = glassFieldColors(),
                    shape = RoundedCornerShape(RadiusR2),
                    value = d.minIntervalSec.toString(),
                    onValueChange = { update(d.copy(minIntervalSec = it.toIntOrNull() ?: d.minIntervalSec)) },
                    modifier = Modifier.weight(1f),
                    label = { Text("最短间隔(s)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                colors = glassFieldColors(),
                shape = RoundedCornerShape(RadiusR2),
                value = d.temperature.toString(),
                onValueChange = { update(d.copy(temperature = it.toDoubleOrNull() ?: d.temperature)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("temperature（0.2 稳 / 0.8 活）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("微信内自动分析", fontSize = 15.sp, color = palette.text)
                    Text("关掉后只在手动点「识别」时出现卡片", fontSize = 13.sp, color = palette.sub)
                }
                Switch(checked = d.enabled, onCheckedChange = { update(d.copy(enabled = it)) })
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("敏感内容检查", fontSize = 15.sp, color = palette.text)
                    Text("命中验证码/银行卡/转账时先拦一次（推荐开）", fontSize = 13.sp, color = palette.sub)
                }
                Switch(checked = !d.allowSensitive, onCheckedChange = { update(d.copy(allowSensitive = !it)) })
            }
            Spacer(Modifier.height(14.dp))
            // 这一页只有这一个保存按钮，而接口地址 / 生成模式 / 参考条数 / temperature 全是草稿 ——
            // 不点它就一概不生效。所以红字必须挨着它，别让人以为改完就已经算数了。
            if (dirty) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "未保存，你所做出的改动不会被保存",
                        modifier = Modifier.weight(1f),
                        fontSize = 13.sp,
                        color = palette.bad,
                    )
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = { saveAll() },
                        modifier = Modifier.height(46.dp),
                        shape = RoundedCornerShape(RadiusR2),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                    ) { Text("保存") }
                }
            } else {
                Button(
                    onClick = { saveAll() },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(RadiusR2),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                ) { Text("已保存（微信里下次识别即生效）") }
            }
        }

        GlassCard(d.glassAlpha) {
            Text("会话白名单", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
            // 白底说明卡放最上面：这一页最容易踩的坑（名字对不上、开关开着名单空着）先说清楚。
            // 白卡和玻璃卡刻意不同 —— 玻璃是界面的一部分，白卡是「贴上去的说明书」。
            WhiteCard {
                Text("怎么用", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1C1B22))
                listOf(
                    "打开开关后，只有名单里的聊天会触发小助手；不在名单里的，不读内容、不记角色、也不调接口。",
                    "加名字三种办法：① 下面的输入框手打；② 点「拉取会话列表」，回微信的首页 / 通讯录停两秒；" +
                        "③ 白名单开着时，打开某个聊天，它的名字会自己进来。",
                    "开关和增删都是改完立刻生效 —— 这张卡没有「保存」按钮。",
                ).forEach { line ->
                    Text(
                        "· $line",
                        fontSize = 13.sp,
                        color = Color(0xFF55525E),
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text("⚠ 注意事项", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFFB3261E))
                listOf(
                    "名字要和微信里显示的一致：群聊填群名；改过备注 / 昵称的，用改过之后的名字。",
                    "认不出会话名的聊天按「没勾」处理 —— 宁可不动，也不误发。",
                    "开关开着、名单却是空的 = 所有聊天都不工作。",
                    "个人资料页不是来源：那一页只有「微信号 / 地区」这类字段，读出来全是杂项。",
                    "拉取时要停在微信的列表页（首页 / 通讯录）；聊天页里读到的是消息正文，不是名字。",
                    "白名单关着时，模块不收集任何会话名（不打算用它的人，不会被攒一份联系人名单）。",
                    "移出白名单只是不再分析它；已经记下的角色和聊天记录不会被删掉。",
                ).forEach { line ->
                    Text(
                        "· $line",
                        fontSize = 13.sp,
                        color = Color(0xFF55525E),
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("只对白名单里的聊天生效", fontSize = 15.sp, color = palette.text)
                    Text(
                        if (d.whitelistEnabled) {
                            "已开：只有下面 ${d.whitelist.size} 个会话会工作"
                        } else {
                            "关着：所有聊天都会分析（默认）"
                        },
                        fontSize = 13.sp,
                        color = palette.sub,
                    )
                }
                Switch(
                    checked = d.whitelistEnabled,
                    onCheckedChange = { on ->
                        saveWhitelist(on, d.whitelist)
                        whitelistNote = if (on) {
                            if (d.whitelist.isEmpty()) "已开，但名单还是空的 —— 等于所有聊天都不工作"
                            else "已开 · 只对下面这几个会话生效"
                        } else {
                            "已关 · 所有聊天都会分析"
                        }
                    },
                )
            }
            if (d.whitelistEnabled) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        colors = glassFieldColors(),
                        shape = RoundedCornerShape(RadiusR2),
                        value = newChat,
                        onValueChange = { newChat = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("写微信里显示的那个名字") },
                        singleLine = true,
                    )
                    Button(
                        onClick = {
                            val k = Roles.normalizeKey(newChat)
                            if (k.isNotBlank()) {
                                saveWhitelist(d.whitelistEnabled, d.whitelist + k)
                                whitelistNote = "已加入「$k」"
                                newChat = ""
                            }
                        },
                        shape = RoundedCornerShape(RadiusR2),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                    ) { Text("添加") }
                }
                if (d.whitelist.isEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "还是空的 —— 开着白名单却一条都没加，等于所有聊天都不工作。",
                        fontSize = 13.sp,
                        color = palette.warn,
                    )
                } else {
                    Spacer(Modifier.height(6.dp))
                    val whiteList = d.whitelist.sorted()
                if (whitePicking) {
                    PickBar(
                        count = whitePicked.size,
                        total = whiteList.size,
                        palette = palette,
                        primaryLabel = "移出白名单",
                        onPrimary = {
                            val gone = whitePicked
                            saveWhitelist(d.whitelistEnabled, d.whitelist - gone)
                            whitelistNote = "已移出 ${gone.size} 个"
                            whitePicked = emptySet()
                            whitePicking = false
                        },
                        dangerLabel = "删除（不再出现）",
                        onDanger = {
                            val gone = whitePicked
                            saveWhitelist(d.whitelistEnabled, d.whitelist - gone)
                            store.ignoreChats(gone)
                            ignoredCount = store.chatIgnored().size
                            whitelistNote = "已删除 ${gone.size} 个，以后拉取不再出现"
                            whitePicked = emptySet()
                            whitePicking = false
                        },
                        onSelectAll = {
                            whitePicked = if (whitePicked.size == whiteList.size) emptySet() else whiteList.toSet()
                        },
                        onCancel = {
                            whitePicked = emptySet()
                            whitePicking = false
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                }
                whiteList.forEach { name ->
                    PickRow(
                        label = name,
                        picking = whitePicking,
                        selected = name in whitePicked,
                        palette = palette,
                        actionLabel = "移除",
                        actionColor = palette.bad,
                        onToggle = {
                            whitePicked = if (name in whitePicked) whitePicked - name else whitePicked + name
                        },
                        onLongPress = {
                            whitePicking = true
                            whitePicked = whitePicked + name
                        },
                        onAction = {
                            saveWhitelist(d.whitelistEnabled, d.whitelist - name)
                            whitelistNote = "已移除「$name」"
                        },
                    )
                }
                Text("长按任意一项可多选，然后一次移出 / 删除。", fontSize = 13.sp, color = palette.sub)
                }
                Spacer(Modifier.height(6.dp))
                HintText(
                    "名字要和微信里显示的一致（群聊填群名；改过备注的用改过的名字）；懒得敲字就用下面的「拉取会话列表」。",
                    fontSize = 13.sp,
                    color = palette.sub,
                )
            }

            Spacer(Modifier.height(12.dp))
            Text("从微信里列出来", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = palette.text)
            HintText(
                "点一下，然后回微信的首页或通讯录停两秒（滚一下能多收几个），再回来打开下面的「拉取到的联系人」去挑。\n" +
                    "白名单开着的时候，你打开过的那个聊天也会自己进来（认的是聊天页顶上那个名字，也就是备注）。\n" +
                    "⚠ 个人资料页不是来源 —— 那一页只有「微信号 / 地区」这种字段，读出来全是杂项，别在那里等。",
                fontSize = 13.sp,
                color = palette.sub,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        store.requestChats()
                        whitelistNote = "已请求 · 回微信首页 / 通讯录停两秒，再回来这里看"
                    },
                    modifier = Modifier.height(44.dp),
                    shape = RoundedCornerShape(RadiusR2),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                ) { Text("拉取会话列表") }
                Text(pullInfo, fontSize = 13.sp, color = palette.sub, modifier = Modifier.weight(1f))
            }
            val todo = cands.filter { it !in d.whitelist }
            Spacer(Modifier.height(10.dp))
            // 名单本身搬到二级页了：拉到几十个名字时铺在卡里会把整页撑得很长，也没法搜。
            // 这里只留一个入口 + 数量。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(RadiusR2))
                    .background(palette.primary.copy(alpha = ChipBgAlpha))
                    .clickable { onOpenCandidates() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("拉取到的联系人（${cands.size}）", fontSize = 14.sp, color = palette.text)
                    Text(
                        when {
                            cands.isEmpty() -> "还没收到候选 —— 点上面的按钮，然后去微信首页 / 通讯录停两秒"
                            todo.isEmpty() -> "拉回来的都加进去了 · 点开可以搜、也可以移出"
                            else -> "还没加进去的 ${todo.size} 个 · 点开可以搜索、批量加入"
                        },
                        fontSize = 13.sp,
                        color = palette.sub,
                    )
                }
                Text("查看 ›", fontSize = 13.sp, color = palette.primary)
            }
            if (ignoredCount > 0) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "已忽略 $ignoredCount 个（删掉的杂项）",
                        fontSize = 13.sp,
                        color = palette.sub,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "恢复",
                        fontSize = 13.sp,
                        color = palette.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(RadiusR1))
                            .clickable {
                                store.clearIgnored()
                                ignoredCount = 0
                                whitelistNote = "已恢复：删掉的候选下次拉取会重新出现"
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            whitelistNote?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 13.sp, color = palette.ok)
            }
        }

        GroupTitle("维护与排障")

        GlassCard(d.glassAlpha) {
            Text("备份 / 迁移", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
            Text(
                "换包名、换手机的时候用：导出成一个 json 文件，装好新的再导入回来。\n" +
                    "导入是合并：角色只覆盖同名的，备份里没有的会保留。",
                fontSize = 13.sp,
                color = palette.sub,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("导出时包含 API Key", fontSize = 13.sp, color = palette.text)
                    Text("开了的话导出文件里有明文 Key，别往公开地方放", fontSize = 13.sp, color = palette.sub)
                }
                Switch(checked = includeKey, onCheckedChange = { includeKey = it })
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { exportLauncher.launch(Backup.FILE_NAME) },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(RadiusR2),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                ) { Text("导出配置") }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(RadiusR2),
                ) { Text("导入配置") }
            }
            backupNote?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 13.sp, color = if (it.startsWith("已")) palette.ok else palette.bad)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { diagLauncher.launch("TalkTact-诊断包.zip") },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(RadiusR2),
            ) { Text("导出诊断包（.zip）") }
            Spacer(Modifier.height(4.dp))
            HintText(
                "出问题时用它：环境 / 配置（不含 Key）/ 角色条数 / 抓到的界面结构 / 最近一次调用。\n" +
                    "⚠️ 里面有你和对方的聊天内容，发出去之前先自己看一眼。",
                fontSize = 13.sp,
                color = palette.sub,
            )
            diagNote?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 13.sp, color = if (it.startsWith("已")) palette.ok else palette.bad)
            }
        }

        GlassCard(d.glassAlpha) {
            Text("诊断", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
            Text(
                "某个聊天页连按钮都不弹、或者「运行状态」报错的时候用这里：\n" +
                    "让微信进程抓一次当前界面、看它真正发出去的那次调用，再复制出来发我。",
                fontSize = 13.sp,
                color = palette.sub,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onOpenDiag,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(RadiusR2),
                colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
            ) { Text("进入诊断") }
        }
    }
}

// ================= 拉取到的联系人（设置 → 高级设置 → 白名单卡的二级页） =================

/**
 * 设置 → 高级设置 → 「拉取到的联系人」。
 *
 * 从微信读回来的会话名（候选）以前直接铺在白名单卡里：一次只列 24 条、也没法搜 ——
 * 拉回来的名字一多，那一块就成了整页最长的一段。所以搬到单独一层：顶上一个搜索框，下面是完整名单。
 *
 * 名单**不因为「加入」而消失**：加进去的那条挪到下面的「已经在白名单里」那一段。
 * 「微信里到底读到了谁」始终是一份看得全的名单，比「加一个少一个」好找。
 */
@Composable
fun ChatCandidatesScreen(
    store: ConfigStore,
    ui: ConfigData,
    onSaved: () -> Unit,
    onBack: () -> Unit,
) {
    val palette = LocalPalette.current
    var d by remember { mutableStateOf(ui) }
    var note by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(emptySet<String>()) }
    var ignoredCount by remember { mutableStateOf(store.chatIgnored().size) }

    // 候选是注入侧用广播回传的，这一页不会自己重组 —— 停在这里时每 1.5 秒自己看一眼。
    // 轮询**有上限**（约 5 分钟），理由和白名单卡那边一样：别留一个永远挂着的定时任务，
    // 也让渲染回归测试不会被一个无限协程挂住。
    val resumed = resumedTick()
    val pulled by produceState(
        initialValue = store.chatCandidates() to store.chatPullInfo(),
        key1 = resumed,
    ) {
        repeat(200) {
            kotlinx.coroutines.delay(1500)
            value = store.chatCandidates() to store.chatPullInfo()
        }
    }
    val cands = pulled.first
    val reqAt = store.chatRequestAt()
    val pullInfo = pulled.second.let { (info, at) ->
        when {
            at > reqAt -> info.ifBlank { "已收到回传" } + " · " + clockText(at)
            reqAt > 0L -> "已请求 ${clockText(reqAt)} · 微信侧还没回过话"
            else -> "还没拉过"
        }
    }

    fun saveWhitelist(enabled: Boolean, chats: Set<String>) {
        store.saveWhitelist(enabled, chats)
        d = store.load()
        // App 层的 ui 也要跟着刷新，否则回上一页看到的是旧名单（再点一次保存还会把它写回去）
        onSaved()
    }

    val all = cands.sorted()
    val todo = all.filter { it !in d.whitelist }
    val added = all.filter { it in d.whitelist }
    val hitTodo = filterNames(todo, query)
    val hitAdded = filterNames(added, query)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            ScreenHeader("拉取到的联系人", "微信里读回来的名单 · 点一下加入白名单") {
                HeaderButton("← 返回", onBack)
            }
        }

        item {
            GlassCard(d.glassAlpha) {
                Text("搜索", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                Text(
                    "拉回来的名字可能有几十个 —— 输一两个字就能筛出来（只筛这一页的名单）。",
                    fontSize = 13.sp,
                    color = palette.sub,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    colors = glassFieldColors(),
                    shape = RoundedCornerShape(RadiusR2),
                    value = query,
                    onValueChange = {
                        query = it
                        // 边筛边选容易错位：一改搜索词就退出多选，重新选
                        if (picking) {
                            picking = false
                            picked = emptySet()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索名字") },
                    singleLine = true,
                )
                if (!d.whitelistEnabled) {
                    Spacer(Modifier.height(10.dp))
                    WhiteCard {
                        Text(
                            "白名单现在是关着的",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFB3261E),
                        )
                        Text(
                            "这里加进去的名字，在开关打开之前都不会生效 —— " +
                                "回上一页把「只对白名单里的聊天生效」打开。",
                            fontSize = 13.sp,
                            color = Color(0xFF55525E),
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            store.requestChats()
                            note = "已请求 · 回微信首页 / 通讯录停两秒，再回来看"
                        },
                        modifier = Modifier.height(44.dp),
                        shape = RoundedCornerShape(RadiusR2),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                    ) { Text("拉取会话列表") }
                    Text(pullInfo, fontSize = 13.sp, color = palette.sub, modifier = Modifier.weight(1f))
                }
                note?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 13.sp, color = palette.ok)
                }
            }
        }

        if (picking) {
            item {
                GlassCard(d.glassAlpha, border = palette.primary.copy(alpha = CardBorderAlpha)) {
                    PickBar(
                        count = picked.size,
                        total = hitTodo.size,
                        palette = palette,
                        primaryLabel = "加入白名单",
                        onPrimary = {
                            val add = picked
                            saveWhitelist(d.whitelistEnabled, d.whitelist + add)
                            store.unignoreChats(add)
                            ignoredCount = store.chatIgnored().size
                            note = "已加入 ${add.size} 个"
                            picked = emptySet()
                            picking = false
                        },
                        dangerLabel = "删除（不再出现）",
                        onDanger = {
                            val gone = picked
                            store.ignoreChats(gone)
                            ignoredCount = store.chatIgnored().size
                            note = "已删除 ${gone.size} 个杂项 —— 以后拉取不会再冒出来"
                            picked = emptySet()
                            picking = false
                        },
                        onSelectAll = {
                            picked = if (picked.size == hitTodo.size) emptySet() else hitTodo.toSet()
                        },
                        onCancel = {
                            picked = emptySet()
                            picking = false
                        },
                    )
                }
            }
        }

        item {
            Text(
                "还没加进去的（${todo.size}）" + if (query.isNotBlank()) " · 筛出 ${hitTodo.size} 个" else "",
                fontSize = 13.sp,
                color = palette.sub,
                modifier = Modifier.padding(start = 30.dp, end = 30.dp, top = 10.dp, bottom = 4.dp),
            )
        }

        if (hitTodo.isEmpty()) {
            item {
                GlassCard(d.glassAlpha) {
                    Text(
                        when {
                            all.isEmpty() -> "这一页还是空的 —— 点上面的「拉取会话列表」，然后回微信首页 / 通讯录停两秒。"
                            query.isNotBlank() -> "没搜到「${query.trim()}」—— 换个字试试，或者清空搜索框。"
                            else -> "拉回来的 ${all.size} 个都已经加进白名单了。"
                        },
                        fontSize = 13.sp,
                        color = palette.sub,
                    )
                }
            }
        }

        items(hitTodo) { name ->
            Box(Modifier.padding(horizontal = 30.dp)) {
                PickRow(
                    label = name,
                    picking = picking,
                    selected = name in picked,
                    palette = palette,
                    actionLabel = "加入",
                    actionColor = palette.primary,
                    onToggle = { picked = if (name in picked) picked - name else picked + name },
                    onLongPress = {
                        picking = true
                        picked = picked + name
                    },
                    onAction = {
                        saveWhitelist(d.whitelistEnabled, d.whitelist + name)
                        store.unignoreChats(setOf(name))
                        ignoredCount = store.chatIgnored().size
                        note = "已加入「$name」"
                    },
                )
            }
        }

        if (hitAdded.isNotEmpty()) {
            item {
                Text(
                    "已经在白名单里的（${added.size}）" + if (query.isNotBlank()) " · 筛出 ${hitAdded.size} 个" else "",
                    fontSize = 13.sp,
                    color = palette.sub,
                    modifier = Modifier.padding(start = 30.dp, end = 30.dp, top = 14.dp, bottom = 4.dp),
                )
            }
            items(hitAdded) { name ->
                Box(Modifier.padding(horizontal = 30.dp)) {
                    PickRow(
                        label = name,
                        picking = false,
                        selected = false,
                        palette = palette,
                        actionLabel = "移出",
                        actionColor = palette.bad,
                        onToggle = {},
                        onLongPress = {},
                        onAction = {
                            saveWhitelist(d.whitelistEnabled, d.whitelist - name)
                            note = "已移出「$name」"
                        },
                    )
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 30.dp, vertical = 10.dp)) {
                Text(
                    "长按任意一项可多选，然后一次加入或删除。删除的杂项会被记下来，下次拉取不再出现。",
                    fontSize = 13.sp,
                    color = palette.sub,
                )
                if (ignoredCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "已忽略 $ignoredCount 个（删掉的杂项）",
                            fontSize = 13.sp,
                            color = palette.sub,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "恢复",
                            fontSize = 13.sp,
                            color = palette.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(RadiusR1))
                                .clickable {
                                    store.clearIgnored()
                                    ignoredCount = 0
                                    note = "已恢复：删掉的候选下次拉取会重新出现"
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

// ================= 诊断（设置 → 高级设置 → 诊断） =================

/**
 * 网络信息（归属地）—— 从「高级设置」沉到「诊断」页顶部。
 *
 * 它是只读的排障信息（自检里那两行省份是从哪儿来的），本质属于网络排障，和接口地址那种
 * 「要你去配的」不是一类东西；放在九张配置卡中间，它是唯一一张「看一眼就行」的卡。
 *
 * 开关与自定义地址都**立刻落盘**（跟白名单 / 识图 / 生成模式同一个道理），没有「保存」按钮 ——
 * 所以这里自己读一份 store，不依赖外面那页的草稿（诊断页本来也没有草稿）。
 */
@Composable
private fun GeoCard(store: ConfigStore, glassAlpha: Float) {
    val palette = LocalPalette.current
    var d by remember { mutableStateOf(store.load()) }
    var persisted by remember { mutableStateOf(d) }

    fun saveGeoNow(enabled: Boolean, endpoint: String) {
        store.saveGeo(enabled, endpoint)
        d = store.load()
        persisted = d
    }

    GlassCard(glassAlpha) {
        Text("网络信息（归属地）", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
        Text(
            "「运行状态 → 接口自检」里那两行省份，是拿 IP 去问第三方库要的 —— 这是整个模块唯一一处" +
                "会把 IP 发给别人的地方。关掉之后自检只显示 IP，一次都不问。",
            fontSize = 13.sp,
            color = palette.sub,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("查询 IP 归属地", fontSize = 15.sp, color = palette.text)
                Text(
                    if (d.geoEnabled) "自检时带上省份（结果缓存 10 分钟）" else "已关闭：不请求任何第三方",
                    fontSize = 13.sp,
                    color = palette.sub,
                )
            }
            Switch(checked = d.geoEnabled, onCheckedChange = { saveGeoNow(it, d.geoEndpoint) })
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            colors = glassFieldColors(),
            shape = RoundedCornerShape(RadiusR2),
            value = d.geoEndpoint,
            onValueChange = { d = d.copy(geoEndpoint = it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("自定义接口地址（留空 = 用内置的）") },
            singleLine = true,
            enabled = d.geoEnabled,
        )
        if (d.geoEnabled && d.geoEndpoint.trim() != persisted.geoEndpoint.trim()) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("地址还没保存", modifier = Modifier.weight(1f), fontSize = 13.sp, color = palette.bad)
                Button(
                    onClick = { saveGeoNow(d.geoEnabled, d.geoEndpoint) },
                    modifier = Modifier.height(42.dp),
                    shape = RoundedCornerShape(RadiusR2),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                ) { Text("保存地址") }
            }
        }
        Spacer(Modifier.height(4.dp))
        HintText(
            "自己填的地址要能返回 JSON（内置用的是 ip.useragentinfo.com）；认不出的字段一律当没有，不会报错。",
            fontSize = 10.sp,
            color = palette.sub,
        )
    }
}

/**
 * 设置 → 高级设置 → 诊断（第三层）。
 *
 * 这三块原来是挤在「运行状态」页上的（抓取界面 / 微信进程的诊断 / 最后一次调用），
 * 把那一页撑得很长，而且它们都是「出问题了才看」的东西，所以搬进设置这条线里。
 * 「返回」回高级设置 —— 它就是从那儿进来的。
 */
@Composable
fun DiagScreen(store: ConfigStore, glassAlpha: Float, onBack: () -> Unit) {
    val palette = LocalPalette.current
    val clipboard = LocalClipboardManager.current
    var tick by remember { mutableStateOf(0) }
    var diagAsked by remember { mutableStateOf(false) }
    val diag = remember(tick) { store.diag() }
    val diagAt = remember(tick) { store.diagAt() }
    val lastCall = remember(tick) { store.lastCall() }
    val lastCallAt = remember(tick) { store.lastCallAt() }
    val trace = remember(tick) { store.trace() }
    val traceAt = remember(tick) { store.traceAt() }
    val context = LocalContext.current
    var exportNote by remember { mutableStateOf("") }
    // 页底的「导出诊断包」：排障时「抓完就能打包发给对方」，不用退回「高级设置 → 备份」去找。
    val diagLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            exportNote = try {
                context.contentResolver
                    .openOutputStream(uri)
                    ?.use { it.write(buildDiagZip(context, store, store.load())) }
                "已导出 —— 发之前建议先打开看一眼（含聊天内容，不含 API Key）"
            } catch (t: Throwable) {
                "导出失败：${t.message}"
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 120.dp)) {
        ScreenHeader("诊断", "抓界面 · 看轨迹与调用 · 复制发我") {
            HeaderButton("↻ 刷新") { tick++ }
            Spacer(Modifier.width(8.dp))
            HeaderButton("← 返回", onBack)
        }

        // 「最近一次调用」提到**页首**：出问题时第一眼看的就是「实际发出去的到底是什么」，
        // 而它原本排在整页最后，滚半天才到 —— 偏偏这是最常被复制的那一段。
        if (lastCall.isNotBlank()) {
            GlassCard(glassAlpha, border = palette.primary.copy(alpha = CardBorderAlpha)) {
                Text("最近一次调用（注入侧真正发出去的）", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                Text(
                    "发生于 ${formatTime(lastCallAt)} · 这里是微信进程实际拿去调接口的那一份，不是本 App 里的配置。" +
                        "核对「当前军师」有没有真的生效，看 system 长度那一行。",
                    fontSize = 13.sp,
                    color = palette.sub,
                )
                Spacer(Modifier.height(8.dp))
                Text(lastCall, fontSize = 10.sp, color = palette.text)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(lastCall)) },
                    shape = RoundedCornerShape(RadiusR2),
                ) { Text("复制这一段") }
            }
        } else {
            GlassCard(glassAlpha) {
                Text("还没有调用记录", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                Text("在微信里生成过一次候选回复之后，那份请求就会留在这里。", fontSize = 13.sp, color = palette.sub)
            }
        }

        // 归属地从「高级设置」沉到这里：只读的排障信息，跟「要配的」分开。
        GeoCard(store, glassAlpha)

        GlassCard(glassAlpha) {
            Text("抓取微信界面", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
            Text(
                "某些聊天页连卡片都不弹时用这个：先在微信里停在那个聊天页 → 切回这里点下面的按钮 → " +
                    "再切回微信（那个页面重新出现就会自动抓）→ 回来点「刷新」→ 复制下面的「诊断」发我。",
                fontSize = 13.sp,
                color = palette.sub,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { store.requestDiag(); diagAsked = true },
                    shape = RoundedCornerShape(RadiusR2),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                ) { Text("抓当前微信界面") }
                if (diagAsked) {
                    Text("已排队，切回微信那一页即抓", fontSize = 13.sp, color = palette.ok)
                }
            }
        }

        if (trace.isNotBlank()) {
            GlassCard(glassAlpha, border = palette.okMark.copy(alpha = CardBorderAlpha)) {
                Text("决策轨迹（每一轮读到什么、停在哪一步）", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                Text(
                    "更新于 ${formatTime(traceAt)} · 卡片不弹时先看它：注入侧每一轮都留一条，" +
                        "扫一眼就知道是卡在「找不到输入框」还是「最后一条是我发的」。它刻意不含聊天正文。",
                    fontSize = 13.sp,
                    color = palette.sub,
                )
                Spacer(Modifier.height(8.dp))
                Text(trace, fontSize = 10.sp, color = palette.text)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(trace)) },
                    shape = RoundedCornerShape(RadiusR2),
                ) { Text("复制轨迹") }
            }
        } else {
            GlassCard(glassAlpha) {
                Text("还没有轨迹", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                Text(
                    "在微信里打开一个聊天页停一会儿，注入侧就会把判定记下来并自动回传；" +
                        "也可以点上面那个「抓当前微信界面」立刻带一份回来。",
                    fontSize = 13.sp,
                    color = palette.sub,
                )
            }
        }

        if (diag.isNotBlank()) {
            GlassCard(glassAlpha, border = palette.warnMark.copy(alpha = CardBorderAlpha)) {
                Text("诊断（来自微信进程）", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                Text(
                    "生成于 ${formatTime(diagAt)} · 排查「读不到消息 / 全是图片」时把它复制给对方",
                    fontSize = 13.sp,
                    color = palette.sub,
                )
                Spacer(Modifier.height(8.dp))
                Text(diag, fontSize = 10.sp, color = palette.text)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(diag)) },
                    shape = RoundedCornerShape(RadiusR2),
                ) { Text("复制诊断") }
            }
        } else {
            GlassCard(glassAlpha) {
                Text("还没有诊断", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                Text("上面那个按钮抓过一次之后，微信进程会把界面结构留在这里。", fontSize = 13.sp, color = palette.sub)
            }
        }

        // 页底固定一个次级导出入口（排障动线：抓完 → 打包 → 发出去）
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = { diagLauncher.launch("TalkTact-诊断包.zip") },
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(RadiusR2),
        ) { Text("导出诊断包（.zip）") }
        if (exportNote.isNotBlank()) {
            Text(exportNote, fontSize = 13.sp, color = palette.sub, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

// ================= 角色 =================

/** 「TA 是你什么人」的备选。用户也可以直接把自定义的写进「平时的关系」里。 */
private val RELATIONS = listOf("家人", "恋人", "暧昧", "朋友", "同学", "同事", "上级", "客户", "其他")

/**
 * 角色页。
 *
 * 列表里每个人 = 一个微信会话名。数据全自动来：模块在你打开某个聊天页时，把它读到的消息
 * 按会话名归档回来（1 小时内重复只留一条）。点进某人可以写「TA 是你什么人」和「平时的关系」，
 * 这两样 + 之前攒下的聊天记录会一起拼进提示词，直接影响当前 skill 生成出来的回复。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RolesScreen(store: ConfigStore, glassAlpha: Float, open: String?, onOpen: (String?) -> Unit) {
    val palette = LocalPalette.current
    var tick by remember { mutableStateOf(0) }
    val roles = remember(tick) { store.roles() }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    val current = open?.let { k -> roles.firstOrNull { it.key == k } }

    if (open != null && current != null) {
        RoleDetail(
            store = store,
            role = current,
            glassAlpha = glassAlpha,
            onBack = {
                onOpen(null)
                tick++
            },
            onRename = { newName ->
                store.renameRole(current.key, newName)
                tick++
            },
            onChanged = { tick++ },
        )
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 120.dp)) {
        ScreenHeader("角色", "每个人一份档案，直接影响生成") {
            HeaderButton("↻ 刷新") { tick++ }
        }

        GlassCard(glassAlpha) {
            Text("这是干什么的", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
            Text(
                "模块会在你打开某个聊天页时，把读到的消息按联系人归档到这里（1 小时内重复的内容只留一条）。\n" +
                    "点进某个人，写上「TA 是你什么人」和「平时的关系」—— 这些会连同之前攒下的聊天记录一起，\n" +
                    "拼进提示词，直接影响「军师」生成出来的回复。\n" +
                    "长按某一项可以直接删除它；点进去可以改名字，\n" +
                        "也能「把另一个角色合并进来」—— 同一个人被记成两条时用那个。",
                fontSize = 13.sp,
                color = palette.sub,
            )
        }

        if (roles.isEmpty()) {
            GlassCard(glassAlpha) {
                Text("还没有记录", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                Text(
                    "去微信里打开几个聊天页，每个停两三秒，再回来点「刷新」。\n" +
                        "（只在聊天页可见时读得到，所以记录是「你在场时看到的那几条」慢慢攒起来的。）",
                    fontSize = 13.sp,
                    color = palette.sub,
                )
            }
        }

        roles.forEach { role ->
            val self = role.key == SELF_ROLE_KEY
            GlassCard(
                glassAlpha,
                // 批 4c：卡片描边只表达「本人 = 绿」这一件事（原来「没写关系」也染一道紫，
                // 紫色当成了「正常」用，跟状态语义打架）。关系没写靠下面那行琥珀文字说。
                border = if (self) palette.okMark.copy(alpha = CardBorderAlpha) else null,
            ) {
                // 卡面差异化（设计师 P1）：「本人」不是「某个联系人」—— 它管的是我自己的说话风格，
                // 是系统条目。给它一条**渐变竖条** + 一枚「系统」chip，一眼就和下面的联系人区分开。
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (self) {
                        Box(
                            Modifier
                                .width(4.dp)
                                .height(52.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Brush.verticalGradient(listOf(palette.primary, palette.okMark))),
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(
                        Modifier.weight(1f).combinedClickable(
                            onClick = { onOpen(role.key) },
                            // 「本人」不给删：它是常驻条目，删了也会立刻回来（见 Roles.withSelf）
                            onLongClick = { if (!self) pendingDelete = role.key },
                        ),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (self) {
                                StatusDot(palette.okMark)
                            }
                            Text(
                                role.name,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (self) palette.ok else palette.text,
                                modifier = Modifier.padding(start = if (self) 6.dp else 0.dp).weight(1f, fill = false),
                            )
                            if (self) {
                                Spacer(Modifier.width(8.dp))
                                StatusChip("系统", palette.okMark)
                            }
                            if (role.renamed) {
                                Text(
                                    " ◦ 识别名 ${role.key.take(10)}",
                                    fontSize = 10.sp,
                                    color = palette.warn,
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                            Text("${role.msgs.size} 条", fontSize = 13.sp, color = palette.sub)
                        }
                        Text(
                            when {
                                self && store.selfStyleEnabled() && store.selfSkill().isNotBlank() ->
                                    "说话风格 skill：已开启 · ${formatTime(store.selfSkillAt())} 生成"
                                self && store.selfStyleEnabled() -> "说话风格 skill：已开启（还没生成）"
                                self -> "说话风格 skill：未开启（点进去打开）"
                                role.relation.isBlank() -> "还没写 TA 是你什么人"
                                else -> "${role.relation}${if (role.note.isBlank()) "" else " · ${role.note.take(18)}"}"
                            },
                            fontSize = 13.sp,
                            color = when {
                                self -> palette.ok
                                role.relation.isBlank() -> palette.warn
                                // 批 4c：紫色只表示「选中 / 主操作」，不承担「正常」—— 普通档案用次要文字色
                                else -> palette.sub
                            },
                        )
                        Text(
                            "最近一条：${if (role.lastAt > 0) formatTime(role.lastAt) else "—"}",
                            fontSize = 13.sp,
                            color = palette.sub,
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { key ->
        val shown = roles.firstOrNull { it.key == key }?.name ?: key
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除角色「$shown」？") },
            text = { Text("档案和记录一起删掉，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    store.removeRole(key)
                    pendingDelete = null
                    tick++
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}


@Composable
private fun RoleDetail(
    store: ConfigStore,
    role: Role,
    glassAlpha: Float,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onChanged: () -> Unit,
) {
    // 「本人」这条没有「TA 是你什么人」，它管的是另一件事（我的说话风格），单独一页
    if (role.key == SELF_ROLE_KEY) {
        SelfStyleDetail(store, glassAlpha)
        return
    }
    val palette = LocalPalette.current
    var relation by remember(role.key) { mutableStateOf(role.relation) }
    var note by remember(role.key) { mutableStateOf(role.note) }
    // 跟「外观」「高级设置」「军师」同一套判据：草稿和上次落盘的那份比。
    // 用本地快照而不是拿 role 再比一次 —— 上面刷新列表的时机不确定，靠 role 会出现「保存完红字还亮着」。
    var persisted by remember(role.key) { mutableStateOf(role.relation to role.note) }
    val dirty = (relation to note) != persisted
    var confirm by remember { mutableStateOf<String?>(null) }
    var renameOpen by remember { mutableStateOf(false) }
    var draft by remember(role.key) { mutableStateOf(role.name) }
    // 合并：先选「把谁并进来」，再确认一次（被并掉的那个角色会消失，不可撤销）
    var mergeOpen by remember { mutableStateOf(false) }
    var pendingMerge by remember { mutableStateOf<Role?>(null) }
    var mergedNote by remember(role.key) { mutableStateOf<String?>(null) }
    var listTick by remember { mutableStateOf(0) }
    // 候选：除自己以外的普通角色。「本人」不参与合并 —— 它装的是「我自己说过的话」，
    // 混进别人的话会直接污染说话风格 skill
    val mergeCandidates = remember(role.key, listTick) {
        store.roles().filterNot { it.key == role.key || it.key == SELF_ROLE_KEY }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 120.dp)) {
        ScreenHeader(role.name, "档案 · ${role.msgs.size} 条记录") {
            TextButton(onClick = { renameOpen = true }) { Text("改名字") }
            Spacer(Modifier.width(8.dp))
            HeaderButton("← 返回", onBack)
        }

        GlassCard(glassAlpha) {
            Text("TA 是你什么人", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
            if (role.renamed) {
                Text(
                    "（识别到的会话名是「${role.key}」，它负责匹配、不会被改动，所以改了名字以后消息还是记到这一条）",
                    fontSize = 13.sp,
                    color = palette.sub,
                )
            }
            RELATIONS.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { r ->
                        GlassPill(r, relation == r, Modifier.weight(1f)) {
                            relation = if (relation == r) "" else r
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                colors = glassFieldColors(),
                shape = RoundedCornerShape(RadiusR2),
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                label = { Text("平时的关系（越具体越有用）") },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "例：同一个组的后端，说话直接，最近在催我 review；上次帮他带过饭。",
                fontSize = 13.sp,
                color = palette.sub,
            )
            Spacer(Modifier.height(10.dp))
            if (dirty) {
                Text("未保存，你所做出的改动不会被保存", fontSize = 13.sp, color = palette.bad)
                Spacer(Modifier.height(6.dp))
            }
            Button(
                onClick = {
                    store.setRoleProfile(role.key, relation, note)
                    persisted = relation to note
                    // 顺手刷新外层列表：原来这里没通知，保存完返回还显示旧档案
                    onChanged()
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(RadiusR2),
                colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
            ) { Text(if (dirty) "保存" else "已保存") }
        }

        GlassCard(glassAlpha) {
            Text("记下来的聊天（${role.msgs.size} 条）", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
            Text(
                "时间是我「看到」它的时间，不是微信里那条消息的真实时间 —— 微信不给这条信息，" +
                    "而模块只在聊天页可见时读得到。生成回复时会带上最近 20 条（屏幕上已有的不再重复）。",
                fontSize = 13.sp,
                color = palette.sub,
            )
            Spacer(Modifier.height(6.dp))
            if (role.msgs.isEmpty()) {
                Text("（还没有）", fontSize = 13.sp, color = palette.sub)
            } else {
                var lastShown = 0L
                role.msgs.takeLast(60).forEach { m ->
                    if (m.at - lastShown > 10 * 60_000L) {
                        lastShown = m.at
                        Text(formatTime(m.at), fontSize = 10.sp, color = palette.sub, modifier = Modifier.padding(top = 6.dp))
                    }
                    Text(
                        "${if (m.fromMe) "我" else "对方"}：${m.text}",
                        fontSize = 13.sp,
                        color = if (m.fromMe) palette.sub else palette.text,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { mergeOpen = true },
                enabled = mergeCandidates.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(RadiusR2),
            ) { Text("把另一个角色合并进来") }
            Spacer(Modifier.height(4.dp))
            Text(
                if (mergeCandidates.isEmpty()) {
                    "还没有别的角色可以合并。"
                } else {
                    "同一个人被记成两条时用这个：选一个角色，把它的记录并进这一条（按时间排好）。"
                },
                fontSize = 13.sp,
                color = palette.sub,
            )
            mergedNote?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 13.sp, color = palette.ok)
            }
        }

        // 危险操作独立成一张卡（设计师 P1）：它们原来挤在「记下来的聊天」卡底部、
        // 和「合并进来」只隔一行，而这两个都**不可撤销** —— 误触的代价太大，不该混在浏览区里。
        GlassCard(glassAlpha, border = palette.badMark.copy(alpha = CardBorderAlpha)) {
            Text("危险操作", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.bad)
            Text(
                "下面两个都不能撤销：清空只删记下来的聊天（改名与档案保留）；" +
                    "删除会把这条角色整个删掉（记录 + 档案一起）。",
                fontSize = 13.sp,
                color = palette.sub,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { confirm = "clear" },
                    enabled = role.msgs.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(RadiusR2),
                ) { Text("清空记录") }
                OutlinedButton(
                    onClick = { confirm = "remove" },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(RadiusR2),
                ) { Text("删除角色") }
            }
        }
    }

    if (renameOpen) {
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("给这个角色改个名字") },
            text = {
                Column {
                    OutlinedTextField(
                        colors = glassFieldColors(),
                        shape = RoundedCornerShape(RadiusR2),
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("显示名字") },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "这里只改显示。识别到的会话名「${role.key}」不变 —— 所以改完名字，" +
                            "以后这个会话的消息还是记到同一条上，不会分家。",
                        fontSize = 13.sp,
                        color = palette.sub,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(draft)
                    renameOpen = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameOpen = false }) { Text("取消") } },
        )
    }

    if (mergeOpen) {
        AlertDialog(
            onDismissRequest = { mergeOpen = false },
            title = { Text("把哪个角色合并进来？") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "合并后：两边记录按时间排好留在「${role.name}」，被并的那个角色会消失。",
                        fontSize = 13.sp,
                        color = palette.sub,
                    )
                    Spacer(Modifier.height(8.dp))
                    mergeCandidates.forEach { o ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clip(RoundedCornerShape(RadiusR2))
                                .clickable {
                                    mergeOpen = false
                                    pendingMerge = o
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(o.name, fontSize = 14.sp, color = palette.text)
                            Text(
                                "${o.msgs.size} 条记录" +
                                    if (o.relation.isNotBlank()) " · ${o.relation}" else "",
                                fontSize = 13.sp,
                                color = palette.sub,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { mergeOpen = false }) { Text("取消") } },
        )
    }

    pendingMerge?.let { src ->
        AlertDialog(
            onDismissRequest = { pendingMerge = null },
            title = { Text("把「${src.name}」合并进来？") },
            text = {
                Text(
                    "「${src.name}」的 ${src.msgs.size} 条记录会并进「${role.name}」，按时间重新排好；" +
                        "合并完「${src.name}」这个角色就没了，无法撤销。\n\n" +
                        if (role.relation.isBlank() && (src.relation.isNotBlank() || src.note.isNotBlank())) {
                            "这边的「TA 是你什么人 / 平时的关系」还空着，会用它的补上。"
                        } else {
                            "「TA 是你什么人 / 平时的关系」保留这边的。"
                        },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    store.mergeRoles(src.key, role.key)
                    pendingMerge = null
                    listTick++
                    mergedNote = "已把「${src.name}」并进来"
                    onChanged()
                }) { Text("合并") }
            },
            dismissButton = { TextButton(onClick = { pendingMerge = null }) { Text("取消") } },
        )
    }

    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (what == "clear") "清空聊天记录？" else "删除这个角色？") },
            text = {
                Text(
                    if (what == "clear") "只清掉记录，「TA 是你什么人 / 平时的关系」会保留。"
                    else "档案和记录一起删掉，无法恢复。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (what == "clear") store.clearRoleMsgs(role.key) else store.removeRole(role.key)
                    confirm = null
                    onBack()
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } },
        )
    }
}


/**
 * 「本人」这条的详情页。
 *
 * 和其它角色最大的不同：这里没有「TA 是你什么人 / 平时的关系」，只有一个开关 +
 * 现在的状态 + 手动生成。因为这条线的产出不是"关系描述"，而是一份**说话风格档案**。
 */
@Composable
private fun SelfStyleDetail(store: ConfigStore, glassAlpha: Float) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val on = remember(tick) { store.selfStyleEnabled() }
    var hour by remember(tick) { mutableStateOf(store.selfStyleHour()) }
    val samples = remember(tick) { StyleSkill.count(store.selfSamples()) }
    val skill = remember(tick) { store.selfSkill() }
    val skillAt = remember(tick) { store.selfSkillAt() }

    LazyColumn(Modifier.fillMaxSize()) {
        item { ScreenHeader("本人", "我的说话风格") }

        item {
            GlassCard(glassAlpha) {
                Text("这是干什么的", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                Text(
                    "把「你自己发出去的话」攒起来，每天在你设定的时间交给模型提炼成一份说话风格档案。\n" +
                        "「军师」生成候选回复时会参考它 —— 回复就会更像你平时说话的样子。\n\n" +
                        "关掉之后：不再采集、不再生成，也不再使用（已经生成的那份留着，重新打开立刻可用）；\n" +
                        "已经攒下的原始记录会被清掉。\n" +
                        "样本只在你打开聊天页时才读得到，所以是「你在场时看到的那几句」慢慢攒起来的。",
                    fontSize = 13.sp,
                    color = palette.sub,
                )
            }
        }

        item {
            GlassCard(glassAlpha, border = if (on) palette.okMark.copy(alpha = CardBorderAlpha) else null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "把我的说话风格做成 skill",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.text,
                        )
                        Text(
                            if (on) "已开启：会采集我说的话" else "未开启：不采集、不生成、不存储",
                            fontSize = 13.sp,
                            color = if (on) palette.ok else palette.sub,
                        )
                    }
                    GlassPill(if (on) "已开启" else "开启", selected = on) {
                        val next = !on
                        store.setSelfStyleEnabled(next)
                        // 开关和定时任务是绑在一起的：开了才排任务，关了立刻撤掉
                        if (next) SelfStyle.schedule(context, hour) else SelfStyle.cancel(context)
                        msg = null
                        tick++
                    }
                }
            }
        }

        item {
            GlassCard(glassAlpha) {
                Text(
                    "每天什么时候生成",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.text,
                )
                Text(
                    "现在是 ${"$hour".padStart(2, '0')}:00 —— 0 点到 23 点里挑都行。" +
                        if (on) "改完立刻生效（按新的时间重新排）。" else "打开上面的开关后按这个时间跑。",
                    fontSize = 13.sp,
                    color = palette.sub,
                )
                Slider(
                    value = hour.toFloat(),
                    onValueChange = {
                        val h = it.roundToInt().coerceIn(0, 23)
                        if (h != hour) {
                            hour = h
                            store.setSelfStyleHour(h)
                        }
                    },
                    // 改完再重排：拖动过程中每变一格都去动 WorkManager 太浪费
                    onValueChangeFinished = { if (on) SelfStyle.schedule(context, hour) },
                    valueRange = 0f..23f,
                    steps = 22,
                )
            }
        }

        item {
            GlassCard(glassAlpha) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCell("$samples", "条样本", Modifier.weight(1f))
                    StatCell(if (skillAt > 0) formatTime(skillAt) else "—", "上次生成", Modifier.weight(1f))
                }
                if (skill.isBlank()) {
                    HintText(
                        "还没有生成过。攒够 ${StyleSkill.MIN_SAMPLES} 条样本之后，可以点下面手动生成一次（不用等到中午）。",
                        fontSize = 13.sp,
                        color = palette.sub,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                } else {
                    Text(
                        "当前 skill（生成回复时会带上）",
                        fontSize = 13.sp,
                        color = palette.sub,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(
                        skill,
                        fontSize = 13.sp,
                        color = palette.text,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(RadiusR2))
                            .background(palette.glassTint.copy(alpha = 0.10f))
                            .padding(10.dp),
                    )
                }
                msg?.let {
                    Text(it, fontSize = 13.sp, color = palette.primary, modifier = Modifier.padding(top = 8.dp))
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassPill(if (busy) "生成中…" else "立即生成", selected = false) {
                        if (busy) return@GlassPill
                        busy = true
                        msg = null
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                runCatching { SelfStyle.generateNow(store) }
                            }
                            busy = false
                            msg = r.fold({ it }, { it.message ?: "生成失败" })
                            tick++
                        }
                    }
                    if (samples > 0) {
                        GlassPill("清空样本", selected = false) {
                            store.clearRoleMsgs(SELF_ROLE_KEY)
                            msg = "已清空采集到的样本"
                            tick++
                        }
                    }
                }
            }
        }

        // 底部导航是浮在上面的，留出空位免得被它挡住
        item { Spacer(Modifier.height(110.dp)) }
    }
}

/**
 * 组装诊断包。
 *
 * 刻意**不含 API Key** —— 诊断包是要发给别人的，Key 不该跟着走。
 * 里面的 04 / 05 会带聊天内容，所以说明文件（00）里明确提醒了「发之前先看一眼」。
 */
private fun buildDiagZip(context: Context, store: ConfigStore, cfg: ConfigData): ByteArray {
    val roles = store.roles()
    val (calls, tokens) = store.usage()
    val lowRam = isLowRamDevice(context)
    val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date())
    return DiagExport.zip(
        linkedMapOf(
            "00-说明.txt" to DiagExport.readme(appVersion(context)),
            "01-环境.txt" to buildString {
                append("App 版本：${appVersion(context)}\n")
                append("Android：SDK ${Build.VERSION.SDK_INT}（${Build.VERSION.RELEASE}）\n")
                append("机型：${Build.MANUFACTURER} ${Build.MODEL}\n")
                append("低内存设备：$lowRam\n")
                append("玻璃效果：设置=${cfg.glassQuality} → 实际=${decideGlassQuality(cfg.glassQuality, lowRam, Build.VERSION.SDK_INT)}\n")
                append("导出时间：$stamp\n")
            },
            "02-配置.txt" to buildString {
                append("接口地址：${cfg.baseUrl}\n")
                append("模型：${cfg.model}\n")
                append("生成模式：")
                append(
                    if (cfg.graded) {
                        "模型分级（第二套：${cfg.baseUrl2.ifBlank { "复用第一套" }} / ${cfg.model2.ifBlank { "复用" }}）"
                    } else {
                        "直通"
                    },
                )
                append("\n参考条数：${cfg.ctx}　最短间隔：${cfg.minIntervalSec}s　temperature：${cfg.temperature}　max_tokens：${cfg.maxTokens}\n")
                append("严格 JSON 输出：${cfg.jsonMode}　敏感内容检查：${!cfg.allowSensitive}\n")
                append("当前 skill：${cfg.skillId}　提示词 ${cfg.prompt.length} 字　含 JSON 契约：${cfg.prompt.contains("replies")}\n")
                // 识图排不进问题时最需要这两行：开关到底开没开、和「本地代理」有没有配套
                val (ocrLine, ocrAt) = store.ocrInfo()
                val ocrWhen = if (ocrAt > 0L) {
                    " · " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(ocrAt))
                } else {
                    ""
                }
                append("图片文字识别：${if (cfg.ocrEnabled) "开" else "关"}　本地代理：${if (cfg.proxyEnabled) "开" else "关"}\n")
                append("最近一次识图：${ocrLine.ifBlank { "（还没有记录）" }}$ocrWhen\n")
                append("API Key：已省略（不导出）\n")
            },
            "03-角色.txt" to buildString {
                append("角色数：${roles.size}\n")
                roles.forEach {
                    append("· ${it.name}（key=${it.key}）关系=${it.relation.ifBlank { "-" }} 记录=${it.msgs.size} 条\n")
                }
            },
            "04-诊断.txt" to store.diag().ifBlank { "（还没有诊断数据：到「诊断」页点一次「抓取微信界面」）" },
            "05-最近一次调用.txt" to store.lastCall().ifBlank { "（还没有调用记录）" },
            "06-用量.txt" to "调用次数：$calls\n累计 token：$tokens\n",
            "07-决策轨迹.txt" to store.trace().ifBlank {
                "（还没有轨迹：在微信里打开一个聊天页停一会儿，轨迹会自动回传）"
            },
        ),
    )
}

/**
 * 「回到前台」的信号：每次 ON_RESUME 自增，给 [produceState] 当重启键。
 *
 * 为什么需要：页面上的那些「注入侧广播回传」的状态（会话名候选、识图结果）都是靠
 * **有上限的轮询**刷新的（5 分钟就停了）。而典型用法是「打开看一眼 → 去微信里试 → 切回来」，
 * 那时候轮询早就停了，页面会一直显示旧值 —— 真机反馈过
 * 「微信里明明认了字，App 里还是『还没试过』」，就是它。
 */
@Composable
private fun resumedTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    return tick
}

/**
 * 「贴在界面上的纸条」：白底 + 深色字、完全不透明，扫一眼就能读到，不会和背景混在一起。
 *
 * 和玻璃卡片刻意不同 —— 玻璃是界面的一部分，白卡是**说明书 / 公告**：说明、注意事项、
 * 延迟数字这类「要看清」的东西用它；能塞进玻璃卡的普通内容就别用，不然整页会花。
 */
@Composable
fun WhiteCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RadiusR2))
            .background(Color.White)
            .padding(12.dp),
        content = content,
    )
}

/** 白底公告栏：一行标题 + 若干行小字（[WhiteCard] 的一种用法）。 */
@Composable
fun NoticeBanner(title: String, lines: List<String>) {
    WhiteCard {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1C1B22))
        lines.forEach { line ->
            Text(
                line,
                fontSize = 13.sp,
                color = Color(0xFF55525E),
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

/** 时间戳 -> 本地 HH:mm（只用在「上次拉取于 …」这种一句话里）。 */
/**
 * 白名单 / 候选共用的一行：不在多选态时右边是一个文字动作；**长按**任意一项进入多选。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PickRow(
    label: String,
    picking: Boolean,
    selected: Boolean,
    palette: Palette,
    actionLabel: String,
    actionColor: Color,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
    onAction: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RadiusR1))
            .combinedClickable(
                onClick = { if (picking) onToggle() },
                onLongClick = onLongPress,
            ),
    ) {
        if (picking) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggle() },
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(2.dp))
        }
        Text(
            label,
            fontSize = 13.sp,
            color = palette.text,
            modifier = Modifier.weight(1f),
        )
        if (!picking) {
            Text(
                actionLabel,
                fontSize = 13.sp,
                color = actionColor,
                modifier = Modifier
                    .clip(RoundedCornerShape(RadiusR1))
                    .clickable { onAction() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** 多选态的顶部：已选几个 / 全选 / 退出多选，外加两个批量动作。 */
@Composable
private fun PickBar(
    count: Int,
    total: Int,
    palette: Palette,
    primaryLabel: String,
    onPrimary: () -> Unit,
    dangerLabel: String,
    onDanger: () -> Unit,
    onSelectAll: () -> Unit,
    onCancel: () -> Unit,
) {
    Text("已选 $count / $total（长按进入多选，点一下勾选）", fontSize = 13.sp, color = palette.text)
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (total > 0 && count >= total) "取消全选" else "全选",
            fontSize = 13.sp,
            color = palette.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(RadiusR1))
                .clickable { onSelectAll() }
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
        Text(
            "退出多选",
            fontSize = 13.sp,
            color = palette.sub,
            modifier = Modifier
                .clip(RoundedCornerShape(RadiusR1))
                .clickable { onCancel() }
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = onPrimary,
            enabled = count > 0,
            modifier = Modifier.weight(1f).height(42.dp),
            shape = RoundedCornerShape(RadiusR2),
            colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
        ) { Text(primaryLabel, fontSize = 13.sp) }
        OutlinedButton(
            onClick = onDanger,
            enabled = count > 0,
            modifier = Modifier.weight(1f).height(42.dp),
            shape = RoundedCornerShape(RadiusR2),
        ) { Text(dangerLabel, fontSize = 13.sp, color = palette.bad) }
    }
}

private fun clockText(at: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at))
