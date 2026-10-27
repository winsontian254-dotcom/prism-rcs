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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.prism.rcs.ui.Metro
import dev.prism.rcs.ui.Style
import dev.prism.rcs.ui.GoogleSignInPage
import dev.prism.rcs.ui.IntroPage
import dev.prism.rcs.ui.MetroProvider
import dev.prism.rcs.ui.PairingPage
import dev.prism.rcs.ui.SettingsPage
import dev.prism.rcs.ui.SwingIn

/**
 * The one activity, for linking and unlinking (the chats themselves are shown by Prism Launcher,
 * through [ChatsProvider]). Prism starts it with action [ACTION_OPEN] and
 * Intent.FLAG_ACTIVITY_NO_ANIMATION; it also turns its own open and close transitions off, so it
 * appears in place like a page of the launcher.
 */
class MainActivity : ComponentActivity() {
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
                    App(repo = (application as RcsApp).repo, onExit = { finish() })
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
    }
}

private sealed interface Page {
    data object Home : Page
    data object SignIn : Page
}

/**
 * Not linked: the intro, Google's sign-in, then the emoji to tap. Linked: the page saying where
 * the chats show (Prism's Messaging), with unlinking and the about page.
 */
@Composable
private fun App(repo: Repo, onExit: () -> Unit) {
    val link by repo.link.collectAsState()
    val note by repo.note.collectAsState()
    var page by remember { mutableStateOf<Page>(Page.Home) }
    BackHandler { if (page == Page.SignIn) page = Page.Home else onExit() }

    when (val l = link) {
        Link.Loading -> Box(Modifier.fillMaxSize())
        is Link.Pairing -> SwingIn("pair") { PairingPage(l.emoji) { repo.cancelPairing() } }
        Link.SignedOut -> if (page == Page.SignIn) {
            SwingIn("signin") {
                GoogleSignInPage(
                    onCookies = { cookies -> page = Page.Home; repo.pair(cookies) },
                    onCancel = { page = Page.Home },
                )
            }
        } else {
            SwingIn("intro") { IntroPage(note) { page = Page.SignIn } }
        }
        Link.Ready -> SwingIn("linked") { SettingsPage(linked = true, note = note, onUnlink = { repo.unlink() }, onBack = onExit) }
    }
}
