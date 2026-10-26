// prism-rcs - an RCS-only Google Messages client for Android.
// Copyright (C) 2026 prism-rcs contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package dev.prism.rcs.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * A small Metro kit, drawn from scratch for this app: black pages, lowercase headings in a light
 * weight, sizes laid out on a 480-unit-wide phone (as the original design grid was), pages that
 * swing in, items that tilt when pressed, and the command bar at the foot with round icons and a
 * "..." that opens labels and a menu. Icons are simple shapes drawn here; no Microsoft assets.
 */

/** Accent colours offered in settings, as (name, colour). */
val Accents = listOf(
    "cobalt" to Color(0xFF0050EF), "blue" to Color(0xFF1BA1E2), "cyan" to Color(0xFF00ABA9),
    "teal" to Color(0xFF00897B), "emerald" to Color(0xFF008A00), "green" to Color(0xFF60A917),
    "lime" to Color(0xFFA4C400), "amber" to Color(0xFFF0A30A), "orange" to Color(0xFFFA6800),
    "red" to Color(0xFFE51400), "crimson" to Color(0xFFA20025), "magenta" to Color(0xFFD80073),
    "pink" to Color(0xFFF472D0), "violet" to Color(0xFFAA00FF), "indigo" to Color(0xFF6A00FF),
    "steel" to Color(0xFF647687),
)

class MetroTheme(val accent: Color, private val unit: Float, private val fontScale: Float) {
    val bg = Color.Black
    val fg = Color.White
    val subtle = Color(0xFF9A9A9A)
    val bar = Color(0xFF1F1F1F)
    val menuBg = Color.White
    val menuFg = Color.Black
    val font: FontFamily = FontFamily.SansSerif

    /** A length on the 480-unit grid. */
    fun dp(px: Float): Dp = (px * unit).dp

    /** A type size on the 480-unit grid; not scaled again by the system font size. */
    fun sp(px: Float): TextUnit = (px * unit / fontScale).sp
}

val LocalMetro = compositionLocalOf { MetroTheme(Accents[0].second, 0.85f, 1f) }

object Metro {
    val t: MetroTheme @Composable get() = LocalMetro.current
}

@Composable
fun MetroProvider(accent: Color, content: @Composable () -> Unit) {
    val conf = LocalConfiguration.current
    val density = LocalDensity.current
    val unit = minOf(conf.screenWidthDp, conf.screenHeightDp) / 480f
    val theme = remember(accent, unit, density.fontScale) { MetroTheme(accent, unit, density.fontScale) }
    CompositionLocalProvider(LocalMetro provides theme, content = content)
}

fun Color.lighter(k: Float = 0.45f) = Color(red + (1 - red) * k, green + (1 - green) * k, blue + (1 - blue) * k, alpha)
fun Color.darker(k: Float = 0.3f) = Color(red * (1 - k), green * (1 - k), blue * (1 - k), alpha)

@Composable
fun MText(
    text: String,
    size: Float,
    modifier: Modifier = Modifier,
    color: Color = Metro.t.fg,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = Int.MAX_VALUE,
    lineHeight: Float? = null,
) {
    val t = Metro.t
    BasicText(
        text, modifier,
        style = TextStyle(
            color = color, fontSize = t.sp(size), fontWeight = weight, fontFamily = t.font,
            lineHeight = lineHeight?.let { t.sp(it) } ?: TextUnit.Unspecified,
        ),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun Gap(px: Float) = Spacer(Modifier.size(Metro.t.dp(px)))

/** The small caps title over a page's big lowercase heading. */
@Composable
fun PageTitle(caps: String, heading: String?) {
    Gap(18f)
    MText(caps.uppercase(), 20f, weight = FontWeight.SemiBold)
    if (heading != null) MText(heading.lowercase(), 72f, weight = FontWeight.Light, maxLines = 1)
}

/**
 * Press feedback: the item tilts away from the finger (or sinks, pressed in the middle), then
 * [onTap]; holding calls [onHold] instead.
 */
fun Modifier.press(enabled: Boolean = true, onHold: (() -> Unit)? = null, onTap: () -> Unit): Modifier =
    if (!enabled) this else this.then(Modifier.tilting(onHold, onTap))

private fun Modifier.tilting(onHold: (() -> Unit)?, onTap: () -> Unit): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    var at by remember { mutableStateOf(Offset.Zero) }
    var w by remember { mutableStateOf(1f) }
    val p by animateFloatAsState(if (pressed) 1f else 0f, tween(if (pressed) 90 else 160), label = "tilt")
    this
        .graphicsLayer {
            w = size.width.coerceAtLeast(1f)
            // tilt toward where it's pressed: the far side lifts, the pressed side sinks
            val dx = ((at.x / w) - 0.5f) * 2f
            rotationY = -dx * 8f * p
            val s = 1f - 0.03f * p
            scaleX = s
            scaleY = s
            cameraDistance = 12f * density
        }
        .pointerInput(onTap, onHold) {
            detectTapGestures(
                onPress = { o ->
                    at = o
                    pressed = true
                    tryAwaitRelease()
                    pressed = false
                },
                onLongPress = onHold?.let { h -> { _: Offset -> h() } },
                onTap = { onTap() },
            )
        }
}

/** A page swinging in about its left edge, as Metro pages open. */
@Composable
fun SwingIn(key: Any, content: @Composable () -> Unit) {
    val p = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { p.animateTo(1f, tween(320, easing = FastOutSlowInEasing)) }
    Box(
        Modifier.graphicsLayer {
            rotationY = 60f * (1f - p.value)
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
            cameraDistance = 10f * density
            alpha = if (p.value < 0.05f) 0f else 1f
        },
    ) { content() }
}

// ---- icons, drawn as strokes in a unit box ----

enum class Icon { Send, Attach, Refresh, Settings, Back, Check, Close, Unlink }

private fun DrawScope.iconPath(icon: Icon, c: Color) {
    val s = size.minDimension
    val stroke = Stroke(width = s * 0.08f, cap = StrokeCap.Square)
    fun p(vararg pts: Float) = Path().apply {
        moveTo(pts[0] * s, pts[1] * s)
        var i = 2
        while (i < pts.size) { lineTo(pts[i] * s, pts[i + 1] * s); i += 2 }
    }
    when (icon) {
        // a paper plane pointing right
        Icon.Send -> {
            drawPath(p(0.1f, 0.15f, 0.92f, 0.5f, 0.1f, 0.85f, 0.25f, 0.5f, 0.1f, 0.15f), c, style = stroke)
            drawLine(c, Offset(0.25f * s, 0.5f * s), Offset(0.6f * s, 0.5f * s), s * 0.08f)
        }
        // a picture: frame with a hill and a sun
        Icon.Attach -> {
            drawPath(p(0.1f, 0.2f, 0.9f, 0.2f, 0.9f, 0.8f, 0.1f, 0.8f, 0.1f, 0.2f), c, style = stroke)
            drawPath(p(0.15f, 0.75f, 0.42f, 0.45f, 0.62f, 0.65f, 0.72f, 0.55f, 0.86f, 0.72f), c, style = stroke)
            drawCircle(c, s * 0.07f, Offset(0.68f * s, 0.36f * s))
        }
        Icon.Refresh -> {
            drawArc(c, 40f, 280f, false, Offset(0.18f * s, 0.18f * s), androidx.compose.ui.geometry.Size(0.64f * s, 0.64f * s), style = stroke)
            drawPath(p(0.66f, 0.12f, 0.8f, 0.3f, 0.58f, 0.36f), c, style = stroke)
        }
        // a gear: ring with teeth
        Icon.Settings -> {
            drawCircle(c, s * 0.2f, Offset(s / 2, s / 2), style = stroke)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val dx = kotlin.math.cos(a).toFloat()
                val dy = kotlin.math.sin(a).toFloat()
                drawLine(c, Offset(s / 2 + dx * s * 0.28f, s / 2 + dy * s * 0.28f), Offset(s / 2 + dx * s * 0.4f, s / 2 + dy * s * 0.4f), s * 0.1f)
            }
        }
        Icon.Back -> {
            drawPath(p(0.45f, 0.2f, 0.15f, 0.5f, 0.45f, 0.8f), c, style = stroke)
            drawLine(c, Offset(0.15f * s, 0.5f * s), Offset(0.85f * s, 0.5f * s), s * 0.08f)
        }
        Icon.Check -> drawPath(p(0.15f, 0.5f, 0.4f, 0.75f, 0.85f, 0.25f), c, style = stroke)
        Icon.Close -> {
            drawLine(c, Offset(0.2f * s, 0.2f * s), Offset(0.8f * s, 0.8f * s), s * 0.08f)
            drawLine(c, Offset(0.8f * s, 0.2f * s), Offset(0.2f * s, 0.8f * s), s * 0.08f)
        }
        // a chain link broken in two
        Icon.Unlink -> {
            drawPath(p(0.42f, 0.25f, 0.2f, 0.25f, 0.2f, 0.75f, 0.42f, 0.75f), c, style = stroke)
            drawPath(p(0.58f, 0.25f, 0.8f, 0.25f, 0.8f, 0.75f, 0.58f, 0.75f), c, style = stroke)
        }
    }
}

@Composable
fun Glyph(icon: Icon, size: Dp, color: Color) = Canvas(Modifier.size(size)) { iconPath(icon, color) }

class BarButton(val icon: Icon, val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * The command bar: round icon buttons in the middle and "..." at the right. Tapping "..." raises
 * the bar to show each button's label and the menu's items.
 */
@Composable
fun AppBar(buttons: List<BarButton>, menu: List<Pair<String, () -> Unit>> = emptyList(), modifier: Modifier = Modifier) {
    val t = Metro.t
    var open by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().background(t.bar).navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(t.dp(if (open) 100f else 72f))) {
            Spacer(Modifier.weight(1f))
            buttons.forEach { b ->
                Column(
                    Modifier.width(t.dp(96f)).padding(top = t.dp(10f)).press(enabled = b.enabled) { open = false; b.onClick() },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val c = if (b.enabled) t.fg else t.subtle.darker(0.4f)
                    Box(
                        Modifier.size(t.dp(48f)).drawCircleOutline(c, t.dp(2.5f)),
                        contentAlignment = Alignment.Center,
                    ) { Glyph(b.icon, t.dp(24f), c) }
                    if (open) { Gap(4f); MText(b.label, 16f, color = c, maxLines = 1) }
                }
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.width(t.dp(64f)).height(t.dp(40f)).press { open = !open }, contentAlignment = Alignment.TopCenter) {
                MText("•••", 16f, Modifier.padding(top = t.dp(8f)))
            }
        }
        if (open && menu.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(bottom = t.dp(16f))) {
                menu.forEach { (label, action) ->
                    MText(
                        label.lowercase(), 28f,
                        Modifier.fillMaxWidth().press { open = false; action() }.padding(horizontal = t.dp(24f), vertical = t.dp(10f)),
                    )
                }
            }
        }
    }
}

fun Modifier.drawCircleOutline(c: Color, width: Dp): Modifier =
    drawBehind { drawCircle(c, size.minDimension / 2 - width.toPx() / 2, style = Stroke(width.toPx())) }

/** A plain Metro button: a white outline with the label inside. */
@Composable
fun MButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val t = Metro.t
    val c = if (enabled) t.fg else t.subtle
    Box(
        modifier
            .press(enabled = enabled, onTap = onClick)
            .drawBehind { drawRect(c, style = Stroke(t.dp(3f).toPx())) }
            .padding(horizontal = t.dp(20f), vertical = t.dp(10f)),
        contentAlignment = Alignment.Center,
    ) { MText(label.lowercase(), 22f, color = c) }
}
