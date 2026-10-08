package dev.goutou.wingman.ui

import android.app.ActivityManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.net.Uri
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.goutou.wingman.ModuleStatus
import dev.goutou.wingman.config.ConfigData
import dev.goutou.wingman.config.RemoteSync
import dev.goutou.wingman.config.ConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 配色 + 玻璃参数。
 *
 * 「液态玻璃」= 面板后面压一块**真正被模糊过的背景**（Modifier.blur 走 RenderEffect）。
 * 因为 minSdk = 31（Android 12），这里不再需要低版本降级分支。
 */
// ================= 设计 Token =================

/**
 * 圆角四档（界面改造批 4b，设计师规范）。
 *
 * **嵌套规则：内层 = 外层 − 6dp** —— 卡片里再套一个圆角块时别用同一个值，
 * 否则两层圆角一样大，看起来是「套娃」而不是「嵌进去」。
 */
val RadiusR1 = 10.dp   // chip / 筛选胶囊 / 小按钮
val RadiusR2 = 16.dp   // 输入框 / 列表行卡 / 普通按钮
val RadiusR3 = 24.dp   // 标准卡片
val RadiusR4 = 32.dp   // 顶部大面板 / 底部面板顶角

/**
 * 状态色令牌（界面改造批 4c）。
 *
 * 语义固定：绿 = 正常/安全，琥珀 = 注意，红 = 危险，紫 = 选中/主操作。
 * **状态色只允许出现在这四种形态里**：
 * ① 8dp 状态点（[StatusDot]）；② 13sp 文字；③ chip（[StatusChip]：底色 alpha [ChipBgAlpha] + 1dp 描边）；
 * ④ 1dp 描边（卡片 / chip，alpha [CardBorderAlpha]）。
 *
 * 两条附加规矩：一屏最多一处「大面积」状态色（运行状态那张 hero 卡的空心圈，其余一律走上面四种形态）；
 * **紫不承担「正常」语义** —— 正常 / 生效一律用绿，说不清就用 [Palette.sub]。
 * 「颜色 + 文案」双通道：状态色永远跟一句说清楚的文字一起出现，不许只靠颜色区分。
 */
val StatusDotSize = 8.dp
/** chip / 小标签的底色透明度（状态色或选中色 + 这一层 alpha 铺在玻璃上）。 */
/** 低配档的描边透明度（设计师 P2）：那一档没有模糊 / 折射衬托，bevel 梯度读不出来，改用均匀描边。 */
const val LOW_TIER_EDGE_ALPHA = 0.16f
const val ChipBgAlpha = 0.14f
/** 状态色 / 选中色的 1dp 描边透明度（批 4c 之前是 0.45 / 0.5 / 0.55 三档混用）。 */
const val CardBorderAlpha = 0.35f

/**
 * 「玻璃强度」(`glassAlpha`) 只调**透**、不调**厚**（第 7 版玻璃重构）。
 *
 * 以前填充 / 描边 / 顶边 / 底边 / 扫光**全都乘**它，滑到最低档卡片就变成一张纸。
 * 现在分三种走法：填充按 ±0.24 摆动且留底、描边衰减到 60% 就不再掉、
 * 厚度（顶边 / 底边 / 阴影）**完全不乘**。
 */
fun fillAlpha(base: Float, g: Float): Float = (base + (g - 0.5f) * 0.24f).coerceIn(0f, 1f)
fun edgeAlpha(base: Float, g: Float): Float = base * (0.6f + 0.4f * g)
fun sweepAlpha(base: Float, g: Float): Float = base * (0.3f + 0.7f * g)

/**
 * 输入框统一样式：半透明填充 + 淡紫描边 + 聚焦加亮。
 *
 * 以前一个字段都没设 `colors` → 全走 Material3 默认（容器透明），落在近白的卡片上
 * 就是一块白板（设计师点名的「白板第二大来源」）。数值见规范：浅色填充白 0.55 / 深色白 0.08。
 */
@Composable
fun glassFieldColors(): TextFieldColors {
    val palette = LocalPalette.current
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = palette.fieldFill,
        unfocusedContainerColor = palette.fieldFill,
        focusedBorderColor = if (palette.dark) {
            Color.White.copy(alpha = 0.40f)
        } else {
            palette.primary.copy(alpha = 0.70f)
        },
        unfocusedBorderColor = palette.fieldBorder,
        focusedTextColor = palette.text,
        unfocusedTextColor = palette.text,
        focusedLabelColor = palette.primary,
        unfocusedLabelColor = palette.sub,
        cursorColor = palette.primary,
    )
}

data class Palette(
    val primary: Color,
    val soft: Color,
    val text: Color,
    val sub: Color,
    val ok: Color,
    val warn: Color,
    val bad: Color,
    /**
     * 状态色的**图形档**（8dp 点 / chip 底色与描边 / 卡片描边 / hero 那个圈）。
     *
     * 为什么不复用 [ok]/[warn]/[bad]：浅色下同一支颜色当**文字**时对比度不够
     * （实测 ok 2.7:1、warn 2.2:1，都低于 AA 的 4.5:1），所以拆两档 ——
     * 上面那三个是**文字档**（更深），这三个是**图形档**（稍亮，配 8dp 点与 1dp 描边）。
     */
    val okMark: Color,
    val warnMark: Color,
    val badMark: Color,
    val bgTop: Color,
    /** 底色渐变的中间那一段。三段比两段更像「光」，不会是平涂。 */
    val bgMid: Color,
    val bgBottom: Color,
    /** 左上角那团光晕：整块背景的「光源」，玻璃面板的高光顺着它来。alpha = 0 就是不要。 */
    val bgGlow: Color,
    val bgGlowAlpha: Float,
    /** 上下两端的暗角：中间亮、两头沉，长列表滚到底也不飘。 */
    val bgVignette: Color,
    val bgVignetteAlpha: Float,
    /** 星尘噪点：给纯渐变补一点颗粒，不然大屏上会有塑料感。 */
    val bgGrain: Color,
    val bgGrainAlpha: Float,
    /** 微网格线：极淡，远看是织物纹理，近看才看得出是格子。只有最高档位才画。 */
    val bgLine: Color,
    val bgLineAlpha: Float,
    val glass: Color,
    /**
     * 玻璃面板的**染色**用色：两种明暗都是白。
     *
     * 低 alpha 的白才是「玻璃反光」；而 [glass] 是给 Material surface 用的**实色**
     * （深色下是深灰 #2A2734）—— 拿它按 0.7 铺满整张卡，卡片就变成一块不透明的板，
     * 这正是「一圈玻璃包着一块板」读感的来源。
     */
    val glassTint: Color,
    val glassBorder: Color,

    // ---- L1 主面板：填充 / 描边 / 厚度（第 7 版玻璃重构，数值全部来自设计师规范）----
    /**
     * 填充的上下两端颜色。浅色是**紫灰**（比背景更深、更紫 = 雾面亚克力），
     * 深色是「带紫的白」—— 而不是「在近白底上再提白」（那正是白板读感的根因）。
     */
    val glassFillTop: Color,
    val glassFillBottom: Color,
    val glassFillTopAlpha: Float,
    val glassFillBottomAlpha: Float,
    /** 描边三停：左上高光 / 中段极淡 / **右下折射暗边**（有色；白→白读不出厚度）。 */
    val glassEdgeHi: Color,
    val glassEdgeHiAlpha: Float,
    val glassEdgeMidAlpha: Float,
    val glassEdgeLow: Color,
    val glassEdgeLowAlpha: Float,
    /** 顶边光源（厚度之一，**不乘 glassAlpha**）：1.5dp 白。 */
    val glassTopEdgeAlpha: Float,
    val glassTopEdgeDp: Float,
    /** 底边反光 / 暗边（厚度之二，也不乘 glassAlpha）。 */
    val glassBottomEdge: Color,
    val glassBottomEdgeAlpha: Float,
    /** 镜面扫光的基准 alpha（按 sweepAlpha(g) 走）。 */
    val glassSweepAlpha: Float,
    /** 外阴影（厚度之三，不乘 glassAlpha —— 乘了滑到最低档卡片会像贴在纸上）。 */
    val glassShadow: Color,
    val glassShadowAlpha: Float,
    val glassShadowDp: Float,
    /** 输入框：半透明填充 + 淡描边（以前用 M3 默认，落在近白卡上就是一块白板）。 */
    val fieldFill: Color,
    val fieldBorder: Color,
    val dark: Boolean,
)

private val LightPalette = Palette(
    primary = Color(0xFF7C3AED),
    soft = Color(0xFFEDE7FA),
    text = Color(0xFF17161D),
    sub = Color(0xFF6B6878),
    // 文字档：比原来深一档（原来是 #1FA463 / #D9910A / #D64545，浅紫底上只有 2~3:1）
    ok = Color(0xFF0E6E43),
    warn = Color(0xFF7A4F00),
    bad = Color(0xFFB32828),
    okMark = Color(0xFF178A52),
    warnMark = Color(0xFFB07400),
    badMark = Color(0xFFC93B3B),
    // 背景整体加深加饱和：玻璃是「后面有起伏」才成立的，近白的底 + 白填充 = 一块白板
    bgTop = Color(0xFFF2ECFC),
    bgMid = Color(0xFFE2D7F7),
    bgBottom = Color(0xFFD6C7F0),
    bgGlow = Color(0xFF8B5CF6),
    bgGlowAlpha = 0.20f,
    bgVignette = Color(0xFF7C5AC0),
    bgVignetteAlpha = 0.06f,
    bgGrain = Color(0xFFFFFFFF),
    bgGrainAlpha = 0.050f,
    bgLine = Color(0xFF8B5CF6),
    bgLineAlpha = 0.035f,
    glass = Color(0xFFFFFFFF),
    glassTint = Color(0xFFFFFFFF),
    glassBorder = Color(0xB3FFFFFF),
    // L1：紫晶玻璃（**比背景更暗更紫**）。数值按实测背景校准：
    //   #C7B6F6 @0.50 铺在 (229,220,248) 上 = (214,201,247) → 比背景暗 5.0%、B−R 由 +19 升到 +33
    //   #B7A1E6 @0.50 铺在 (211,195,238) 上 = (197,178,234) → 暗 5.4%、更紫；卡内自上而下约 8% 明暗过渡
    glassFillTop = Color(0xFFC7B6F6),
    glassFillBottom = Color(0xFFB7A1E6),
    // 基准值按「默认玻璃强度（0.92）时正好落到设计师给的 0.50」标定 —— 见 fillAlpha()
    glassFillTopAlpha = 0.40f,
    glassFillBottomAlpha = 0.40f,
    glassEdgeHi = Color(0xFFFFFFFF),
    glassEdgeHiAlpha = 0.60f,
    glassEdgeMidAlpha = 0.16f,
    // 右下折射暗边：新填充变暗后，旧紫边比卡面还亮（会翻成「亮边」），所以压暗半档
    glassEdgeLow = Color(0xFFA98FE0),
    glassEdgeLowAlpha = 0.32f,
    glassTopEdgeAlpha = 0.60f,
    glassTopEdgeDp = 1.5f,
    glassBottomEdge = Color(0xFF8E6FD6),
    glassBottomEdgeAlpha = 0.18f,
    glassSweepAlpha = 0.10f,
    glassShadow = Color(0xFF4C2C8C),
    glassShadowAlpha = 0.14f,
    glassShadowDp = 18f,
    fieldFill = Color(0x8CFFFFFF),
    fieldBorder = Color(0x47A58BD8),
    dark = false,
)

private val DarkPalette = Palette(
    primary = Color(0xFFB79CFF),
    soft = Color(0xFF322A4D),
    text = Color(0xFFF2EFFA),
    sub = Color(0xFFA9A4BA),
    // 深色下三支颜色两档都用同一支（实测对比度 4.6~5.8:1，都够）
    ok = Color(0xFF4FD296),
    warn = Color(0xFFF0B95B),
    bad = Color(0xFFFF8A8A),
    okMark = Color(0xFF4FD296),
    warnMark = Color(0xFFF0B95B),
    badMark = Color(0xFFFF8A8A),
    bgTop = Color(0xFF221439),
    bgMid = Color(0xFF2B1B4D),
    bgBottom = Color(0xFF1B1230),
    bgGlow = Color(0xFF7C3AED),
    bgGlowAlpha = 0.22f,
    bgVignette = Color(0xFF000000),
    bgVignetteAlpha = 0.28f,
    bgGrain = Color(0xFFFFFFFF),
    bgGrainAlpha = 0.022f,
    bgLine = Color(0xFFFFFFFF),
    bgLineAlpha = 0.028f,
    glass = Color(0xFF2A2734),
    glassTint = Color(0xFFFFFFFF),
    glassBorder = Color(0x33FFFFFF),
    // 深色：上白下「带紫的白」—— 白 0.16 在深紫底上发灰，带紫才是有色玻璃
    glassFillTop = Color(0xFFFFFFFF),
    glassFillBottom = Color(0xFFB79CF0),
    glassFillTopAlpha = 0.13f,
    glassFillBottomAlpha = 0.06f,
    glassEdgeHi = Color(0xFFFFFFFF),
    glassEdgeHiAlpha = 0.55f,
    glassEdgeMidAlpha = 0.10f,
    glassEdgeLow = Color(0xFFC9B4FF),
    glassEdgeLowAlpha = 0.26f,
    glassTopEdgeAlpha = 0.55f,
    glassTopEdgeDp = 1.5f,
    glassBottomEdge = Color(0xFF000000),
    glassBottomEdgeAlpha = 0.22f,
    glassSweepAlpha = 0.085f,
    glassShadow = Color(0xFF0B0616),
    glassShadowAlpha = 0.45f,
    glassShadowDp = 24f,
    fieldFill = Color(0x14FFFFFF),
    fieldBorder = Color(0x24FFFFFF),
    dark = true,
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

/**
 * 背景内容。图片在这里解码**一次**，所有玻璃面板共用，
 * 免得每个面板各自 produceState 重新解码一遍。
 */
data class Backdrop(
    /** 用户自选的背景图；为空 = 用程序化渐变（内置背景）。 */
    val bitmap: ImageBitmap?,
    /** 只对自定义背景图生效的压暗强度。 */
    val dim: Float,
    val bgTop: Color,
    val bgMid: Color,
    val bgBottom: Color,
    val bgGlow: Color,
    val bgGlowAlpha: Float,
    val bgVignette: Color,
    val bgVignetteAlpha: Float,
    val bgGrain: Color,
    val bgGrainAlpha: Float,
    val bgLine: Color,
    val bgLineAlpha: Float,
    val primary: Color,
    /** 玻璃面板背后的模糊半径（dp） */
    val blur: Dp,
)

/**
 * 把「当前配色 + 用户设置」拼成一份 [Backdrop] —— **全仓唯一一处映射**。
 *
 * 以前同样一组背景色写了三份（Palette / Backdrop 的构造处 / 这里的默认值），
 * 改一处忘两处是迟早的事，索性都收进这个函数。
 */
fun backdropOf(palette: Palette, bitmap: ImageBitmap?, dim: Float, blur: Dp) = Backdrop(
    bitmap = bitmap,
    dim = dim,
    bgTop = palette.bgTop,
    bgMid = palette.bgMid,
    bgBottom = palette.bgBottom,
    bgGlow = palette.bgGlow,
    bgGlowAlpha = palette.bgGlowAlpha,
    bgVignette = palette.bgVignette,
    bgVignetteAlpha = palette.bgVignetteAlpha,
    bgGrain = palette.bgGrain,
    bgGrainAlpha = palette.bgGrainAlpha,
    bgLine = palette.bgLine,
    bgLineAlpha = palette.bgLineAlpha,
    primary = palette.primary,
    blur = blur,
)

val LocalBackdrop = staticCompositionLocalOf { backdropOf(LightPalette, null, 0f, 24.dp) }

/** 根容器尺寸（px）。玻璃面板靠它把整屏背景平移对齐到自己身上。 */
val LocalRootSize = staticCompositionLocalOf { IntSize.Zero }

/**
 * 玻璃扫光的相位（0..1 一轮）。
 *
 * 单独做成一个持有者、而不是塞进 [Backdrop]：动画推进时会让读到它的组件重组，
 * 而 [Backdrop] 是所有玻璃面板都在读的 —— 那样每帧都会重组整屏。
 * 装在这里，配合「只在 draw 阶段读取」的写法，推进相位只会重画，不会重组。
 *
 * 镜面扫光与折射共用同一个相位，所以它们是同一个时钟，不会各动各的。
 */
private class GlassPhase {
    var phase by mutableFloatStateOf(0f)
}

private val LocalGlassPhase = staticCompositionLocalOf { GlassPhase() }

/**
 * 当前生效的玻璃档位（折射 / 模糊 / 扫光 各开不开）。
 *
 * 由 [App] 按「设置里的选择 + 设备能力」算一次后提供；玻璃面板只读它，不各自去问系统 ——
 * 读 `isLowRamDevice` 有成本，而且设置页要能显示「自动 = 实际判成了哪一档」。
 */
val LocalGlassQuality = staticCompositionLocalOf { GlassQuality.HIGH }

/**
 * 低内存设备（`ActivityManager.isLowRamDevice`）。拿不到就按「不是」处理 ——
 * 宁可多开点效果，也别因为一个查询失败把所有人降级。
 */
internal fun isLowRamDevice(context: Context): Boolean = runCatching {
    (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
}.getOrDefault(false)

/**
 * 一支 AGSL 折射着色器（对应 liquidGL 的 refraction + aberration + bevel）。
 *
 * 只说一句实话：这是整次改造里**唯一没法在这儿验证**的部分 —— 着色器是运行时编译的，
 * 编译不过只会在真机上抛异常。所以全都包了 try/catch：任何一步出问题都返回 null，
 * 上层自动退回「模糊 / 只染色」，最坏情况只是没有折射，不会崩。
 *
 * 需要 API 33+（RuntimeShader）。minSdk 是 31，31/32 直接走降级。
 */
private const val AGSL_GLASS = """
uniform shader uBackdrop;
uniform float2 uSize;
uniform float uRadius;
uniform float uStrength;
uniform float uAberration;

half4 main(float2 fragCoord) {
    float2 c = uSize * 0.5;
    float2 p = fragCoord - c;
    float m = min(uSize.x, uSize.y);
    float r = min(uRadius, m * 0.5);
    // 圆角矩形 SDF：面板内部为负、边缘为 0、外部为正
    float2 q = abs(p) - c + float2(r, r);
    float sd = min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0, 0.0))) - r;
    // t: 边缘 0 → 内部 1
    float t = clamp(-sd / (m * 0.24), 0.0, 1.0);
    // 近似外法线；加个极小量避免正中心取到 0 向量
    float2 n = normalize(p + float2(0.0001, 0.0001));
    // 越靠边位移越大 —— 斜面折射就是这个平方衰减
    float k = (1.0 - t) * (1.0 - t) * uStrength * m;
    float2 uv = fragCoord - n * k;
    // 色散：R / B 往两边错开一点点采样
    float ab = uAberration * m * (1.0 - t);
    half3 col;
    col.r = uBackdrop.eval(uv + n * ab).r;
    col.g = uBackdrop.eval(uv).g;
    col.b = uBackdrop.eval(uv - n * ab).b;
    // 斜面上一圈冷白高光
    float rim = pow(1.0 - t, 5.0);
    col += half3(0.55, 0.60, 0.75) * rim * 0.30;
    return half4(col, 1.0);
}
"""

private class AgslGlass {
    private val shader: RuntimeShader? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try { RuntimeShader(AGSL_GLASS) } catch (t: Throwable) { null }
        } else null

    /** 尺寸 → RenderEffect 的缓存：尺寸不变的场景下不用反复创建。 */
    private val cache = HashMap<Long, RenderEffect?>()

    val available: Boolean get() = shader != null

    fun effect(w: Int, h: Int): RenderEffect? {
        val sh = shader ?: return null
        if (w <= 0 || h <= 0) return null
        return cache.getOrPut((w.toLong() shl 32) or h.toLong()) {
            try {
                sh.setFloatUniform("uSize", w.toFloat(), h.toFloat())
                sh.setFloatUniform("uRadius", min(w, h) * 0.30f)
                // 3.2% 短边：看得出边缘弯折，又不至于把背景图案揉变形
                sh.setFloatUniform("uStrength", 0.032f)
                sh.setFloatUniform("uAberration", 0.006f)
                RenderEffect.createRuntimeShaderEffect(sh, "uBackdrop")
            } catch (t: Throwable) { null }
        }
    }
}

/**
 * 主题。
 *
 * [dark] 留空 = 跟随系统（正式路径）；显式传值只有两个用途：截图回归要深色 / 浅色各出一张，
 * 以及 Compose Preview 想钉住某一套配色 —— 都比改 Robolectric 限定符干净。
 */
@Composable
fun GoutouTheme(dark: Boolean? = null, content: @Composable () -> Unit) {
    val isDark = dark ?: isSystemInDarkTheme()
    val palette = if (isDark) DarkPalette else LightPalette
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            colorScheme = if (isDark) {
                darkColorScheme(primary = palette.primary, background = palette.bgTop, surface = palette.glass)
            } else {
                lightColorScheme(primary = palette.primary, background = palette.bgTop, surface = palette.glass)
            },
            content = content,
        )
    }
}

// ================= 背景绘制 =================

/**
 * 把「整屏背景」画一遍。
 *
 * 调用方负责先把坐标系平移到目标区域（面板）再裁剪，所以同一个函数既能画最底层背景，
 * 也能画出「面板背后那块背景」—— 后者再套一层折射 / 模糊就是真实的玻璃。
 *
 * 默认背景**不再是一张图**，而是程序化画出来的：底色渐变 + 左上光晕 + 上下暗角 +
 * 星尘噪点 + 微网格。这么换掉有三个好处：
 * - 安装包不再背着几百 KB 的大图，也不用每次进前台解码一遍；
 * - 每一层都是矢量绘制，平板 / 折叠屏上不会糊；
 * - 「面板背后做一次真实模糊」这条最贵的路径会自然短路（见 [GlassSurface]）——
 *   纯渐变没有细节可模糊，跳过整整一次离屏渲染。
 *
 * 用户在设置里选了自己的图就以他的图为准，装饰层会被盖住，索性不画。
 */
// internal 而不是 private：截图回归要**直接**调它。
// 走 BackgroundLayer 不行 —— 那里面有一圈 withFrameNanos 的扫光，Compose 测试会永远等不到 idle。
internal fun DrawScope.drawBackdropArt(
    b: Backdrop,
    w: Float,
    h: Float,
    quality: GlassQuality = GlassQuality.HIGH,
    /**
     * 0..1 的动画相位：只用来让左上角那团光晕**缓慢漂移**（设计师 P2：动效按档启用）。
     * 默认 0 = 完全静止 —— 中/低档位、截图回归（`ScreenRenderTest`）以及面板里那份背景副本
     * 都走这个默认值，所以测试与低配机不会因为动画而抖动。
     */
    drift: Float = 0f,
) {
    // ① 底色渐变（斜向）：永远画。图还没解码完、或者用户选了张很亮的图时，兜住文字对比度。
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(b.bgTop, b.bgMid, b.bgBottom),
            start = Offset(w * 0.18f, 0f),
            end = Offset(w * 0.82f, h),
        ),
        topLeft = Offset.Zero,
        size = Size(w, h),
    )

    val img = b.bitmap
    if (img != null) {
        // 用户自选的背景图：贴上去就完事。
        // 等价于 ContentScale.Crop：按「铺满」的比例缩放后居中
        val scale = max(w / img.width.toFloat(), h / img.height.toFloat())
        val dw = (img.width * scale).roundToInt()
        val dh = (img.height * scale).roundToInt()
        drawImage(
            image = img,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(img.width, img.height),
            dstOffset = IntOffset(((w - dw) / 2f).roundToInt(), ((h - dh) / 2f).roundToInt()),
            dstSize = IntSize(dw, dh),
        )
        // 压暗只对自定义图片开放：内置渐变本身已经调过明度，再压一层会连同配色一起糊掉
        if (b.dim > 0f) {
            drawRect(color = Color.Black.copy(alpha = b.dim), topLeft = Offset.Zero, size = Size(w, h))
        }
        return
    }

    // ② 左上角光晕 —— 整块背景的光源。
    //    动效按档启用（设计师 P2）：只有**最高档位**才让它漂移，幅度很小（±3% 宽 / ±2% 高）——
    //    是「呼吸」，不是「晃」。中/低档与截图回归传 drift = 0，完全静止。
    if (b.bgGlowAlpha > 0f) {
        val swing = if (quality == GlassQuality.HIGH) (drift * 2f - 1f) else 0f
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(b.bgGlow.copy(alpha = b.bgGlowAlpha), Color.Transparent),
                center = Offset(w * 0.72f + swing * w * 0.03f, h * 0.12f + swing * h * 0.02f),
                // 半径 0.9W：光晕要「铺得开」才给玻璃一点可透的明暗起伏（原来 1.10W 太平）
                radius = max(w * 0.90f, h * 0.45f),
            ),
            topLeft = Offset.Zero,
            size = Size(w, h),
        )
    }

    // ③ 上下暗角
    if (b.bgVignetteAlpha > 0f) {
        val v = b.bgVignette.copy(alpha = b.bgVignetteAlpha)
        drawRect(
            brush = Brush.verticalGradient(
                0.00f to v,
                0.26f to Color.Transparent,
                0.74f to Color.Transparent,
                1.00f to v,
            ),
            topLeft = Offset.Zero,
            size = Size(w, h),
        )
    }

    // 低档位到此为止：噪点和网格都算「细节」，弱机上省掉
    if (quality == GlassQuality.LOW) return

    // ④ 星尘噪点。
    //    位置用的是**由尺寸算出来的确定性伪随机**：同一块屏幕永远画同一批点，
    //    切页 / 重绘时不会闪，也就不需要 remember 一份点表。
    if (b.bgGrainAlpha > 0f && w > 0f && h > 0f) {
        var seed = ((w.toInt() * 31) xor (h.toInt() * 17)) or 1
        fun next(): Float {
            seed = seed * 1103515245 + 12345
            return ((seed ushr 9) and 0x7FFF) / 32767f
        }
        val count = (w * h / 26_000f).toInt().coerceIn(48, 220)
        val baseR = 0.55.dp.toPx()
        val spanR = 1.20.dp.toPx()
        repeat(count) {
            val x = next() * w
            val y = next() * h
            val t = next()
            drawCircle(
                color = b.bgGrain.copy(alpha = b.bgGrainAlpha * (0.35f + 0.65f * t)),
                radius = baseR + t * spanR,
                center = Offset(x, y),
            )
        }
    }

    // ⑤ 微网格：只有最高档位才画。一格 34dp、颜色极淡。
    if (quality == GlassQuality.HIGH && b.bgLineAlpha > 0f) {
        val step = 34.dp.toPx()
        if (step > 0f) {
            val color = b.bgLine.copy(alpha = b.bgLineAlpha)
            val stroke = 1.dp.toPx().coerceAtLeast(1f)
            var x = 0f
            while (x <= w) {
                drawLine(color, Offset(x, 0f), Offset(x, h), stroke)
                x += step
            }
            var y = 0f
            while (y <= h) {
                drawLine(color, Offset(0f, y), Offset(w, y), stroke)
                y += step
            }
        }
    }
}

/**
 * 玻璃扫光的节奏（镜面扫光与折射共用同一个相位）。
 *
 * 想彻底关掉这层动画（比如觉得费电）把 [GLASS_SWEEP_ENABLED] 改成 false 就行 ——
 * 玻璃的折射、高光、描边都还在，只是不再缓缓移动。
 */
private const val GLASS_SWEEP_ENABLED = true

/** 一道扫光走完整屏的时长。够慢才是「流动」，短了就成了「闪烁」。 */
private const val GLASS_SWEEP_PERIOD_MS = 26_000L

/** 最短重绘间隔：扫光变化很慢，没必要跑满 60fps，省一半的电。 */
private const val GLASS_SWEEP_MIN_FRAME_MS = 32L

/**
 * 背景层。
 *
 * 背景只有一张图（内置默认图，或者用户自选的那张），本身是静态的。这里唯一要做的事，
 * 是推进「玻璃扫光」的相位：
 * - 动画值只在**绘制阶段**读取（`drawBehind` 里的 `phase`），所以每帧只重画，
 *   **不会触发重组**，玻璃面板不会被拖着一起重组。
 * - 图还没解码完时只画底色渐变，不闪白也不闪黑。
 */
@Composable
fun BackgroundLayer(backdrop: Backdrop) {
    val root = LocalRootSize.current
    val phase = LocalGlassPhase.current
    val quality = LocalGlassQuality.current

    // 低档位连相位都不推：省掉每帧的动画开销
    if (GLASS_SWEEP_ENABLED && quality != GlassQuality.LOW) {
        LaunchedEffect(Unit) {
            var last = 0L
            while (true) {
                val now = withFrameNanos { it }
                if (now - last < GLASS_SWEEP_MIN_FRAME_MS * 1_000_000L) continue
                last = now
                phase.phase = (now / 1_000_000L % GLASS_SWEEP_PERIOD_MS) / GLASS_SWEEP_PERIOD_MS.toFloat()
            }
        }
    }

    Spacer(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val w = if (root.width > 0) root.width.toFloat() else size.width
                val h = if (root.height > 0) root.height.toFloat() else size.height
                drawBackdropArt(backdrop, w, h, quality, drift = phase.phase)
            },
    )
}

private fun decodeImage(context: Context, uriStr: String): ImageBitmap? = try {
    val uri = Uri.parse(uriStr)
    context.contentResolver.openInputStream(uri)?.use { input ->
        val options = BitmapFactory.Options().apply { inSampleSize = 2 }
        BitmapFactory.decodeStream(input, null, options)?.asImageBitmap()
    }
} catch (t: Throwable) {
    null
}

/**
 * 背景参数：把当前配色 + 用户设置拼成一份 [Backdrop]。
 *
 * 单独抽出来是因为**截图回归也要渲染同一份背景** —— 两个主题各出一张图，
 * 免得测试里自己拼一套、跟线上跑的不是同一个东西。
 */
@Composable
fun rememberBackdrop(bgUri: String, dim: Float, blur: Float): Backdrop {
    val palette = LocalPalette.current
    return backdropOf(palette, decodeBackdrop(bgUri), dim, blur.dp)
}

@Composable
private fun decodeBackdrop(uriStr: String): ImageBitmap? {
    val context = LocalContext.current
    // 解码放到 IO 线程，避免切页时卡一下
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uriStr) {
        // 没选自定义背景 → null，交给程序化渐变去画（这是默认路径）
        value = if (uriStr.isBlank()) null else withContext(Dispatchers.IO) { decodeImage(context, uriStr) }
    }
    return bitmap
}

// ================= 玻璃组件 =================

/**
 * 真·液态玻璃。
 *
 * 面板内容分四层，从下往上：
 *   ① 被模糊的真实背景 —— 把整屏背景按 -面板位置 平移进来，裁剪成面板形状，再 blur
 *   ② 玻璃染色（半透明白/黑 + 顶部高光）
 *   ③ 面板内容
 *   ④ 1px 亮边
 *
 * 关键点：模糊层的尺寸只有**面板那么大**（不是整屏），所以代价和面板面积成正比。
 * 默认渐变背景没有细节可模糊，这时直接跳过 ①（省一次离屏渲染）。
 */
@Composable
fun GlassSurface(
    shape: Shape,
    glassAlpha: Float,
    modifier: Modifier = Modifier,
    borderColor: Color? = null,
    /** R4 大面板（底栏那种「一整块板」）：填充更实、只留一条顶边光、不要全圈描边。 */
    big: Boolean = false,
    tintTop: Float? = null,
    tintBottom: Float? = null,
    refract: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = LocalPalette.current
    val backdrop = LocalBackdrop.current
    val root = LocalRootSize.current
    val phase = LocalGlassPhase.current
    val quality = LocalGlassQuality.current
    var pos by remember { mutableStateOf(Offset.Zero) }
    // 面板自身的像素尺寸：折射着色器要按它算 SDF 和折射位移。
    // 用布局实测值而不是 graphicsLayer 作用域里的 size —— 后者的类型随版本变，
    // 实测值既明确又一定是 px。
    var panel by remember { mutableStateOf(IntSize.Zero) }
    // L2（卡内子块）自己传 tintTop/tintBottom，沿用白色染色（且调用方已经乘过 glassAlpha）；
    // L1 主面板走新的紫灰填充，并按 fillAlpha 的公式随「玻璃强度」微调。
    val l2 = tintTop != null || tintBottom != null
    // 低配档补偿（设计师 P2）：低档不模糊、不折射、不扫光，只剩一层染色，卡片会读成「薄」。
    // 补偿三件事 —— 填充 +0.04、描边固定 0.16、去掉投影（最后一件在 [GlassCard] 里）。
    val lowComp = quality == GlassQuality.LOW
    val boost = (if (big) 0.08f else 0f) + (if (lowComp && !l2) 0.04f else 0f)
    val top = if (l2) (tintTop ?: palette.glassFillTopAlpha) else fillAlpha(palette.glassFillTopAlpha + boost, glassAlpha)
    val bottom = if (l2) (tintBottom ?: palette.glassFillBottomAlpha) else fillAlpha(palette.glassFillBottomAlpha + boost, glassAlpha)
    val fillTop = if (l2) palette.glassTint else palette.glassFillTop
    val fillBottom = if (l2) palette.glassTint else palette.glassFillBottom
    val sized = root.width > 0 && root.height > 0
    // 低档位连模糊都不做，只留半透明染色（弱机上 RenderEffect 的离屏模糊很贵）
    val hasImage = sized && backdrop.bitmap != null && backdrop.blur > 0.dp && quality != GlassQuality.LOW
    val glass = remember { AgslGlass() }
    // 折射只在「背景里有东西可折 + 设备撑得住」时才做
    val wantsRefraction = refract && hasImage && quality == GlassQuality.HIGH

    Box(
        modifier
            .onGloballyPositioned {
                pos = it.positionInRoot()
                panel = it.size
            }
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        if (wantsRefraction && glass.available) {
            // ①a 真折射：先把「这一格背后的背景」画进图层，再让 AGSL 按边缘斜面把它折一下
            Spacer(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        renderEffect = glass.effect(panel.width, panel.height)?.asComposeRenderEffect()
                    }
                    .clipToBounds()
                    .drawBehind {
                        withTransform({ translate(-pos.x, -pos.y) }) {
                            drawBackdropArt(backdrop, root.width.toFloat(), root.height.toFloat(), quality)
                        }
                    },
            )
        } else if (hasImage) {
            // ①b 降级路径：API < 33 或着色器编译失败时，退回原来的模糊
            Spacer(
                Modifier
                    .matchParentSize()
                    .blur(backdrop.blur)
                    .clipToBounds()
                    .drawBehind {
                        withTransform({ translate(-pos.x, -pos.y) }) {
                            drawBackdropArt(backdrop, root.width.toFloat(), root.height.toFloat(), quality)
                        }
                    },
            )
        }
        // ② 玻璃染色（L1 = 紫灰雾面 / L2 = 白）
        Spacer(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(listOf(fillTop.copy(alpha = top), fillBottom.copy(alpha = bottom))),
            ),
        )
        // ③ 镜面扫光（liquidGL 的 specular）：一道很淡的斜光缓缓扫过。
        //    相位由 BackgroundLayer 推，所以不需要额外动画驱动；只在 draw 阶段读，不触发重组。
        if (GLASS_SWEEP_ENABLED && quality != GlassQuality.LOW) {
            Spacer(
                Modifier.matchParentSize().drawBehind {
                    val p = (phase.phase * 2f) % 1f
                    val band = size.width * 0.30f
                    val cx = -band + (size.width + 2f * band) * p
                    drawRect(
                        brush = Brush.linearGradient(
                            0f to Color.Transparent,
                            0.5f to Color.White.copy(alpha = sweepAlpha(palette.glassSweepAlpha, glassAlpha)),
                            1f to Color.Transparent,
                            start = Offset(cx - band, -size.height * 0.35f),
                            end = Offset(cx + band, size.height * 1.35f),
                        ),
                        size = size,
                    )
                },
            )
        }
        // ④ 上光源：顶边一条高光 + 底边一条极淡的反光。
        //    设计师原话「顶边白 0.20、底边白 0.04 —— 这一条最出质感」：有它才像一块悬着的玻璃，
        //    而不是一块平板嵌在框里。降低染色 alpha 之后，这一条就是「这还是玻璃」的主要凭据。
        Spacer(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(palette.glassTopEdgeDp.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = palette.glassTopEdgeAlpha),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        Spacer(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            palette.glassBottomEdge.copy(alpha = palette.glassBottomEdgeAlpha),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        content()
        // ⑤ 边缘：报错卡片之类沿用纯色描边；其余用「左上亮、右下暗」的斜面渐变当 bevel
        // 大面板（底栏）刻意不画全圈：圈出来反而显小气，只留上面那条顶边光
        if (!big) {
            Spacer(
                Modifier.matchParentSize().border(
                    width = 1.dp,
                    brush = if (borderColor != null) {
                        SolidColor(borderColor)
                    } else if (lowComp) {
                        // 低配档：没有模糊 / 折射衬托，「左上亮、右下暗」这套斜面 bevel 根本读不出来 ——
                        // 换成一条**均匀**的 0.16 描边（设计师 P2 给的数值）。它是这一档唯一的「厚度」凭据。
                        SolidColor(Color.White.copy(alpha = LOW_TIER_EDGE_ALPHA))
                    } else {
                        Brush.linearGradient(
                            0f to palette.glassEdgeHi.copy(alpha = edgeAlpha(palette.glassEdgeHiAlpha, glassAlpha)),
                            0.45f to Color.White.copy(alpha = edgeAlpha(palette.glassEdgeMidAlpha, glassAlpha)),
                            1f to palette.glassEdgeLow.copy(alpha = edgeAlpha(palette.glassEdgeLowAlpha, glassAlpha)),
                            start = Offset.Zero,
                            end = Offset.Infinite,
                        )
                    },
                    shape = shape,
                ),
            )
        }
    }
}

/** 玻璃卡片：模糊背景 + 半透明底 + 顶部高光 + 亮边。 */
@Composable
fun GlassCard(
    glassAlpha: Float,
    modifier: Modifier = Modifier,
    border: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalPalette.current
    val quality = LocalGlassQuality.current
    val cardShape = RoundedCornerShape(RadiusR3)
    GlassSurface(
        shape = cardShape,
        glassAlpha = glassAlpha,
        borderColor = border,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            // 外投影是「厚度」的一部分：**不乘 glassAlpha**（乘了滑到最低档就像贴在纸上），
            // 颜色与强度按规范（浅色 y6/blur18 紫 / 深色 y8/blur24 近黑）。
            // 低配档整层去掉（设计师 P2 的第三件补偿）：弱机上大面积 shadow 也要花钱，
            // 而那一档本来就靠「填充 + 描边」立住了。
            .then(
                if (quality == GlassQuality.LOW) {
                    Modifier
                } else {
                    Modifier.shadow(
                        elevation = palette.glassShadowDp.dp,
                        shape = cardShape,
                        clip = false,
                        ambientColor = palette.glassShadow.copy(alpha = palette.glassShadowAlpha),
                        spotColor = palette.glassShadow.copy(alpha = palette.glassShadowAlpha),
                    )
                },
            ),
    ) {
        Column(Modifier.padding(20.dp), content = content)
    }
}

/**
 * 状态点（批 4c）：8dp 圆点 —— 状态色最小的那个形态，永远跟一句说清楚的文字一起出现（双通道）。
 * 颜色可以给状态色（绿 / 琥珀 / 红），也可以给选中色（紫）。
 */
@Composable
fun StatusDot(color: Color, size: Dp = StatusDotSize) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/**
 * 状态 chip（批 4c）：底色 = 颜色 alpha [ChipBgAlpha]，1dp 描边 alpha [CardBorderAlpha]，13sp 文字。
 * 需要「一眼看出状态、又不许铺一片色」的地方用它（[SourceChip] 也是它的一个用法）。
 */
@Composable
fun StatusChip(text: String, color: Color) {
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(RadiusR1))
            .background(color.copy(alpha = ChipBgAlpha))
            .border(1.dp, color.copy(alpha = CardBorderAlpha), RoundedCornerShape(RadiusR1))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = color,
    )
}

/** 玻璃胶囊（筛选、档位选择都用它）。它一般落在卡片里，所以只做染色、不再重复模糊。 */
@Composable
fun GlassPill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(RadiusR1)
    Box(
        modifier
            .clip(shape)
            .background(
                if (selected) {
                    Brush.verticalGradient(
                        listOf(palette.primary.copy(alpha = 0.92f), palette.primary.copy(alpha = 0.72f)),
                    )
                } else {
                    Brush.verticalGradient(
                        // L2 常态块：白 0.18/0.10 —— L1 变暗后，低于 3% 的差就看不见了
                        listOf(
                            palette.glassTint.copy(alpha = 0.18f),
                            palette.glassTint.copy(alpha = 0.10f),
                        ),
                    )
                },
            )
            .border(1.dp, if (selected) palette.primary else palette.glassBorder.copy(alpha = 0.45f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 13.sp,
            color = if (selected) Color.White else palette.text,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
fun ScreenHeader(title: String, sub: String, actions: @Composable RowScope.() -> Unit = {}) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
            Text(sub, fontSize = 13.sp, color = palette.primary)
        }
        actions()
    }
}

@Composable
fun StatCell(big: String, small: String, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Column(
        modifier
            .clip(RoundedCornerShape(RadiusR2))
            .background(palette.glassTint.copy(alpha = 0.10f))
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(big, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = palette.text)
        Text(small, fontSize = 13.sp, color = palette.sub)
    }
}

// ================= 运行状态 =================

enum class Health(val label: String) { OK("就绪"), WARN("待确认"), BAD("有问题") }

/** skillId → 给人看的名字。运行状态页和军师页共用，别再各写一份 if-else。 */
fun skillName(id: String): String = when (id) {
    "full" -> "狗头军师·满血版"
    "coder" -> "程序员搭子"
    "custom" -> "自定义 skill"
    else -> "原版狗头军师"
}

/** 顶栏那个小圆点/勾叉就靠它：模块激活 + Key + （心跳或手动确认）。 */
/**
 * 「模块到底生效了没」的判定，返回能说清缘由的说明；没生效返回 null。
 *
 * 为什么要三个信号：现代 API 之后，模块被注入哪些进程**严格跟随作用域勾选**。
 * legacy 时代框架会无条件把模块也注入它自己的 App 进程（那是 New XSharedPreferences
 * 机制的一部分），所以老的「自注入探针」一直成立；换成现代 API 后这条不再成立 ——
 * 用户只勾了微信时探针永远不亮，但模块其实工作得好好的。所以改成
 * 「任意一个信号成立即算生效」：
 *
 * 1. 自注入探针：用户显式把本模块也勾进作用域时成立（scope.list 里已放了本模块的包名）；
 * 2. service 通道已建立：框架认得本模块、并且正在跟它通信（见 RemoteSync）；
 * 3. 微信进程报过心跳：最硬的证据 —— 模块真的在微信里跑起来了。
 */
fun moduleActiveReason(store: ConfigStore): String? = when {
    ModuleStatus.isActive() -> "框架已把模块注入本应用"
    RemoteSync.bound -> "框架已连上本模块（service 通道已建立）"
    store.heartbeatAt() > 0 -> "微信进程里跑过本模块"
    else -> null
}

fun moduleActive(store: ConfigStore): Boolean = moduleActiveReason(store) != null

/** 顶栏那个小圆点/勾叉就靠它：模块激活 + Key + （心跳或手动确认）。 */
fun healthOf(store: ConfigStore): Health {
    val active = moduleActive(store)
    val cfg = store.load()
    val heartbeat = store.heartbeatAt()
    val fresh = heartbeat > 0 && System.currentTimeMillis() - heartbeat < 6 * 3600_000L
    return when {
        !active || cfg.apiKey.isBlank() -> Health.BAD
        !fresh && !store.scopeConfirmed() -> Health.WARN
        else -> Health.OK
    }
}

fun appVersion(context: Context): String = try {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    "v${info.versionName}(${info.versionCode})"
} catch (t: Throwable) {
    "v?"
}

fun formatTime(ts: Long): String =
    if (ts <= 0) "从未" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

// ================= 主界面 =================

/**
 * 底栏五个 tab 的下标。
 *
 * 顺序就是「用得上的先后」：先看出没出问题 → 让军师给出怎么回 → 管角色的人设 →
 * 实在想自己试一句才去「试一试」。设置永远最后。
 * 常量而不是裸数字：改顺序时漏改一处是最容易犯的错，让编译器帮忙盯着。
 */
private const val TAB_STATUS = 0
private const val TAB_MENTOR = 1
private const val TAB_ROLES = 2
private const val TAB_TRIAL = 3
private const val TAB_SETTINGS = 4

@Composable
fun App(store: ConfigStore) {
    var tab by remember { mutableIntStateOf(0) }
    // 「角色」的二级页（打开了某个人）也放在这一层：切走 tab 再回来时能回到原位
    var roleOpen by remember { mutableStateOf<String?>(null) }
    // 「设置」这条线上现在有四层：设置(0) → 高级设置(1) → 诊断(2) / 拉取到的联系人(3)
    var settingsPage by remember { mutableIntStateOf(0) }
    // 只关心「影响外观」的那几个字段：玻璃透明度/模糊、背景
    var ui by remember { mutableStateOf(store.load()) }
    val health = healthOf(store)
    // 设备能力只问一次：isLowRamDevice 有成本，运行中也不会变
    val context = LocalContext.current
    val lowRam = remember { isLowRamDevice(context) }
    val glassQuality = remember(ui.glassQuality) {
        decideGlassQuality(ui.glassQuality, lowRam, Build.VERSION.SDK_INT)
    }

    GoutouTheme {
        var rootSize by remember { mutableStateOf(IntSize.Zero) }
        val backdrop = rememberBackdrop(ui.bgUri, ui.bgDim, ui.glassBlur)

        val phase = remember { GlassPhase() }
        CompositionLocalProvider(
            LocalBackdrop provides backdrop,
            LocalRootSize provides rootSize,
            LocalGlassPhase provides phase,
            LocalGlassQuality provides glassQuality,
        ) {
            var contentAlpha by remember { mutableStateOf(0f) }
            LaunchedEffect(tab) {
                contentAlpha = 0f
                animate(0f, 1f, animationSpec = tween(220)) { value, _ -> contentAlpha = value }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { rootSize = it.size },
            ) {
                BackgroundLayer(backdrop)
                Column(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .graphicsLayer {
                            alpha = contentAlpha
                            translationY = (1f - contentAlpha) * 36f
                        },
                ) {
                    when (tab) {
                        TAB_STATUS -> StatusScreen(store) { tab = TAB_TRIAL }
                        TAB_MENTOR -> MentorScreen(store, ui.glassAlpha) { ui = store.load() }
                        TAB_ROLES -> RolesScreen(store, ui.glassAlpha, roleOpen) { roleOpen = it }
                        TAB_TRIAL -> TrialScreen(store, ui.glassAlpha)
                        else -> when (settingsPage) {
                            1 -> AdvancedScreen(
                                store = store,
                                ui = ui,
                                onSaved = { ui = store.load() },
                                onOpenDiag = { settingsPage = 2 },
                                onOpenCandidates = { settingsPage = 3 },
                                onBack = { settingsPage = 0 },
                            )
                            // 诊断从「高级设置」里进，所以返回也应该回到高级设置
                            2 -> DiagScreen(store, ui.glassAlpha) { settingsPage = 1 }
                            // 「拉取到的联系人」也是从高级设置里进的（白名单卡），返回同理
                            3 -> ChatCandidatesScreen(
                                store = store,
                                ui = ui,
                                onSaved = { ui = store.load() },
                                onBack = { settingsPage = 1 },
                            )
                            else -> SettingsScreen(
                                store = store,
                                ui = ui,
                                onUi = { ui = it },
                                onOpenAdvanced = { settingsPage = 1 },
                            )
                        }
                    }
                }
                NavBar(
                    tab = tab,
                    health = health,
                    glassAlpha = ui.glassAlpha,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(14.dp),
                ) { next ->
                    // 离开这一栏就把二级页收掉：切走再回来应该回到列表，而不是停在上次那个二级页；
                    // 点自己这一栏也当成「退出二级页」。
                    if (next != TAB_ROLES || next == tab) roleOpen = null
                    if (next != TAB_SETTINGS || next == tab) settingsPage = 0
                    tab = next
                }
            }
        }
    }
}

private val NavShape = RoundedCornerShape(RadiusR4)
private val NavIndicatorShape = RoundedCornerShape(RadiusR3)
private val NavBarHeight = 62.dp

/**
 * 底部导航。
 *
 * 和上一版的区别：
 * - 选中态从「每一格各自变色」改成**一整块会滑动的指示块**（弹簧跟随），切页时是连续的位移，
 *   而不是两块背景直接交换；
 * - 指示块用 `offset { }` 的 lambda 重载 → 只走布局阶段、不触发重组，滑动是满帧的；
 * - 图标随选中进度缩放 + 变色，文字跟着变重；
 * - 切页给一次轻触觉反馈，点起来「有实体感」；
 * - 徽标外加了一圈底色，从玻璃上浮出来，不再糊在背景里。
 */
@Composable
private fun NavBar(
    tab: Int,
    health: Health,
    glassAlpha: Float,
    modifier: Modifier = Modifier,
    onTab: (Int) -> Unit,
) {
    val palette = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    val items = listOf(
        "运行状态" to Icons.Filled.Pets,
        "军师" to Icons.Filled.Edit,
        "角色" to Icons.Filled.Person,
        "试一试" to Icons.Filled.PlayArrow,
        "设置" to Icons.Filled.Settings,
    )

    var rowWidth by remember { mutableIntStateOf(0) }
    val cellPx = if (rowWidth > 0) rowWidth.toFloat() / items.size else 0f
    val slide = remember { Animatable(0f) }
    LaunchedEffect(tab, cellPx) {
        if (cellPx <= 0f) return@LaunchedEffect
        slide.animateTo(tab.toFloat(), spring(dampingRatio = 0.76f, stiffness = Spring.StiffnessMediumLow))
    }

    GlassSurface(
        shape = NavShape,
        glassAlpha = glassAlpha,
        // R4 是一整块板：填充更实、只留一条顶边光（见 GlassSurface 的 big）
        big = true,
        modifier = modifier.shadow(
            elevation = (palette.glassShadowDp + 10f).dp,
            shape = NavShape,
            clip = false,
            ambientColor = palette.glassShadow.copy(alpha = palette.glassShadowAlpha),
            spotColor = palette.glassShadow.copy(alpha = palette.glassShadowAlpha),
        ),
    ) {
        Box(Modifier.fillMaxWidth().height(NavBarHeight)) {
            if (cellPx > 0f) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset { IntOffset((slide.value * cellPx).roundToInt(), 0) }
                        .width(with(LocalDensity.current) { cellPx.toDp() })
                        .fillMaxHeight()
                        .padding(horizontal = 3.dp, vertical = 5.dp)
                        .clip(NavIndicatorShape)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    palette.primary.copy(alpha = 0.30f),
                                    palette.primary.copy(alpha = 0.12f),
                                ),
                            ),
                        )
                        .border(1.dp, palette.primary.copy(alpha = 0.34f), NavIndicatorShape),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(NavBarHeight)
                    .onGloballyPositioned { rowWidth = it.size.width },
            ) {
                items.forEachIndexed { index, (label, icon) ->
                    NavItem(
                        label = label,
                        icon = icon,
                        selected = index == tab,
                        palette = palette,
                        badge = if (index == 0) health else null,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (index != tab) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            onTab(index)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun NavItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    palette: Palette,
    badge: Health?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // 0 → 1 的「选中进度」，图标缩放/变色/字重都跟着它走，切换才有连续感
    val p by animateFloatAsState(if (selected) 1f else 0f, tween(260))
    val interaction = remember { MutableInteractionSource() }
    val tint = lerp(palette.sub, palette.primary, p)

    Column(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(RadiusR3))
            // 自带指示块了，所以不要涟漪 —— 否则会闪出一个和指示块不重合的方块
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .graphicsLayer {
                val scale = 0.94f + 0.06f * p
                scaleX = scale
                scaleY = scale
                translationY = -1.5f * p
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Icon(
                icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(21.dp + 2.dp * p),
            )
            if (badge != null) {
                val badgeColor = when (badge) {
                    Health.OK -> palette.ok
                    Health.WARN -> palette.warn
                    Health.BAD -> palette.bad
                }
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 6.dp, y = (-5).dp)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(palette.bgTop.copy(alpha = 0.90f))
                        .padding(1.5.dp)
                        .clip(CircleShape)
                        .background(badgeColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (badge == Health.BAD) Icons.Filled.Close else Icons.Filled.Check,
                        contentDescription = badge.label,
                        tint = Color.White,
                        modifier = Modifier.size(8.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            fontSize = 10.sp,
            letterSpacing = 0.2.sp,
            fontWeight = if (p > 0.5f) FontWeight.SemiBold else FontWeight.Normal,
            color = tint,
        )
    }
}
