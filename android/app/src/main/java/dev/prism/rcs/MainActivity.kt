// prism-rcs - an RCS-only Google Messages client for Android.
// Copyright (C) 2026 prism-rcs contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package dev.prism.rcs

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.prism.rcs.ui.Metro
import dev.prism.rcs.ui.Style
import dev.prism.rcs.ui.AppBar
import dev.prism.rcs.ui.BarButton
import dev.prism.rcs.ui.ConversationPage
import dev.prism.rcs.ui.GoogleSignInPage
import dev.prism.rcs.ui.Icon
import dev.prism.rcs.ui.IntroPage
import dev.prism.rcs.ui.MetroProvider
import dev.prism.rcs.ui.PairingPage
import dev.prism.rcs.ui.SettingsPage
import dev.prism.rcs.ui.SwingIn
import dev.prism.rcs.ui.ThreadsPage
import kotlinx.coroutines.delay

/**
 * The one activity. Prism Launcher starts it with action [ACTION_OPEN] and
 * Intent.FLAG_ACTIVITY_NO_ANIMATION; it also turns its own open and close transitions off, so it
 * appears in place like a page of the launcher.
 */
class MainActivity : ComponentActivity() {
    private var openConversation by mutableStateOf<String?>(null)
    private var style by mutableStateOf(Style())

    override fun onCreate(savedInstanceState: Bundle?) {
        noTransitions()
        super.onCreate(savedInstanceState)
        style = Style.load(this)
        readIntent(intent)
        setContent {
            val s = style
            // the theme's own ground from the first frame, and status bar icons to match
            LaunchedEffect(s.dark) {
                val clear = android.graphics.Color.TRANSPARENT
                val bars = if (s.dark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear)
                enableEdgeToEdge(bars, bars)
                window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(if (s.dark) android.graphics.Color.BLACK else android.graphics.Color.WHITE))
            }
            MetroProvider(s) {
                Box(Modifier.fillMaxSize().background(Metro.t.bg)) {
                    App(
                        repo = (application as RcsApp).repo,
                        openConversation = openConversation,
                        onOpened = { openConversation = null },
                        onExit = { finish() },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        noTransitions()
        readIntent(intent)
    }

    override fun finish() {
        super.finish()
        noTransitions(closing = true)
    }

    private fun readIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_CONVERSATION_ID)?.let { openConversation = it }
        // Prism Launcher's look, handed over each time it opens this app
        val next = style.with(intent)
        if (next != style) {
            style = next
            Style.save(this, next)
        }
    }

    @Suppress("DEPRECATION")
    private fun noTransitions(closing: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(if (closing) Activity.OVERRIDE_TRANSITION_CLOSE else Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        const val ACTION_OPEN = "dev.prism.rcs.OPEN"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
    }
}

private sealed interface Page {
    data object Threads : Page
    data class Chat(val id: String) : Page
    data class Forward(val text: String) : Page
    data object Settings : Page
    data object SignIn : Page
}

@Composable
private fun App(
    repo: Repo,
    openConversation: String?,
    onOpened: () -> Unit,
    onExit: () -> Unit,
) {
    val link by repo.link.collectAsState()
    val threads by repo.threads.collectAsState()
    val note by repo.note.collectAsState()
    val connected by repo.connected.collectAsState()
    val typingMap by repo.typing.collectAsState()
    // typing indicators run out on their own
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(1_000); value = System.currentTimeMillis() } }
    val typing = typingMap.filterValues { it > now }.keys
    val stack = remember { mutableStateListOf<Page>(Page.Threads) }
    val page = stack.last()
    fun open(p: Page) { stack.add(p) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onExit() }

    // Prism asked for a thread: open it once linked
    LaunchedEffect(openConversation, link) {
        if (openConversation != null && link == Link.Ready) {
            stack.clear(); stack.add(Page.Threads); stack.add(Page.Chat(openConversation))
            onOpened()
        }
    }
    BackHandler { back() }

    // Not linked yet: the intro, Google's sign-in, then the emoji to tap.
    if (link != Link.Ready && page != Page.Settings) {
        when (val l = link) {
            Link.Loading -> Box(Modifier.fillMaxSize())
            is Link.Pairing -> SwingIn("pair") { PairingPage(l.emoji) { repo.cancelPairing() } }
            else -> if (page == Page.SignIn) {
                SwingIn("signin") {
                    GoogleSignInPage(
                        onCookies = { cookies -> stack.removeAt(stack.lastIndex); repo.pair(cookies) },
                        onCancel = { back() },
                    )
                }
            } else {
                SwingIn("intro") { IntroPage(note) { open(Page.SignIn) } }
            }
        }
        return
    }

    when (page) {
        Page.Threads, Page.SignIn -> SwingIn("threads") {
            ThreadsPage(threads, typing, note, connected, onOpen = { open(Page.Chat(it.id)) }) {
                AppBar(
                    buttons = listOf(
                        BarButton(Icon.Refresh, "refresh") { repo.refreshThreads() },
                        BarButton(Icon.Settings, "settings") { open(Page.Settings) },
                    ),
                    menu = listOf("unlink" to { open(Page.Settings) }),
                )
            }
        }
        is Page.Chat -> {
            val conv = threads.firstOrNull { it.id == page.id }
            if (conv == null) Box(Modifier.fillMaxSize()) else SwingIn(page) {
                ConversationPage(repo, conv, conv.id in typing, note, onForward = { open(Page.Forward(it)) }, onBack = { back() })
            }
        }
        is Page.Forward -> SwingIn(page) {
            ThreadsPage(threads, emptySet(), null, connected, caps = "forward to", heading = "threads", onOpen = { target ->
                repo.sendText(target.id, page.text)
                stack.removeAt(stack.lastIndex)
                open(Page.Chat(target.id))
            }) {
                AppBar(buttons = listOf(BarButton(Icon.Close, "cancel") { back() }))
            }
        }
        Page.Settings -> SwingIn("settings") {
            SettingsPage(link == Link.Ready, onUnlink = { repo.unlink(); stack.clear(); stack.add(Page.Threads) }, onBack = { back() })
        }
    }
}
