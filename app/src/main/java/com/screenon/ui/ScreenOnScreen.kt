package com.screenon.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.screenon.KeepAwake
import com.screenon.KeepAwake.Mode
import com.screenon.R
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Small caps-style label: tracked uppercase text, used for all secondary labels. */
private val Tracked = TextStyle(fontSize = 12.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Medium)

@Composable
fun ScreenOnScreen(
    state: KeepAwake.State,
    onToggle: () -> Unit,
    onModeChange: (Mode) -> Unit,
    onDurationChange: (Int) -> Unit,
    onBatteryGuardChange: (Boolean) -> Unit,
    onGrant: () -> Unit,
    onPreciseTimer: () -> Unit,
    onExpire: () -> Unit,
    onAddTile: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val now = rememberCountdownClock(state.startedAt, state.endsAt, onExpire)
    val left = state.endsAt?.let { (it - now).coerceAtLeast(0L) }
    val remaining = if (left != null && state.startedAt != null) {
        (left.toFloat() / (state.endsAt - state.startedAt).coerceAtLeast(1L)).coerceIn(0f, 1f)
    } else 1f

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        // The bottom half grew with the timer and battery rows: the dial gives way on short screens.
        val dialSize = (maxHeight * 0.33f).coerceIn(196.dp, 268.dp)
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 28.dp, vertical = 20.dp)
                .widthIn(max = 460.dp)
                .align(Alignment.TopCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.app_name).uppercase(),
                style = Tracked.copy(letterSpacing = 5.sp),
                color = colors.primary,
                modifier = Modifier.padding(top = 12.dp),
            )

            Spacer(Modifier.weight(1f))

            Dial(
                size = dialSize,
                active = state.active,
                remaining = remaining,
                caption = left?.let { remainingLabel(it) },
                onToggle = onToggle,
                label = stringResource(R.string.tile_label),
            )

            Spacer(Modifier.height(32.dp))
            Status(state)

            Spacer(Modifier.weight(1f))

            DurationPicker(selected = state.duration, onSelect = onDurationChange)
            AnimatedVisibility(
                visible = state.mode == Mode.TIMEOUT && state.duration > 0 && !state.preciseTimer,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Text(
                    stringResource(R.string.precise_timer).uppercase(),
                    style = Tracked.copy(fontSize = 11.sp, letterSpacing = 2.sp),
                    color = colors.primary,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onPreciseTimer)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }

            Spacer(Modifier.height(14.dp))
            BatteryGuard(on = state.batteryGuard, onChange = onBatteryGuardChange)
            Spacer(Modifier.height(18.dp))

            MethodSelector(selected = state.mode, onSelect = onModeChange)
            Spacer(Modifier.height(16.dp))
            MethodFooter(state, onGrant)

            AnimatedVisibility(
                visible = onAddTile != null && !state.tileAdded,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Row(
                    Modifier
                        .padding(top = 12.dp)
                        .clip(CircleShape)
                        .clickable { onAddTile?.invoke() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_add),
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        stringResource(R.string.add_tile).uppercase(),
                        style = Tracked,
                        color = colors.primary,
                    )
                }
            }
        }
    }
}

/**
 * Current time, re-read only when something visible changes: the next dial tick (1/60 of the
 * session) or the next whole minute of the label, whichever comes first. Nothing ticks without a
 * timer. When time is up, [onExpire] lets the app end the session even if its alarm is running late.
 */
@Composable
private fun rememberCountdownClock(startedAt: Long?, endsAt: Long?, onExpire: () -> Unit): Long {
    val expire by rememberUpdatedState(onExpire)
    val now by produceState(System.currentTimeMillis(), startedAt, endsAt) {
        if (endsAt == null) return@produceState
        val step = ((endsAt - (startedAt ?: endsAt)) / 60).coerceIn(1_000L, 60_000L)
        while (true) {
            value = System.currentTimeMillis()
            val left = endsAt - value
            if (left <= 0L) {
                expire()
                return@produceState
            }
            delay(left % step + 50L)
        }
    }
    return now
}

@Composable
private fun remainingLabel(leftMs: Long): String {
    val minutes = ((leftMs + 59_999L) / 60_000L).toInt()
    return when {
        minutes < 60 -> stringResource(R.string.left_min, minutes)
        minutes % 60 == 0 -> stringResource(R.string.left_hr, minutes / 60)
        else -> stringResource(R.string.left_hr_min, minutes / 60, minutes % 60)
    }
}

/**
 * A watch-style dial. Turning on sweeps gold light around the 60 minute ticks from 12 o'clock; with
 * a timer, the lit arc then drains back tick by tick as the session runs down.
 * Everything is drawn in the draw phase from animated values: no recomposition per frame, and no
 * animation at all while idle (this screen may stay visible for hours).
 */
@Composable
private fun Dial(
    size: Dp,
    active: Boolean,
    remaining: Float,
    caption: String?,
    onToggle: () -> Unit,
    label: String,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val progress = animateFloatAsState(
        if (active) 1f else 0f, tween(900, easing = FastOutSlowInEasing), label = "sweep"
    )
    val fill = animateFloatAsState(remaining, tween(700, easing = FastOutSlowInEasing), label = "fill")
    val scale = animateFloatAsState(
        if (pressed) 0.96f else 1f, spring(dampingRatio = 0.55f, stiffness = 600f), label = "press"
    )
    val glyphTint by animateColorAsState(
        if (active) colors.tertiary else colors.onSurfaceVariant, tween(500), label = "glyph"
    )
    val stateTint by animateColorAsState(
        if (active) colors.primary else colors.onSurfaceVariant, tween(500), label = "state"
    )

    val gold = colors.primary
    val bright = colors.tertiary
    val dim = colors.outline
    val disc = colors.surfaceContainer

    Box(
        Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .drawBehind {
                val p = progress.value
                val lit = p * fill.value
                val c = center
                val r = this.size.minDimension / 2 - 8.dp.toPx()

                if (p > 0f) {
                    drawCircle(
                        Brush.radialGradient(
                            0f to gold.copy(alpha = 0.20f * p),
                            0.7f to gold.copy(alpha = 0.06f * p),
                            1f to Color.Transparent,
                            center = c,
                            radius = r * 1.15f,
                        ),
                        radius = r * 1.15f,
                    )
                }

                // Minute ticks; each lights up as the sweep passes it, and dims as time runs out.
                val minor = 6.dp.toPx()
                val major = 13.dp.toPx()
                for (i in 0 until 60) {
                    val isMajor = i % 5 == 0
                    val len = if (isMajor) major else minor
                    val angle = Math.toRadians(i * 6.0 - 90.0)
                    val dx = cos(angle).toFloat()
                    val dy = sin(angle).toFloat()
                    val on = (lit * 60f - i).coerceIn(0f, 1f)
                    drawLine(
                        color = lerp(dim, if (isMajor) bright else gold, on),
                        start = Offset(c.x + dx * r, c.y + dy * r),
                        end = Offset(c.x + dx * (r - len), c.y + dy * (r - len)),
                        strokeWidth = (if (isMajor) 2.2.dp else 1.2.dp).toPx(),
                        cap = StrokeCap.Round,
                    )
                }

                // Inner face.
                val ir = r - 28.dp.toPx()
                drawCircle(disc, radius = ir)
                if (p > 0f) {
                    drawCircle(
                        Brush.radialGradient(
                            listOf(gold.copy(alpha = 0.14f * p), Color.Transparent),
                            center = Offset(c.x, c.y - ir * 0.35f),
                            radius = ir * 1.1f,
                        ),
                        radius = ir,
                    )
                }
                drawCircle(lerp(dim, gold, p), radius = ir, style = Stroke(1.dp.toPx()))
            }
            .toggleable(
                value = active,
                interactionSource = interaction,
                indication = null,
                role = Role.Switch,
            ) {
                haptics.performHapticFeedback(
                    if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff
                )
                onToggle()
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        val glyph = if (size < 240.dp) 52.dp else 64.dp
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(14.dp))
            Icon(
                painterResource(R.drawable.ic_screen_on),
                contentDescription = null,
                tint = glyphTint,
                modifier = Modifier.size(glyph),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(if (active) R.string.dial_on else R.string.dial_off).uppercase(),
                style = Tracked.copy(letterSpacing = 4.sp),
                color = stateTint,
            )
            // Fixed height so the glyph doesn't jump when the countdown appears.
            Box(Modifier.height(22.dp), contentAlignment = Alignment.BottomCenter) {
                AnimatedContent(
                    targetState = caption?.takeIf { active },
                    transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(150)) },
                    label = "caption",
                ) { text ->
                    if (text != null) {
                        Text(
                            text.uppercase(),
                            style = Tracked.copy(fontSize = 10.sp, letterSpacing = 2.sp),
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Status(state: KeepAwake.State) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    AnimatedContent(
        targetState = Triple(state.active, state.restoreTimeout, state.endsAt),
        transitionSpec = { fadeIn(tween(300, 120)) togetherWith fadeOut(tween(150)) },
        label = "status",
    ) { (on, restore, endsAt) ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(if (on) R.string.status_on else R.string.status_off),
                style = TextStyle(
                    fontFamily = FontFamily.Serif,
                    fontSize = 34.sp,
                    lineHeight = 40.sp,
                    letterSpacing = (-0.3).sp,
                ),
                color = colors.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            val until = endsAt?.let { KeepAwake.formatClock(context, it) }
            Text(
                when {
                    !on -> stringResource(R.string.hint_off)
                    until != null && restore != null -> stringResource(
                        R.string.hint_on_timeout_timed, until, KeepAwake.formatTimeout(context, restore)
                    )
                    until != null -> stringResource(R.string.hint_on_service_timed, until)
                    restore != null -> stringResource(
                        R.string.hint_on_timeout, KeepAwake.formatTimeout(context, restore)
                    )
                    else -> stringResource(R.string.hint_on_service)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Session length, laid out like a bezel: options over a hairline track, with a short gold bar that
 * glides under the chosen one.
 */
@Composable
private fun DurationPicker(selected: Int, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val options = KeepAwake.DURATIONS
    val position = animateFloatAsState(
        options.indexOf(selected).coerceAtLeast(0).toFloat(),
        spring(dampingRatio = 0.85f, stiffness = 450f),
        label = "duration",
    )
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.duration_label).uppercase(),
            style = Tracked.copy(fontSize = 11.sp),
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val segment = maxWidth / options.size
            val bar = 22.dp
            val density = LocalDensity.current
            val segmentPx = with(density) { segment.toPx() }
            val barPx = with(density) { bar.toPx() }

            Row(Modifier.fillMaxWidth().selectableGroup()) {
                options.forEach { minutes ->
                    val isSelected = minutes == selected
                    val tint by animateColorAsState(
                        if (isSelected) colors.primary else colors.onSurfaceVariant,
                        tween(250),
                        label = "durationLabel",
                    )
                    val description = when {
                        minutes == 0 -> stringResource(R.string.duration_forever)
                        minutes % 60 == 0 -> pluralStringResource(R.plurals.hours, minutes / 60, minutes / 60)
                        else -> pluralStringResource(R.plurals.minutes, minutes, minutes)
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .selectable(
                                selected = isSelected,
                                interactionSource = null,
                                indication = null,
                                role = Role.RadioButton,
                            ) {
                                if (!isSelected) {
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    onSelect(minutes)
                                }
                            }
                            .semantics { contentDescription = description },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (minutes == 0) {
                            // The app's own infinity glyph, so "until turned off" matches the dial.
                            Icon(
                                painterResource(R.drawable.ic_screen_on),
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(24.dp),
                            )
                        } else {
                            Text(
                                if (minutes % 60 == 0) {
                                    stringResource(R.string.duration_hr_short, minutes / 60)
                                } else {
                                    stringResource(R.string.duration_min_short, minutes)
                                }.uppercase(),
                                style = Tracked.copy(letterSpacing = 1.5.sp),
                                color = tint,
                                modifier = Modifier.clearAndSetSemantics {},
                            )
                        }
                    }
                }
            }
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.outline)
            )
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .offset {
                        IntOffset(
                            (segmentPx * position.value + (segmentPx - barPx) / 2).roundToInt(),
                            with(density) { 0.5.dp.roundToPx() },
                        )
                    }
                    .width(bar)
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(colors.primary)
            )
        }
    }
}

@Composable
private fun BatteryGuard(on: Boolean, onChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val iconTint by animateColorAsState(
        if (on) colors.primary else colors.onSurfaceVariant, tween(250), label = "batteryIcon"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .toggleable(value = on, role = Role.Switch) {
                haptics.performHapticFeedback(
                    if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff
                )
                onChange(it)
            }
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_battery),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.battery_guard_title),
                style = MaterialTheme.typography.bodyLarge.copy(letterSpacing = 0.2.sp),
                color = colors.onBackground,
            )
            Text(
                stringResource(R.string.battery_guard_desc, KeepAwake.BATTERY_THRESHOLD),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(14.dp))
        HairlineSwitch(on)
    }
}

/** The app's switch: a hairline capsule whose thumb glides over and turns gold. */
@Composable
private fun HairlineSwitch(on: Boolean) {
    val colors = MaterialTheme.colorScheme
    val t = animateFloatAsState(
        if (on) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = 500f), label = "switch"
    )
    val gold = colors.primary
    val line = colors.outline
    val off = colors.onSurfaceVariant
    Box(
        Modifier
            .size(width = 46.dp, height = 28.dp)
            .drawBehind {
                val v = t.value.coerceIn(0f, 1f)
                val stroke = 1.dp.toPx()
                val h = size.height
                val radius = CornerRadius(h / 2)
                drawRoundRect(gold.copy(alpha = 0.14f * v), cornerRadius = radius)
                drawRoundRect(
                    lerp(line, gold.copy(alpha = 0.7f), v),
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, h - stroke),
                    cornerRadius = radius,
                    style = Stroke(stroke),
                )
                val inset = h / 2
                drawCircle(
                    lerp(off, gold, v),
                    radius = h / 2 - 6.dp.toPx(),
                    center = Offset(inset + (size.width - 2 * inset) * t.value, h / 2),
                )
            }
    )
}

/** Hairline pill with a gold-outlined indicator that glides between options. */
@Composable
private fun MethodSelector(selected: Mode, onSelect: (Mode) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val modes = Mode.entries
    val position = animateFloatAsState(
        modes.indexOf(selected).toFloat(),
        spring(dampingRatio = 0.85f, stiffness = 450f),
        label = "indicator",
    )
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .border(1.dp, colors.outline, CircleShape)
            .padding(5.dp)
    ) {
        val segment = maxWidth / modes.size
        val segmentPx = with(LocalDensity.current) { segment.toPx() }
        Box(
            Modifier
                .offset { IntOffset((segmentPx * position.value).roundToInt(), 0) }
                .width(segment)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(colors.primary.copy(alpha = 0.12f))
                .border(1.dp, colors.primary.copy(alpha = 0.7f), CircleShape)
        )
        Row(Modifier.fillMaxSize().selectableGroup()) {
            modes.forEach { mode ->
                val isSelected = mode == selected
                val textColor by animateColorAsState(
                    if (isSelected) colors.primary else colors.onSurfaceVariant,
                    tween(250),
                    label = "label",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .selectable(
                            selected = isSelected,
                            interactionSource = null,
                            indication = null,
                            role = Role.Tab,
                        ) { onSelect(mode) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(
                            if (mode == Mode.TIMEOUT) R.string.mode_timeout else R.string.mode_service
                        ).uppercase(),
                        style = Tracked.copy(fontSize = 13.sp, letterSpacing = 2.5.sp),
                        color = textColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun MethodFooter(state: KeepAwake.State, onGrant: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val needsPermission = state.mode == Mode.TIMEOUT && !state.canWriteSettings
    AnimatedContent(
        targetState = state.mode to needsPermission,
        transitionSpec = { fadeIn(tween(240, 60)) togetherWith fadeOut(tween(100)) },
        label = "footer",
    ) { (mode, perm) ->
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(if (mode == Mode.TIMEOUT) R.string.desc_timeout else R.string.desc_service),
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            if (perm) {
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.grant_permission).uppercase(),
                    style = Tracked,
                    color = colors.primary,
                    modifier = Modifier
                        .clip(CircleShape)
                        .border(1.dp, colors.primary.copy(alpha = 0.7f), CircleShape)
                        .clickable(onClick = onGrant)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }
}
