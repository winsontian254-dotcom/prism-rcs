// prism-rcs - an RCS-only Google Messages client for Android.
// Copyright (C) 2026 prism-rcs contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package dev.prism.rcs.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.googlefonts.Font as GoogleFontFile

/*
 * A small Metro kit, drawn from scratch for this app and dressed in whatever look Prism Launcher
 * hands over ([Style]): its light or dark theme, accent, font and text size, headings in lower or
 * sentence case, sizes on a 480-unit-wide phone, pages that swing in, items that tilt when pressed,
 * and the command bar in the style's own form. Icons are simple shapes drawn here; no Microsoft
 * assets.
 */

class MetroTheme(val style: Style, private val unit: Float, val font: FontFamily) {
    val dark = style.dark
    val accent = Color(style.accent)
    val bg = if (dark) Color.Black else Color.White
    val fg = if (dark) Color.White else Color.Black
    val subtle = if (dark) Color(0x99FFFFFF) else Color(0x99000000)
    val disabled = if (dark) Color(0x66FFFFFF) else Color(0x4D000000)
    val chrome = if (dark) Color(0xFF1F1F1F) else Color(0xFFDDDDDD)
    /** Menus are the theme turned around: white on dark, black on light. */
    val menuBg = fg
    val menuFg = bg
    /** Names and new things in the accent, lightened on dark as 8.1 draws them. */
    val hot = if (dark) accent.lighter() else accent
    val ten = style.messaging == "10"
    val wp8 = style.messaging == "8"
    val iconWeight = when (style.phone) { "wp7" -> 1.25f; "wp8" -> 1.1f; "w10m" -> 0.75f; else -> 1f }

    /** A length on the 480-unit grid. */
    fun dp(px: Float): Dp = (px * unit).dp

    /** A type size on the 480-unit grid, times the launcher's text size. */
    fun sp(px: Float): TextUnit = (px * unit * style.textScale).sp

    /** A heading as the style writes it: all lower case, or in sentence case (10 Mobile). */
    fun heading(text: String): String =
        if (style.lowercase) text.lowercase() else text.replaceFirstChar { it.uppercaseChar() }

    /** An animation's length, at the launcher's speed. */
    fun ms(base: Int): Int = (base / style.speed).toInt().coerceAtLeast(1)
}

val LocalMetro = staticCompositionLocalOf { MetroTheme(Style(), 0.85f, FontFamily.Default) }

object Metro {
    val t: MetroTheme @Composable get() = LocalMetro.current
}

@Composable
fun MetroProvider(style: Style, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val unit = LocalConfiguration.current.screenWidthDp / 480f
    val font = remember(style.font) { Fonts.family(context, style.font) }
    val theme = remember(style, unit, font) { MetroTheme(style, unit, font) }
    CompositionLocalProvider(LocalMetro provides theme, content = content)
}

/** The launcher's font choices: the phone's own font, or a family from the Google Fonts provider. */
object Fonts {
    private val cache = HashMap<String, FontFamily>()

    fun family(context: Context, name: String): FontFamily = synchronized(cache) {
        cache.getOrPut(name) { build(context, name) }
    }

    /** The phone's default typeface at one weight, so a font set on the phone carries over. */
    private class PhoneFont(override val weight: FontWeight) :
        AndroidFont(FontLoadingStrategy.Blocking, Loader, FontVariation.Settings()) {
        override val style = FontStyle.Normal

        object Loader : AndroidFont.TypefaceLoader {
            override fun loadBlocking(context: Context, font: AndroidFont): android.graphics.Typeface =
                android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, font.weight.weight, false)

            override suspend fun awaitLoad(context: Context, font: AndroidFont) = loadBlocking(context, font)
        }
    }

    private fun build(context: Context, name: String): FontFamily {
        // "custom" is a file inside the launcher, which this app can't read: the phone's font stands in
        if (name == "system" || name == "custom") return FontFamily((1..9).map { PhoneFont(FontWeight(it * 100)) })
        val light = if (name == "open sans") FontWeight.Light else FontWeight.ExtraLight
        val fallback = listOf(
            Font(DeviceFontFamilyName("sans-serif-light"), light),
            Font(DeviceFontFamilyName("sans-serif"), FontWeight.Normal),
            Font(DeviceFontFamilyName("sans-serif-medium"), FontWeight.SemiBold),
        )
        val certs = gmsCertificates(context) ?: return FontFamily(fallback)
        val provider = GoogleFont.Provider("com.google.android.gms.fonts", "com.google.android.gms", certs)
        val g = GoogleFont(name.split(' ').joinToString(" ") { w -> if (w == "ibm") "IBM" else w.replaceFirstChar { it.uppercase() } })
        return FontFamily(
            listOf(GoogleFontFile(g, provider, light), GoogleFontFile(g, provider, FontWeight.Normal), GoogleFontFile(g, provider, FontWeight.SemiBold)) + fallback,
        )
    }

    @Suppress("DEPRECATION")
    private fun gmsCertificates(context: Context): List<List<ByteArray>>? = try {
        val info = context.packageManager.getPackageInfo("com.google.android.gms", PackageManager.GET_SIGNATURES)
        info.signatures?.map { it.toByteArray() }?.takeIf { it.isNotEmpty() }?.let { listOf(it) }
    } catch (e: Exception) {
        null
    }
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
    align: TextAlign = TextAlign.Unspecified,
) {
    val t = Metro.t
    BasicText(
        text, modifier,
        style = TextStyle(
            color = color, fontSize = t.sp(size), fontWeight = weight, fontFamily = t.font, textAlign = align,
            lineHeight = lineHeight?.let { t.sp(it) } ?: TextUnit.Unspecified,
        ),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun Gap(px: Float) = Spacer(Modifier.size(Metro.t.dp(px)))

/** The small caps title over a page's big heading, both written as the style writes them. */
@Composable
fun PageTitle(caps: String, heading: String?) {
    val t = Metro.t
    Gap(18f)
    MText(if (t.ten) t.heading(caps) else caps.uppercase(), 20f, weight = FontWeight.SemiBold)
    if (heading != null) MText(t.heading(heading), if (t.ten) 46f else 72f, weight = FontWeight.Light, maxLines = 1)
}

/**
 * Press feedback: the item tilts away from the finger (unless the launcher has tilt off), then
 * [onTap]; holding calls [onHold] instead.
 */
fun Modifier.press(enabled: Boolean = true, onHold: (() -> Unit)? = null, onTap: () -> Unit): Modifier =
    if (!enabled) this else this.then(Modifier.tilting(onHold, onTap))

private fun Modifier.tilting(onHold: (() -> Unit)?, onTap: () -> Unit): Modifier = composed {
    val tilt = Metro.t.style.tilt
    var pressed by remember { mutableStateOf(false) }
    var at by remember { mutableStateOf(Offset.Zero) }
    val p by animateFloatAsState(if (pressed && tilt) 1f else 0f, tween(if (pressed) 90 else 160), label = "tilt")
    this
        .graphicsLayer {
            val w = size.width.coerceAtLeast(1f)
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

/** A page swinging in about its left edge, as Metro pages open (unless the launcher has it off). */
@Composable
fun SwingIn(key: Any, content: @Composable () -> Unit) {
    val t = Metro.t
    val p = remember(key) { Animatable(if (t.style.turnstile) 0f else 1f) }
    LaunchedEffect(key) { p.animateTo(1f, tween(t.ms(320), easing = FastOutSlowInEasing)) }
    Box(
        Modifier.graphicsLayer {
            rotationY = 60f * (1f - p.value)
            transformOrigin = TransformOrigin(0f, 0.5f)
            cameraDistance = 10f * density
            alpha = if (p.value < 0.05f) 0f else 1f
        },
    ) { content() }
}

// ---- icons, drawn as strokes in a unit box ----

enum class Icon { Send, Attach, Refresh, Settings, Back, Check, Close, Unlink }

private fun DrawScope.iconPath(icon: Icon, c: Color, weight: Float) {
    val s = size.minDimension
    val w = s * 0.08f * weight
    val stroke = Stroke(width = w, cap = StrokeCap.Square)
    fun p(vararg pts: Float) = Path().apply {
        moveTo(pts[0] * s, pts[1] * s)
        var i = 2
        while (i < pts.size) { lineTo(pts[i] * s, pts[i + 1] * s); i += 2 }
    }
    when (icon) {
        // a paper plane pointing right
        Icon.Send -> {
            drawPath(p(0.1f, 0.15f, 0.92f, 0.5f, 0.1f, 0.85f, 0.25f, 0.5f, 0.1f, 0.15f), c, style = stroke)
            drawLine(c, Offset(0.25f * s, 0.5f * s), Offset(0.6f * s, 0.5f * s), w)
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
                drawLine(c, Offset(s / 2 + dx * s * 0.28f, s / 2 + dy * s * 0.28f), Offset(s / 2 + dx * s * 0.4f, s / 2 + dy * s * 0.4f), w * 1.25f)
            }
        }
        Icon.Back -> {
            drawPath(p(0.45f, 0.2f, 0.15f, 0.5f, 0.45f, 0.8f), c, style = stroke)
            drawLine(c, Offset(0.15f * s, 0.5f * s), Offset(0.85f * s, 0.5f * s), w)
        }
        Icon.Check -> drawPath(p(0.15f, 0.5f, 0.4f, 0.75f, 0.85f, 0.25f), c, style = stroke)
        Icon.Close -> {
            drawLine(c, Offset(0.2f * s, 0.2f * s), Offset(0.8f * s, 0.8f * s), w)
            drawLine(c, Offset(0.8f * s, 0.2f * s), Offset(0.2f * s, 0.8f * s), w)
        }
        // a chain link broken in two
        Icon.Unlink -> {
            drawPath(p(0.42f, 0.25f, 0.2f, 0.25f, 0.2f, 0.75f, 0.42f, 0.75f), c, style = stroke)
            drawPath(p(0.58f, 0.25f, 0.8f, 0.25f, 0.8f, 0.75f, 0.58f, 0.75f), c, style = stroke)
        }
    }
}

@Composable
fun Glyph(icon: Icon, size: Dp, color: Color) {
    val weight = Metro.t.iconWeight
    Canvas(Modifier.size(size)) { iconPath(icon, color, weight) }
}

class BarButton(val icon: Icon, val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * The command bar, in the launcher's form: rings on a grey band (7, 8), rings on a see-through
 * tint (8.1), or bare glyphs at the right (10 Mobile). "..." opens each button's label and the
 * menu; with no buttons it's a thin strip holding just "...".
 */
@Composable
fun AppBar(buttons: List<BarButton>, menu: List<Pair<String, () -> Unit>> = emptyList(), modifier: Modifier = Modifier) {
    val t = Metro.t
    var open by remember { mutableStateOf(false) }
    if (buttons.isEmpty() && menu.isEmpty()) return
    val bare = t.style.appBar == "GLYPHS"
    val labels = open || t.style.appBarLabels
    val ground = when (t.style.appBar) {
        "RINGS_TINT" -> (if (t.dark) Color(0xFF101010) else t.chrome).copy(alpha = 0.8f)
        else -> t.chrome
    }
    val strip = buttons.isEmpty()
    Column(modifier.fillMaxWidth().background(ground).navigationBarsPadding().animateContentSize(tween(t.ms(100)))) {
        val head = when {
            bare -> if (strip) 32f else if (labels) 96f else 55.6f
            strip -> 34f
            labels -> 126.8f
            else -> 84f
        }
        Box(Modifier.fillMaxWidth().height(t.dp(head))) {
            if (bare) {
                val row = if (strip) 32f else 55.6f
                buttons.forEachIndexed { i, b ->
                    // glyphs right-aligned 78 apart, the last one 67 left of the "..."
                    val cx = 462.8f - 67f - (buttons.size - 1 - i) * 78.1f
                    val c = if (b.enabled) t.fg else t.disabled
                    Column(
                        Modifier.offset(x = t.dp(cx - 39f)).width(t.dp(78f)).fillMaxHeight().press(enabled = b.enabled) { open = false; b.onClick() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.height(t.dp(row)), contentAlignment = Alignment.Center) { Glyph(b.icon, t.dp(27f), c) }
                        if (labels) MText(t.heading(b.label), 16f, color = c, maxLines = 2, align = TextAlign.Center)
                    }
                }
                Dots(Modifier.align(Alignment.TopEnd).size(t.dp(48f), t.dp(row)), centreX = 30.8f / 48f) { open = !open }
            } else {
                Row(Modifier.align(Alignment.TopCenter).padding(top = t.dp(18f))) {
                    buttons.forEach { b ->
                        val c = if (b.enabled) t.fg else t.disabled
                        Column(
                            Modifier.width(t.dp(85.5f)).press(enabled = b.enabled) { open = false; b.onClick() },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.size(t.dp(48f)).drawCircleOutline(c, t.dp(2.5f * t.iconWeight)), contentAlignment = Alignment.Center) {
                                Glyph(b.icon, t.dp(24f), c)
                            }
                            if (labels) {
                                Gap(6f)
                                MText(t.heading(b.label), 15.8f, color = c, weight = FontWeight.SemiBold, maxLines = 2, align = TextAlign.Center)
                            }
                        }
                    }
                }
                Dots(Modifier.align(Alignment.TopEnd).size(t.dp(64f), t.dp(if (strip) 30f else 36f)), centreX = 0.67f) { open = !open }
            }
        }
        if (open && menu.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(bottom = t.dp(16f))) {
                menu.forEach { (label, action) ->
                    MText(
                        t.heading(label), if (bare) 22f else 28f,
                        Modifier.fillMaxWidth().press { open = false; action() }
                            .padding(horizontal = t.dp(if (bare) 16f else 24f), vertical = t.dp(if (bare) 12f else 10f)),
                    )
                }
            }
        }
    }
}

/** "...": three small dots, centred at [centreX] of the box's width. */
@Composable
private fun Dots(modifier: Modifier, centreX: Float, onTap: () -> Unit) {
    val c = Metro.t.fg
    Box(modifier.press(onTap = onTap)) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.height * 0.05f
            val cy = if (size.height > size.width * 0.6f) size.height * 0.33f else size.height / 2
            for (k in -1..1) drawCircle(c, r.coerceAtLeast(2f), Offset(size.width * centreX + k * r * 4f, cy))
        }
    }
}

fun Modifier.drawCircleOutline(c: Color, width: Dp): Modifier =
    drawBehind { drawCircle(c, size.minDimension / 2 - width.toPx() / 2, style = Stroke(width.toPx())) }

/** A plain Metro button: an outline in the theme's colour with the label inside. */
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
    ) { MText(t.heading(label), 22f, color = c) }
}
