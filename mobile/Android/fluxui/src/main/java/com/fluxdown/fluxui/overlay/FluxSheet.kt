package com.fluxdown.fluxui.overlay

import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.fluxdown.fluxui.icons.FluxIcons
import com.fluxdown.fluxui.material.FluxBlur
import com.fluxdown.fluxui.material.FluxGlass
import com.fluxdown.fluxui.material.LocalFluxBackdrop
import com.fluxdown.fluxui.material.fluxGlass
import com.fluxdown.fluxui.theme.FluxText
import com.fluxdown.fluxui.theme.FluxTheme
import com.fluxdown.fluxui.theme.FluxWindowClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 档位：Wrap = 内容高（max = 屏高 − 状态栏 − 8）· Partial = 56% 高（上拖 >50dp 升 Full）· Full = 顶 sb+6 至底。 */
enum class FluxSheetDetent { Wrap, Partial, Full }

/** Sheet 顶部左右下的圆角随 Full 比例 f 由 32 → 0（仅底角）。 */
private class SheetShape(private val full: () -> Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { 32.dp.toPx() }
        val rb = r * (1f - full().coerceIn(0f, 1f))
        return Outline.Rounded(
            RoundRect(
                0f, 0f, size.width, size.height,
                topLeftCornerRadius = CornerRadius(r),
                topRightCornerRadius = CornerRadius(r),
                bottomRightCornerRadius = CornerRadius(rb),
                bottomLeftCornerRadius = CornerRadius(rb),
            ),
        )
    }
}

/**
 * 浮动 Sheet（§12.25）。声明式：放在应用根 Box 中内容之后、[FluxOverlayHost] 之前的全屏层里，
 * `visible` 驱动进出场；多个 Sheet 按组合顺序叠放（后者在上）。
 *
 * - 浮动：距屏边 12dp、r32；Full 为左右 / 底 0、顶 sb+6、仅顶部圆角；medium / expanded 居中宽 520（Full 640）。
 * - 遮罩 dim + 模糊 8；点遮罩 / 把手下拖 / 返回关闭（[dismissible] = false 时仅内容里的按钮可关，返回键触发 reject）。
 * - 手势：把手区（含 [header]）下拖跟手、上拖阻尼 /5；松手 >90dp 或速度 >600dp/s → 关闭（gestureEnd）；
 *   Partial 上拖 <−50dp → Full（tick）。弹出时 gestureStart。
 * - 返回：预测性返回，Sheet 自身缩放 .92 + 下沉（不动页面）；提交 confirm。
 * - [content] 位于可滚区域（padding 0 16 28）；[footer] 吸底（用 [FluxSheetFooter]）。
 *
 * @param title 无障碍 paneTitle（建议与 header 标题一致）
 */
@Composable
fun FluxSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    detent: FluxSheetDetent = FluxSheetDetent.Wrap,
    dismissible: Boolean = true,
    title: String? = null,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val motion = FluxTheme.motion
    val haptics = FluxTheme.haptics
    val windowClass = FluxTheme.windowClass
    val colors = FluxTheme.colors
    val progress = rememberOverlayProgress()
    val scope = rememberCoroutineScope()
    val visibleNow by rememberUpdatedState(visible)
    val dismissNow by rememberUpdatedState(onDismissRequest)
    val rawDrag = remember { mutableFloatStateOf(0f) }
    val backP = remember { mutableFloatStateOf(0f) }
    var expanded by remember { mutableStateOf(detent == FluxSheetDetent.Full) }
    val fullAnim = remember { Animatable(if (detent == FluxSheetDetent.Full) 1f else 0f) }
    val settleJob = remember { arrayOfNulls<Job>(1) }
    val shape = remember { SheetShape { fullAnim.value } }
    val outlinePath = remember { Path() }
    val density = LocalDensity.current
    val dismissPx = with(density) { 90.dp.toPx() }
    val flingPx = with(density) { 600.dp.toPx() }
    val expandPx = with(density) { 50.dp.toPx() }

    LaunchedEffect(detent) { expanded = detent == FluxSheetDetent.Full }
    LaunchedEffect(expanded) { fullAnim.animateTo(if (expanded) 1f else 0f, motion.of(motion.soft)) }
    LaunchedEffect(visible) {
        if (visible) {
            haptics.gestureStart()
            progress.enter(motion.of(motion.soft))
        } else {
            progress.exit(motion.of(motion.snap))
            rawDrag.floatValue = 0f
            backP.floatValue = 0f
            expanded = detent == FluxSheetDetent.Full
        }
    }

    /** 请求关闭后若宿主没有真的隐藏（拒绝了），把 [state] 弹回 0。 */
    suspend fun bounceBackIfStillVisible(state: MutableFloatState) {
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        if (visibleNow && state.floatValue != 0f) {
            animate(state.floatValue, 0f, animationSpec = motion.of(motion.fluid)) { v, _ -> state.floatValue = v }
        }
    }

    // 菜单 / 对话框在更高 z 序：它们打开时返回键交给 FluxOverlayHost（后注册的 Sheet 处理器否则会抢先）
    val overlays = LocalFluxOverlays.current
    val canGoBack = progress.present && visible && !overlays.hasMenu && !overlays.hasDialog
    if (dismissible) {
        PredictiveBackHandler(enabled = canGoBack) { events: Flow<BackEventCompat> ->
            try {
                events.collect { backP.floatValue = it.progress }
                haptics.confirm()
                dismissNow()
                bounceBackIfStillVisible(backP)
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    animate(backP.floatValue, 0f, animationSpec = motion.of(motion.fluid)) { v, _ -> backP.floatValue = v }
                }
            }
        }
    } else {
        BackHandler(enabled = canGoBack) { haptics.reject() }
    }

    if (!progress.present) return

    val status = WindowInsets.statusBars
    val nav = WindowInsets.navigationBars
    val ime = WindowInsets.ime
    val isPartial = detent == FluxSheetDetent.Partial
    val fill = detent != FluxSheetDetent.Wrap
    val dragState = rememberDraggableState { rawDrag.floatValue += it }

    // Sheet 是不透明叠加面：本体与内部玻璃面都不采样下层页面的模糊副本（backdrop = null ⇒ 一律实色）
    CompositionLocalProvider(LocalFluxBackdrop provides null) { Box(modifier.fillMaxSize().semantics { isTraversalGroup = true }) {
        // 遮罩
        Box(
            Modifier
                .fillMaxSize()
                .fluxScrim(8.dp) { progress.value }
                .then(
                    if (dismissible && visible) {
                        Modifier
                            .pointerInput(Unit) { detectTapGestures { dismissNow() } }
                            .semantics {
                                contentDescription = "关闭面板"
                                role = Role.Button
                                onClick { dismissNow(); true }
                            }
                    } else {
                        Modifier.pointerInput(Unit) { detectTapGestures { } }
                    },
                ),
        )

        Column(
            Modifier
                .layout { measurable, constraints ->
                    val w = constraints.maxWidth
                    val h = constraints.maxHeight
                    val f = fullAnim.value.coerceIn(0f, 1f)
                    val sbTop = status.getTop(this)
                    val navB = nav.getBottom(this)
                    val imeB = ime.getBottom(this)
                    val m12 = 12.dp.toPx()
                    val margin = m12 * (1f - f)
                    val sheetW = if (windowClass == FluxWindowClass.Compact) {
                        w - 2 * margin
                    } else {
                        min(w.toFloat(), 520.dp.toPx() + (640.dp.toPx() - 520.dp.toPx()) * f)
                    }.roundToInt().coerceAtLeast(0)
                    val gapFloating = m12 + max(navB, imeB)
                    val gap = (gapFloating + (imeB - gapFloating) * f).roundToInt()
                    val fullH = (h - gap - sbTop - 6.dp.roundToPx()).coerceAtLeast(0)
                    val fixedH = when {
                        detent == FluxSheetDetent.Full -> fullH
                        isPartial -> min(fullH, (h * 0.56f + (fullH - h * 0.56f) * f).roundToInt())
                        else -> 0
                    }
                    val maxH = (h - gap - sbTop - 8.dp.roundToPx()).coerceAtLeast(0)
                    val c = if (fill) {
                        Constraints(sheetW, sheetW, fixedH, fixedH)
                    } else {
                        Constraints(sheetW, sheetW, 0, maxH)
                    }
                    val p = measurable.measure(c)
                    layout(w, h) {
                        val enter = (1f - progress.value) * (p.height + 24.dp.toPx())
                        val drag = rawDrag.floatValue.let { if (it < 0f) it / 5f else it }
                        val back = backP.floatValue * 16.dp.toPx()
                        val x = (w - p.width) / 2
                        val y = h - gap - p.height + (enter + drag + back).roundToInt()
                        p.place(x, y)
                    }
                }
                .graphicsLayer {
                    val s = 1f - 0.08f * backP.floatValue
                    scaleX = s
                    scaleY = s
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
                .fluxGlass(FluxGlass.Sheet, shape, FluxBlur.Thick)
                .drawWithContent {
                    outlinePath.reset()
                    outlinePath.addOutline(shape.createOutline(size, layoutDirection, this))
                    clipPath(outlinePath) { this@drawWithContent.drawContent() }
                }
                .swallowTaps()
                .semantics { title?.let { paneTitle = it } },
        ) {
            // 把手 + 头：可拖动区
            Column(
                Modifier.draggable(
                    state = dragState,
                    orientation = Orientation.Vertical,
                    onDragStarted = { settleJob[0]?.cancel() },
                    onDragStopped = { velocity ->
                        val raw = rawDrag.floatValue
                        val job = scope.launch {
                            if (dismissible && raw > 0f && (raw > dismissPx || velocity > flingPx)) {
                                haptics.gestureEnd()
                                dismissNow()
                                bounceBackIfStillVisible(rawDrag)
                            } else {
                                if (isPartial && !expanded && raw < -expandPx) {
                                    expanded = true
                                    haptics.tick()
                                }
                                animate(raw, 0f, animationSpec = motion.of(motion.fluid)) { v, _ -> rawDrag.floatValue = v }
                            }
                        }
                        settleJob[0] = job
                    },
                ),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .semantics {
                            contentDescription = "拖动调整面板高度"
                            val actions = ArrayList<CustomAccessibilityAction>(2)
                            if (isPartial && !expanded) {
                                actions += CustomAccessibilityAction("展开面板") { expanded = true; true }
                            }
                            if (dismissible) {
                                actions += CustomAccessibilityAction("关闭面板") { dismissNow(); true }
                            }
                            customActions = actions
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .width(38.dp)
                            .height(4.dp)
                            .background(colors.ink.copy(alpha = 0.26f), FluxTheme.shapes.full),
                    )
                }
                header?.invoke()
            }
            // 滚动体
            Column(
                Modifier
                    .weight(1f, fill = fill)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
            ) { content() }
            footer?.invoke()
            // Full 档延伸到屏底：脚部让出导航栏高度
            Spacer(
                Modifier.layout { m, c ->
                    val f = fullAnim.value.coerceIn(0f, 1f)
                    val pad = (max(0, nav.getBottom(this) - ime.getBottom(this)) * f).roundToInt()
                    val p = m.measure(c)
                    layout(p.width, pad) { }
                },
            )
        }
    } }
}

/**
 * Sheet 头（sheetHead）：`padding 2 16 12 22`；标题 h1、副标题 sm inkMuted；右侧动作槽 + 关闭钮（glass sm 36，命中 48）。
 */
@Composable
fun FluxSheetHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClose: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = FluxTheme.colors
    val type = FluxTheme.type
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 22.dp, top = 2.dp, end = if (onClose != null) 10.dp else 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            FluxText(title, Modifier.semantics { heading() }, style = type.h1, color = c.ink, maxLines = 2)
            subtitle?.let {
                FluxText(it, Modifier.padding(top = 2.dp), style = type.sm, color = c.inkMuted, maxLines = 3)
            }
        }
        actions()
        onClose?.let { OverlayIconButton(FluxIcons.X, "关闭", it) }
    }
}

/** Sheet 脚（sheetFoot）：`padding 12 16 18`、顶发丝线、`sheetBg@80%` 底；按钮间距 10。 */
@Composable
fun FluxSheetFooter(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val c = FluxTheme.colors
    val bg = c.sheetBg.copy(alpha = c.sheetBg.alpha * 0.8f)
    Row(
        modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(bg)
                drawLine(c.hairline, Offset.Zero, Offset(size.width, 0f), strokeWidth = 0.5.dp.toPx())
            }
            .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}
