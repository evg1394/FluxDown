package com.fluxdown.fluxui.data

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.fluxdown.fluxui.controls.FluxTag
import com.fluxdown.fluxui.controls.Tone
import com.fluxdown.fluxui.icons.FluxIcon
import com.fluxdown.fluxui.icons.FluxIcons
import com.fluxdown.fluxui.theme.FileCategory
import com.fluxdown.fluxui.theme.FluxColors
import com.fluxdown.fluxui.theme.FluxScaleGroup
import com.fluxdown.fluxui.theme.FluxText
import com.fluxdown.fluxui.theme.FluxTheme
import com.fluxdown.fluxui.theme.fluxPressable
import kotlinx.coroutines.launch

/** 行密度（§12.14）：舒适 = 40|1fr|36 + 4dp 流带第二行；紧凑 = 32|1fr|32 单行 + 2dp 流带置于文本列内。 */
enum class TaskRowDensity { Comfortable, Compact }

/** 选择模式可视状态：None = 非选择模式；Unselected / Selected = 选择模式下未选 / 已选。 */
enum class TaskRowSelection { None, Unselected, Selected }

/** 元信息片段色调（均来自主题色，失败 / 缺失 / 做种用文字色以保证对比度）。 */
enum class MetaTone { Muted, Accent, Coral, Amber, Mint, Faint, Ink }

/**
 * 任务元信息构建器：以 `·` 分隔（分隔符 inkFaint，左右各约 5dp）并着色。
 * 例：下载中 `speed`(Accent) · `eta` · `42.0%` · `1.2/2.9 GB`，尾部字段 [trailing]（整体 inkFaint）。
 * 空字符串片段自动跳过。基色为 inkMuted（由 TaskRow 的文本样式提供）。
 */
class TaskMetaBuilder(private val colors: FluxColors) {
    private val sb = AnnotatedString.Builder()
    private var hasContent = false

    /** 追加一个片段；若前面已有内容则先插入 `·` 分隔。 */
    fun part(text: String, tone: MetaTone = MetaTone.Muted): TaskMetaBuilder {
        if (text.isEmpty()) return this
        if (hasContent) separator()
        sb.pushStyle(SpanStyle(color = colorOf(tone)))
        sb.append(text)
        sb.pop()
        hasContent = true
        return this
    }

    /** 追加尾部字段（协议 · 来源站点 · 队列名 · 创建时间…），整体 inkFaint；null / 空串跳过。失败行不要调用。 */
    fun trailing(parts: List<String?>): TaskMetaBuilder {
        for (p in parts) if (!p.isNullOrEmpty()) part(p, MetaTone.Faint)
        return this
    }

    fun trailing(vararg parts: String?): TaskMetaBuilder = trailing(parts.asList())

    private fun separator() {
        // mono 空格 ≈ 0.6em；0.69 倍字号 ⇒ 约 5dp（12sp 基准）
        sb.pushStyle(SpanStyle(fontSize = 0.69f.em))
        sb.append(' ')
        sb.pop()
        sb.pushStyle(SpanStyle(color = colors.inkFaint))
        sb.append('·')
        sb.pop()
        sb.pushStyle(SpanStyle(fontSize = 0.69f.em))
        sb.append(' ')
        sb.pop()
    }

    private fun colorOf(tone: MetaTone): Color = when (tone) {
        MetaTone.Muted -> colors.inkMuted
        MetaTone.Accent -> colors.accentHi
        MetaTone.Coral -> colors.coralText
        MetaTone.Amber -> colors.amberText
        MetaTone.Mint -> colors.mintText
        MetaTone.Faint -> colors.inkFaint
        MetaTone.Ink -> colors.ink
    }

    fun build(): AnnotatedString = sb.toAnnotatedString()
}

/** `colors.buildTaskMeta { part(speed, MetaTone.Accent); part(eta); … trailing(protocol, site) }` */
fun FluxColors.buildTaskMeta(block: TaskMetaBuilder.() -> Unit): AnnotatedString =
    TaskMetaBuilder(this).apply(block).build()

/**
 * 任务行（D1 核心）。网格 `40 | 1fr | 36`，列间距 12；第二行为全宽 [FlowStrip]（行间距 11）。
 * 由自定义 `Layout` 单次测量，无嵌套 Row/Column；不含任何 Real 玻璃。
 *
 * @param meta 元信息（由应用用 [buildTaskMeta] 拼好）。≥150% 字号时允许折成两行。
 * @param flowSegments `null` = 不显示流带（行变单行，row-gap 0）：已完成 / 总大小未知 / 排队且未下载。
 * @param strikethrough 文件已删除：名称 inkMuted + inkFaint 删除线。
 * @param selection 选择模式：选中底 accentLo；图块显示勾覆盖层；环钮只切换勾选（转发给 [onTileClick]）。
 * @param horizontalPadding D1「传输中」分区内 18，「历史」20，其余 16。
 * @param contentDescription 整行朗读文案（建议“{名}，{状态}，{p}%，{速度}，剩余 {ETA}，{大小}”）；缺省为 名称 + 元信息。
 * @param customActions 长按菜单全集 + 滑动动作（TalkBack 不需要长按 / 滑动）。
 * @param badge 元信息行前的琥珀色角标（如「待确认」）；[onBadgeClick] 非空时角标可点（行本身的点击不变）。
 *
 * 点击：行 = 打开详情；长按 = 菜单（LONG_PRESS 触感）；图块 = 进入 / 切换多选；环 = 主操作。
 * 按压为 glass2 底（fluid 弹簧），整行不缩放以免列表抖动。
 */
@Composable
fun TaskRow(
    fileName: String,
    meta: AnnotatedString,
    tileIcon: ImageVector,
    categoryColor: Color?,
    ringKind: RingKind,
    ringProgress: Float?,
    ringContentDescription: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRingClick: () -> Unit,
    onTileClick: () -> Unit,
    modifier: Modifier = Modifier,
    flowSegments: List<FlowSegmentUi>? = null,
    flowState: FlowStripState = FlowStripState.Downloading,
    density: TaskRowDensity = TaskRowDensity.Comfortable,
    selection: TaskRowSelection = TaskRowSelection.None,
    strikethrough: Boolean = false,
    horizontalPadding: Dp = 16.dp,
    contentDescription: String? = null,
    onClickLabel: String? = null,
    customActions: List<CustomAccessibilityAction> = emptyList(),
    badge: String? = null,
    onBadgeClick: (() -> Unit)? = null,
) {
    val colors = FluxTheme.colors
    val type = FluxTheme.type
    val compact = density == TaskRowDensity.Compact
    val selecting = selection != TaskRowSelection.None
    val selected = selection == TaskRowSelection.Selected

    val nameStyle = remember(type, compact) { taskNameStyle(type, compact) }
    val metaStyle = remember(type, colors) { type.mono.copy(color = colors.inkMuted) }
    val metaLines = if (type.fontScale >= 1.5f) 2 else 1
    val cd = contentDescription ?: remember(fileName, meta, badge) { listOfNotNull(fileName, badge, meta.text).joinToString(", ") }

    val interaction = remember { MutableInteractionSource() }
    val press = rememberPressAnim(interaction)

    TaskRowScaffold(
        compact = compact,
        showFlow = flowSegments != null,
        horizontalPadding = horizontalPadding,
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                if (selected) drawRect(colors.accentLo)
                val p = press.value
                if (p > 0.001f) drawRect(colors.glass2, alpha = p.coerceIn(0f, 1f))
            }
            .fluxPressable(
                onClick = onClick,
                scale = 1f,
                role = if (selecting) Role.Checkbox else Role.Button,
                onClickLabel = onClickLabel,
                onLongClick = onLongClick,
                interactionSource = interaction,
            )
            .semantics {
                this.contentDescription = cd
                if (selecting) toggleableState = if (selected) ToggleableState.On else ToggleableState.Off
                if (customActions.isNotEmpty()) this.customActions = customActions
            },
        tile = {
            FileTile(
                icon = tileIcon,
                categoryColor = categoryColor,
                size = if (compact) FileTileSize.Sm else FileTileSize.Md,
                selectionMode = selecting,
                selected = selected,
                onClick = onTileClick,
            )
        },
        name = {
            TaskName(
                text = fileName,
                style = nameStyle,
                color = if (strikethrough) colors.inkMuted else colors.ink,
                strike = strikethrough,
                lineColor = colors.inkFaint,
            )
        },
        meta = {
            if (badge == null) {
                BasicText(
                    meta,
                    modifier = Modifier.clearAndSetSemantics { },
                    style = metaStyle,
                    maxLines = metaLines,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FluxTag(
                        badge,
                        if (onBadgeClick != null) {
                            Modifier.fluxPressable(onClick = onBadgeClick, scale = 1f, role = Role.Button)
                        } else {
                            Modifier
                        },
                        tone = Tone.Amber,
                    )
                    BasicText(
                        meta,
                        modifier = Modifier.weight(1f, fill = false).clearAndSetSemantics { },
                        style = metaStyle,
                        maxLines = metaLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        ring = {
            RingControl(
                progress = ringProgress,
                kind = ringKind,
                onClick = { if (selecting) onTileClick() else onRingClick() },
                contentDescription = ringContentDescription,
                size = if (compact) RingSize.Sm else RingSize.Md,
            )
        },
        flow = {
            FlowStrip(
                segments = flowSegments ?: emptyList(),
                state = flowState,
                height = if (compact) FlowStripHeight.Xs else FlowStripHeight.Regular,
            )
        },
    )
}

/**
 * 任务组行（插件清单 / 批量组，§12.15）：与 [TaskRow] 同网格；图块后方叠一层偏移描边暗示“一组”；
 * 流带为成员伪段（见 [FlowSegmentUi.members]）；环 = 全部暂停 / 继续（有速度 Pause，有失败 Retry，其余 Play）。
 */
@Composable
fun TaskGroupRow(
    name: String,
    meta: AnnotatedString,
    flowSegments: List<FlowSegmentUi>,
    flowState: FlowStripState,
    ringKind: RingKind,
    ringProgress: Float?,
    ringContentDescription: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRingClick: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 16.dp,
    contentDescription: String? = null,
    onClickLabel: String? = null,
    customActions: List<CustomAccessibilityAction> = emptyList(),
) {
    val colors = FluxTheme.colors
    val type = FluxTheme.type
    val nameStyle = remember(type) { taskNameStyle(type, compact = false) }
    val metaStyle = remember(type, colors) { type.mono.copy(color = colors.inkMuted) }
    val metaLines = if (type.fontScale >= 1.5f) 2 else 1
    val cd = contentDescription ?: remember(name, meta) { "$name, ${meta.text}" }
    val interaction = remember { MutableInteractionSource() }
    val press = rememberPressAnim(interaction)

    TaskRowScaffold(
        compact = false,
        showFlow = true,
        horizontalPadding = horizontalPadding,
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val p = press.value
                if (p > 0.001f) drawRect(colors.glass2, alpha = p.coerceIn(0f, 1f))
            }
            .fluxPressable(
                onClick = onClick,
                scale = 1f,
                role = Role.Button,
                onClickLabel = onClickLabel,
                onLongClick = onLongClick,
                interactionSource = interaction,
            )
            .semantics {
                this.contentDescription = cd
                if (customActions.isNotEmpty()) this.customActions = customActions
            },
        tile = {
            Box(
                Modifier
                    .size(FileTileSize.Md.dp)
                    .drawWithCache {
                        val r = CornerRadius(14.dp.toPx())
                        val hair = Stroke(0.5.dp.toPx())
                        val inset = 4.dp.toPx()
                        onDrawBehind {
                            translate(-inset, -inset) {
                                drawRoundRect(colors.glass1, cornerRadius = r)
                                drawRoundRect(colors.hairline, cornerRadius = r, style = hair)
                            }
                        }
                    },
            ) {
                FileTile(
                    icon = FluxIcons.Group,
                    categoryColor = colors.category(FileCategory.Other),
                    size = FileTileSize.Md,
                )
            }
        },
        name = {
            TaskName(text = name, style = nameStyle, color = colors.ink, strike = false, lineColor = colors.inkFaint)
        },
        meta = {
            BasicText(
                meta,
                modifier = Modifier.clearAndSetSemantics { },
                style = metaStyle,
                maxLines = metaLines,
                overflow = TextOverflow.Ellipsis,
            )
        },
        ring = {
            RingControl(
                progress = ringProgress,
                kind = ringKind,
                onClick = onRingClick,
                contentDescription = ringContentDescription,
                size = RingSize.Md,
            )
        },
        flow = { FlowStrip(segments = flowSegments, state = flowState) },
    )
}

/**
 * 分组折叠头：⌄ 标题(sm 600) 计数(monoS inkFaint)，右侧 [extra]（monoS inkFaint）；padding 18/8/8。
 * 折叠时箭头 −90°（fluid 弹簧）；无障碍暴露 expand / collapse 动作，命中高 ≥ 48dp。
 */
@Composable
fun GroupHeader(
    title: String,
    count: Int,
    closed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    extra: String? = null,
) {
    val colors = FluxTheme.colors
    val type = FluxTheme.type
    val motion = FluxTheme.motion
    val titleStyle = remember(type, colors) {
        type.weight(type.sm, 600).copy(color = colors.ink, letterSpacing = 0.02.em)
    }
    val numStyle = remember(type, colors) { type.weight(type.monoS, 500, mono = true).copy(color = colors.inkFaint) }
    val rot = animateFloatAsState(if (closed) -90f else 0f, motion.of(motion.fluid), label = "groupChevron")
    Row(
        modifier
            .fillMaxWidth()
            .fluxPressable(onClick = onToggle, scale = 1f, role = Role.Button)
            .heightIn(min = 48.dp)
            .padding(start = 8.dp, end = 8.dp, top = 18.dp, bottom = 8.dp)
            .semantics(mergeDescendants = true) {
                if (closed) expand { onToggle(); true } else collapse { onToggle(); true }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        FluxIcon(
            FluxIcons.ChevronDown,
            contentDescription = null,
            modifier = Modifier.graphicsLayer { rotationZ = rot.value },
            size = 16.dp,
            tint = colors.inkMuted,
        )
        BasicText(title, style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText(count.toString(), style = numStyle)
        if (extra != null) {
            Spacer(Modifier.weight(1f))
            BasicText(extra, style = numStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun taskNameStyle(type: com.fluxdown.fluxui.theme.FluxType, compact: Boolean): TextStyle {
    val base = type.weight(type.body, 500).copy(lineHeight = 1.3.em, letterSpacing = (-0.005).em)
    return if (compact) type.sized(base, 14f, FluxScaleGroup.Km) else base
}

/** 按压底色动画（0..1），只在绘制阶段读取。 */
@Composable
private fun rememberPressAnim(interaction: MutableInteractionSource): Animatable<Float, androidx.compose.animation.core.AnimationVector1D> {
    val motion = FluxTheme.motion
    val press = remember { Animatable(0f) }
    LaunchedEffect(interaction, motion) {
        val spec = motion.of<Float>(motion.fluid)
        interaction.interactions.collect { i ->
            when (i) {
                is PressInteraction.Press -> launch { press.animateTo(1f, spec) }
                is PressInteraction.Release, is PressInteraction.Cancel -> launch { press.animateTo(0f, spec) }
                else -> Unit
            }
        }
    }
    return press
}

/** 文件名：单行省略；[strike] 时在每行文字中部画 inkFaint 删除线（TextDecoration 无法单独着色）。 */
@Composable
private fun TaskName(text: String, style: TextStyle, color: Color, strike: Boolean, lineColor: Color) {
    if (!strike) {
        FluxText(text, Modifier.clearAndSetSemantics { }, style, color, maxLines = 1)
        return
    }
    val holder = remember { arrayOfNulls<TextLayoutResult>(1) }
    FluxText(
        text,
        modifier = Modifier
            .clearAndSetSemantics { }
            .drawWithCache {
                val sw = 1.dp.toPx()
                onDrawWithContent {
                    drawContent()
                    val r = holder[0] ?: return@onDrawWithContent
                    for (i in 0 until r.lineCount) {
                        val y = r.getLineBaseline(i) - (r.getLineBottom(i) - r.getLineTop(i)) * 0.22f
                        drawLine(lineColor, Offset(r.getLineLeft(i), y), Offset(r.getLineRight(i), y), strokeWidth = sw)
                    }
                }
            },
        style = style,
        color = color,
        maxLines = 1,
        onTextLayout = { holder[0] = it },
    )
}

/**
 * 行网格：tile | text(name + meta) | ring，第二行 flow。五个槽位各恰好产出一个布局节点（flow 仅 [showFlow] 时存在），
 * 以组合顺序取 measurable，免去 layoutId 包装层。
 */
@Composable
internal fun TaskRowScaffold(
    compact: Boolean,
    showFlow: Boolean,
    horizontalPadding: Dp,
    modifier: Modifier,
    tile: @Composable () -> Unit,
    name: @Composable () -> Unit,
    meta: @Composable () -> Unit,
    ring: @Composable () -> Unit,
    flow: @Composable () -> Unit,
) {
    Layout(
        content = {
            tile()
            name()
            meta()
            ring()
            if (showFlow) flow()
        },
        modifier = modifier,
    ) { ms, c ->
        val w = if (c.hasBoundedWidth) c.maxWidth else c.minWidth
        val px = horizontalPadding.roundToPx()
        val vp = (if (compact) 9 else 14).dp.roundToPx()
        val tileS = (if (compact) 32 else 40).dp.roundToPx()
        val ringS = (if (compact) 32 else 36).dp.roundToPx()
        val colGap = 12.dp.roundToPx()
        val textW = (w - 2 * px - tileS - ringS - 2 * colGap).coerceAtLeast(0)

        val tilePl = ms[0].measure(Constraints.fixed(tileS, tileS))
        val namePl = ms[1].measure(Constraints(maxWidth = textW))
        val metaPl = ms[2].measure(Constraints(maxWidth = textW))
        val ringPl = ms[3].measure(Constraints.fixed(ringS, ringS))

        val flowPl = if (showFlow) {
            ms[4].measure(Constraints.fixedWidth(if (compact) textW else (w - 2 * px).coerceAtLeast(0)))
        } else null
        val nameGap = 3.dp.roundToPx()
        val flowGapInText = 6.dp.roundToPx()
        val textH = namePl.height + nameGap + metaPl.height +
            if (compact && flowPl != null) flowGapInText + flowPl.height else 0
        val rowH = maxOf(tileS, ringS, textH)
        val rowGap = if (flowPl != null && !compact) 11.dp.roundToPx() else 0
        val flowBelow = if (flowPl != null && !compact) flowPl.height else 0
        val h = vp + rowH + rowGap + flowBelow + vp

        layout(w, h) {
            val textX = px + tileS + colGap
            tilePl.placeRelative(px, vp + (rowH - tileS) / 2)
            namePl.placeRelative(textX, vp + (rowH - textH) / 2)
            val metaY = vp + (rowH - textH) / 2 + namePl.height + 3.dp.roundToPx()
            metaPl.placeRelative(textX, metaY)
            ringPl.placeRelative(px + tileS + colGap + textW + colGap, vp + (rowH - ringS) / 2)
            if (flowPl != null) {
                if (compact) flowPl.placeRelative(textX, metaY + metaPl.height + 6.dp.roundToPx())
                else flowPl.placeRelative(px, vp + rowH + rowGap)
            }
        }
    }
}
