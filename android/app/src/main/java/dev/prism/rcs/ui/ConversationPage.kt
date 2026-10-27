// prism-rcs - an RCS-only Google Messages client for Android.
// Copyright (C) 2026 prism-rcs contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package dev.prism.rcs.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import dev.prism.rcs.Conversation
import dev.prism.rcs.Media
import dev.prism.rcs.Message
import dev.prism.rcs.Reaction
import dev.prism.rcs.Repo

/** The six reactions Google Messages offers, in its order. */
val ReactionEmoji = listOf("👍", "❤️", "😂", "😮", "😢", "😠")

/**
 * One conversation, in the launcher's Messaging design. 8.1 (the default): the name in small
 * caps, square bubbles stacked up from the bottom (theirs in the accent with the tail up at the
 * left, yours darker with the tail down at the right) and a grey box for a new message pinned at
 * the foot. 7 and 8: "name, Chat" over a "Chat" line, yours in the full colour and theirs darker,
 * a white box. 10 Mobile: small bubbles only as wide as their words over a plain message row.
 */
@Composable
fun ConversationPage(repo: Repo, conv: Conversation, typing: Boolean, note: String?, onForward: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val t = Metro.t
    val msgs by remember(conv.id) { repo.messages(conv.id) }.collectAsState()
    var draft by remember { mutableStateOf("") }
    var held by remember { mutableStateOf<Pair<Message, Rect>?>(null) }
    val list = rememberLazyListState()
    val shown = remember(msgs) { msgs.filter { !it.deleted }.reversed() }
    val ten = t.ten
    val wp8 = t.wp8

    // newest at the foot: keep it in view as messages arrive
    LaunchedEffect(shown.firstOrNull()?.id) { if (shown.isNotEmpty()) list.scrollToItem(0) }
    // read receipt for the newest message from them
    LaunchedEffect(shown.firstOrNull { !it.fromMe }?.id) {
        shown.firstOrNull { !it.fromMe }?.let { repo.markRead(conv.id, it.id) }
    }
    // older messages when scrolled to the top
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index }.collect { last ->
            if (last != null && last >= shown.size - 3 && shown.isNotEmpty()) repo.olderMessages(conv.id)
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val cr = context.contentResolver
        val mime = cr.getType(uri) ?: "image/jpeg"
        val bytes = runCatching { cr.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return@rememberLauncherForActivityResult
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
        repo.sendMedia(conv.id, bytes, name, mime)
    }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty() || conv.readOnly) return
        repo.sendText(conv.id, text)
        draft = ""
    }
    fun attach() = pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    val onDraft: (String) -> Unit = { draft = it; if (it.isNotEmpty()) repo.typingNow(conv.id) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            when {
                // 10 Mobile: the name small and bold in the accent
                ten -> MText(conv.title, 18f, Modifier.padding(start = t.dp(12f), top = t.dp(10f)), color = t.accent, weight = FontWeight.Bold, maxLines = 1)
                // 7 and 8: who and how, then the line saying how it goes
                wp8 -> {
                    Gap(18f)
                    MText("${conv.title}, Chat", 22f, Modifier.padding(horizontal = t.dp(24f)), weight = FontWeight.Bold, maxLines = 1)
                    Gap(14f)
                    MText("Chat", 19f, Modifier.padding(horizontal = t.dp(24f)))
                    Gap(10f)
                }
                else -> {
                    Gap(18f)
                    MText(conv.title.uppercase(), 20f, Modifier.padding(horizontal = t.dp(24f)), weight = FontWeight.SemiBold, maxLines = 1)
                    Gap(12f)
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(horizontal = t.dp(if (ten) 12f else 24f))) {
                val inset = maxWidth * if (ten) 0.3f else 0.2f
                LazyColumn(Modifier.fillMaxSize(), state = list, reverseLayout = true, verticalArrangement = Arrangement.Bottom) {
                    if (typing) item(key = "typing") {
                        MText(t.heading("typing…"), 18f, Modifier.padding(vertical = t.dp(4f)), color = t.subtle)
                    }
                    items(shown, key = { it.id }) { m ->
                        val hold: (Rect) -> Unit = { at -> held = m to at }
                        if (ten) Bubble10(repo, m, conv.isGroup, inset, hold) else Bubble(repo, m, conv.isGroup, inset, wp8, hold)
                    }
                }
            }
            note?.let { MText(it, 17f, Modifier.padding(horizontal = t.dp(24f), vertical = t.dp(4f)), color = t.subtle, maxLines = 2) }
            if (ten) {
                Composer10(draft, onDraft, conv.readOnly, ::send, ::attach)
                AppBar(buttons = emptyList(), menu = listOf("back to threads" to onBack))
            } else {
                Box(Modifier.padding(horizontal = t.dp(24f))) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        Composer(draft, onDraft, maxWidth * 0.2f, conv.readOnly, white = wp8, onSend = ::send)
                    }
                }
                AppBar(
                    buttons = listOf(
                        BarButton(Icon.Send, "send", enabled = draft.isNotBlank() && !conv.readOnly) { send() },
                        BarButton(Icon.Attach, "attach", enabled = !conv.readOnly) { attach() },
                    ),
                    menu = listOf("back to threads" to onBack),
                )
            }
        }
        held?.let { (m, at) ->
            MessageMenu(
                at, onDismiss = { held = null },
                react = { repo.react(m, it) },
                copy = { copy(context, m.text) },
                forward = { onForward(m.text) },
                delete = { repo.delete(m) },
                canReact = !m.pending,
                canForward = m.text.isNotBlank(),
            )
        }
    }
}

private fun copy(context: Context, text: String) =
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("message", text))

/** A small bubble of reactions just over the message, lighter than it, with a thin edge. */
@Composable
private fun ReactionBubble(reactions: List<Reaction>, fill: Color, small: Boolean = false) {
    val t = Metro.t
    val text = reactions.joinToString("  ") { if (it.count > 1) "${it.emoji} ${it.count}" else it.emoji }
    Box(
        Modifier.background(fill.lighter(0.15f)).border(t.dp(1f), Color.White.copy(alpha = 0.25f))
            .padding(horizontal = t.dp(if (small) 7f else 10f), vertical = t.dp(if (small) 2f else 4f)),
    ) { MText(text, if (small) 16f else 20f, color = Color.White) }
    Gap(4f)
}

/** 8.1's (and, with [wp8], 7 and 8's) bubble: full width, square, with a tail. */
@Composable
private fun Bubble(repo: Repo, m: Message, group: Boolean, inset: Dp, wp8: Boolean, onHold: (Rect) -> Unit) {
    val t = Metro.t
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val theirs = !m.fromMe
    // 8.1 darkens yours; 7 and 8 have yours in the full colour and theirs darker
    val fill = if (wp8) (if (theirs) t.accent.darker() else t.accent) else if (theirs) t.accent else t.accent.darker()
    val tail = t.dp(14f)
    val side = if (theirs) Modifier.padding(end = inset) else Modifier.padding(start = inset)
    Column(Modifier.fillMaxWidth().padding(vertical = t.dp(6f)).then(side), horizontalAlignment = if (theirs) Alignment.Start else Alignment.End) {
        if (m.reactions.isNotEmpty()) ReactionBubble(m.reactions, fill)
        if (theirs) Canvas(Modifier.padding(start = t.dp(14f)).size(tail)) {
            drawPath(Path().apply { moveTo(0f, 0f); lineTo(0f, size.height); lineTo(size.width, size.height); close() }, fill)
        }
        Box(
            Modifier.fillMaxWidth().background(fill)
                .onGloballyPositioned { bounds = it.boundsInWindow() }
                .press(onHold = { onHold(bounds) }) {}
                .padding(start = t.dp(14f), end = t.dp(14f), top = t.dp(10f), bottom = t.dp(8f)),
        ) {
            Column(Modifier.fillMaxWidth()) {
                if (group && theirs && m.senderName.isNotEmpty()) {
                    MText(m.senderName, 17f, color = Color.White.copy(alpha = 0.75f), weight = FontWeight.SemiBold, maxLines = 1)
                    Gap(2f)
                }
                m.media.forEach { media -> Attachment(repo, media) }
                if (m.text.isNotEmpty()) MText(m.text, if (wp8) 20f else 21f, color = Color.White, lineHeight = if (wp8) 27f else 29f)
                Gap(6f)
                val status = when {
                    m.failed -> "not sent"
                    m.sending -> "sending…"
                    else -> clock(m.timestamp)
                }
                MText(status, if (wp8) 18f else 16f, Modifier.align(Alignment.End), color = Color.White.copy(alpha = if (wp8) 0.6f else 0.7f))
            }
        }
        if (!theirs) Canvas(Modifier.padding(end = t.dp(14f)).size(tail)) {
            drawPath(Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height); close() }, fill)
        }
    }
}

/**
 * 10 Mobile's bubble: small type, only as wide as its words, the time under them, and a small
 * tail at the bottom (left for theirs, right for yours).
 */
@Composable
private fun Bubble10(repo: Repo, m: Message, group: Boolean, inset: Dp, onHold: (Rect) -> Unit) {
    val t = Metro.t
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val theirs = !m.fromMe
    val fill = if (theirs) t.accent else t.accent.darker(0.45f)
    val side = if (theirs) Modifier.padding(end = inset) else Modifier.padding(start = inset)
    Column(Modifier.fillMaxWidth().padding(vertical = t.dp(4f)).then(side), horizontalAlignment = if (theirs) Alignment.Start else Alignment.End) {
        if (m.reactions.isNotEmpty()) ReactionBubble(m.reactions, fill, small = true)
        Column(
            Modifier.background(fill)
                .onGloballyPositioned { bounds = it.boundsInWindow() }
                .press(onHold = { onHold(bounds) }) {}
                .padding(start = t.dp(10f), end = t.dp(14f), top = t.dp(7f), bottom = t.dp(7f)),
        ) {
            if (group && theirs && m.senderName.isNotEmpty()) {
                MText(m.senderName, 14f, color = Color.White.copy(alpha = 0.8f), weight = FontWeight.SemiBold, maxLines = 1)
            }
            m.media.forEach { media -> Box(Modifier.width(t.dp(240f))) { Attachment(repo, media) } }
            if (m.text.isNotEmpty()) MText(m.text, 17f, color = Color.White, lineHeight = 22f)
            when {
                m.failed -> MText("Couldn't send", 14f, color = Color.White)
                m.sending -> MText("Sending…", 14f, color = Color.White)
                else -> {}
            }
            MText(clock(m.timestamp), 14f, color = Color.White)
        }
        Canvas(Modifier.padding(horizontal = t.dp(12f)).size(t.dp(9f))) {
            val path = if (theirs) Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(0f, size.height); close() }
            else Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height); close() }
            drawPath(path, fill)
        }
    }
}

@Composable
private fun Attachment(repo: Repo, media: Media) {
    if (!media.isImage) {
        MText("[${media.mime.substringBefore('/').ifEmpty { "file" }}] ${media.name}", 19f, color = Color.White)
        Gap(6f)
        return
    }
    val bitmap by produceState<android.graphics.Bitmap?>(null, media.mediaId) { value = repo.image(media) }
    val ratio = if (media.width > 0 && media.height > 0) media.width.toFloat() / media.height else 4f / 3f
    Box(
        Modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(0.5f, 2.5f)).background(Color.Black.copy(alpha = 0.2f)),
    ) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
    Gap(8f)
}

/**
 * Hold a message: the page dims and a menu in the theme's contrast colour swings open just under
 * it (or over it, near the foot): a row of reactions, then copy, forward and delete.
 */
@Composable
private fun MessageMenu(
    at: Rect, onDismiss: () -> Unit, react: (String) -> Unit, copy: () -> Unit, forward: () -> Unit, delete: () -> Unit,
    canReact: Boolean, canForward: Boolean,
) {
    val t = Metro.t
    val p = remember(at) { Animatable(0f) }
    LaunchedEffect(at) { p.animateTo(1f, tween(t.ms(220), easing = FastOutSlowInEasing)) }
    var menuH by remember { mutableIntStateOf(0) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    fun go(action: () -> Unit) { onDismiss(); action() }
    BoxWithConstraints(
        Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }
            .background(t.bg.copy(alpha = 0.6f * p.value)).press { onDismiss() },
    ) {
        val h = constraints.maxHeight.toFloat()
        val below = (at.bottom - origin.y) + menuH < h * 0.85f
        val y = if (below) at.bottom - origin.y else (at.top - origin.y - menuH).coerceAtLeast(0f)
        Column(
            Modifier
                .offset { IntOffset(0, y.toInt()) }
                .fillMaxWidth()
                .onSizeChanged { menuH = it.height }
                .graphicsLayer {
                    alpha = if (menuH == 0) 0f else 1f
                    rotationX = (if (below) -1f else 1f) * 70f * (1f - p.value)
                    transformOrigin = TransformOrigin(0.5f, if (below) 0f else 1f)
                    cameraDistance = 12f * density
                }
                .background(t.menuBg)
                .press { }
                .padding(vertical = t.dp(12f)),
        ) {
            if (canReact) Row(Modifier.fillMaxWidth().padding(horizontal = t.dp(12f)), horizontalArrangement = Arrangement.SpaceBetween) {
                ReactionEmoji.forEach { emoji ->
                    Box(Modifier.size(t.dp(66f)).press { go { react(emoji) } }, contentAlignment = Alignment.Center) { MText(emoji, 32f) }
                }
            }
            buildList {
                add("copy" to copy)
                if (canForward) add("forward" to forward)
                add("delete" to delete)
            }.forEach { (label, action) ->
                MText(
                    t.heading(label), if (t.ten) 22f else 30f,
                    Modifier.fillMaxWidth().press { go(action) }.padding(horizontal = t.dp(24f), vertical = t.dp(12f)),
                    color = t.menuFg,
                )
            }
        }
    }
}

/**
 * The box for a new message, set at the right like a bubble of your own: 8.1's is grey with a hint
 * and turns white while typing; 7 and 8's ([white]) is plain white throughout.
 */
@Composable
private fun Composer(value: String, onChange: (String) -> Unit, inset: Dp, readOnly: Boolean, white: Boolean, onSend: () -> Unit) {
    val t = Metro.t
    var focused by remember { mutableStateOf(false) }
    val fill = if (focused || white) Color.White else Color(0xFFBFBFBF)
    Column(Modifier.fillMaxWidth().padding(start = inset, top = t.dp(6f)), horizontalAlignment = Alignment.End) {
        // on a light page a white box needs an edge to be seen
        val edge = if (!t.dark && fill == Color.White) Modifier.border(t.dp(1.5f), Color(0xFFBFBFBF)) else Modifier
        Box(Modifier.fillMaxWidth().background(fill).then(edge).padding(horizontal = t.dp(14f), vertical = t.dp(10f))) {
            if (value.isEmpty() && (readOnly || !white)) {
                MText(if (readOnly) "you can't reply to this chat" else "type a chat message", 22f, color = Color(0xFF7A7A7A))
            }
            BasicTextField(
                value, onChange,
                Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                enabled = !readOnly,
                textStyle = TextStyle(color = Color.Black, fontSize = t.sp(22f), fontFamily = t.font),
                cursorBrush = SolidColor(t.accent),
                maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
        }
        Canvas(Modifier.padding(end = t.dp(14f)).size(t.dp(14f))) {
            drawPath(Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height); close() }, fill)
        }
    }
}

/** 10 Mobile's: a hairline, then the attach button, the box and the send arrow in one row. */
@Composable
private fun Composer10(value: String, onChange: (String) -> Unit, readOnly: Boolean, onSend: () -> Unit, onAttach: () -> Unit) {
    val t = Metro.t
    Box(Modifier.fillMaxWidth().height(t.dp(1f)).background(if (t.dark) Color(0xFF2B2B2B) else Color(0xFFCCCCCC)))
    Row(Modifier.fillMaxWidth().padding(vertical = t.dp(4f)), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(t.dp(52f)).press(enabled = !readOnly, onTap = onAttach), contentAlignment = Alignment.Center) {
            Glyph(Icon.Attach, t.dp(24f), if (readOnly) t.disabled else t.fg)
        }
        Box(Modifier.weight(1f).padding(vertical = t.dp(10f))) {
            if (value.isEmpty()) MText(if (readOnly) "You can't reply to this chat" else "Type a message", 18f, color = t.subtle)
            BasicTextField(
                value, onChange, Modifier.fillMaxWidth(),
                enabled = !readOnly,
                textStyle = TextStyle(color = t.fg, fontSize = t.sp(18f), fontFamily = t.font),
                cursorBrush = SolidColor(t.accent),
                maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
        }
        val ready = value.isNotBlank() && !readOnly
        Box(Modifier.size(t.dp(52f)).press(enabled = ready, onTap = onSend), contentAlignment = Alignment.Center) {
            Glyph(Icon.Send, t.dp(24f), if (ready) t.fg else t.disabled)
        }
    }
}
