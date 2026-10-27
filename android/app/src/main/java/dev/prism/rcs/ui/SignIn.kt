// prism-rcs - an RCS-only Google Messages client for Android.
// Copyright (C) 2026 prism-rcs contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package dev.prism.rcs.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView

/** Before linking: what this does, said plainly, and the button to sign in. */
@Composable
fun IntroPage(note: String?, onSignIn: () -> Unit) {
    val t = Metro.t
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = t.dp(24f)).verticalScroll(rememberScrollState()),
        ) {
            PageTitle("RCS chats", "link")
            Gap(10f)
            MText(
                "Shows your RCS chats from Google Messages in Prism's Messaging. Sign in with the Google account on your phone, " +
                    "then tap the matching emoji in Google Messages to link.",
                22f, lineHeight = 30f,
            )
            Gap(18f)
            MText(
                "Good to know: this works like Google Messages for web, so your phone relays your messages through " +
                    "Google's servers while linked. It's an unofficial client that Google could break at any time, and " +
                    "using it goes against Google's terms. Plain SMS and MMS threads aren't shown here.",
                18f, color = t.subtle, lineHeight = 25f,
            )
            Gap(24f)
            MButton("sign in to google", onClick = onSignIn)
            if (note != null) {
                Gap(18f)
                MText(note, 20f, color = t.hot, lineHeight = 27f)
            }
        }
    }
}

private val RequiredCookies = listOf("SID", "HSID", "SSID", "APISID", "SAPISID", "OSID")
private val WantedCookies = RequiredCookies + "__Secure-1PSIDTS"

private fun messagesCookies(): Map<String, String>? {
    val raw = CookieManager.getInstance().getCookie("https://messages.google.com") ?: return null
    val all = raw.split(";").mapNotNull { part ->
        val i = part.indexOf('=')
        if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
    }.toMap()
    if (RequiredCookies.any { all[it].isNullOrEmpty() }) return null
    return all.filterKeys { it in WantedCookies }
}

/**
 * Google's own sign-in page, in a WebView inside the app. As soon as the session cookies for
 * messages.google.com exist, they're taken and the page is closed, before Google's web client can
 * start pairing on its own; then the WebView's cookies are cleared again.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GoogleSignInPage(onCookies: (Map<String, String>) -> Unit, onCancel: () -> Unit) {
    val t = Metro.t
    var done by remember { mutableStateOf(false) }
    val cookies = remember { CookieManager.getInstance() }
    DisposableEffect(Unit) {
        // start signed out, so the cookies taken are this sign-in's
        cookies.removeAllCookies(null)
        onDispose {
            cookies.removeAllCookies(null)
            cookies.flush()
        }
    }
    fun check(url: String?): Boolean {
        if (done) return true
        val host = url?.let { Uri.parse(it).host } ?: return false
        if (host != "messages.google.com") return false
        val got = messagesCookies() ?: return false
        done = true
        onCookies(got)
        return true
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.padding(horizontal = t.dp(24f))) {
            Gap(18f)
            MText("SIGN IN", 20f, weight = FontWeight.SemiBold)
            Gap(10f)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!done) AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Google refuses sign-in in pages it can tell are a WebView
                        settings.userAgentString = settings.userAgentString
                            .replace("; wv", "")
                            .replace(Regex("Version/\\d+\\.\\d+ "), "")
                        cookies.setAcceptCookie(true)
                        cookies.setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                request.isForMainFrame && check(request.url.toString())

                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                if (check(url)) view.stopLoading()
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                if (check(url)) view.stopLoading()
                            }
                        }
                        loadUrl(SIGN_IN_URL)
                    }
                },
                onRelease = { it.stopLoading(); it.destroy() },
                modifier = Modifier.fillMaxSize(),
            ) else MText("signing in…", 30f, Modifier.align(Alignment.Center), weight = FontWeight.Light)
        }
        AppBar(buttons = listOf(BarButton(Icon.Close, "cancel", onClick = onCancel)))
    }
}

/** Google's sign-in, coming back to Google Messages for web once signed in. */
private const val SIGN_IN_URL =
    "https://accounts.google.com/AccountChooser?continue=https%3A%2F%2Fmessages.google.com%2Fweb%2Fconversations"

/** Waiting on the phone: the emoji to tap in Google Messages. */
@Composable
fun PairingPage(emoji: String?, onCancel: () -> Unit) {
    val t = Metro.t
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.weight(1f).padding(horizontal = t.dp(24f))) {
            PageTitle("RCS chats", "pair")
            Gap(10f)
            if (emoji == null) {
                MText("getting your phone…", 26f, color = t.subtle, weight = FontWeight.Light)
            } else {
                MText("On your phone, Google Messages shows some emoji. Tap this one:", 22f, lineHeight = 30f)
                Gap(30f)
                MText(emoji, 150f, Modifier.align(Alignment.CenterHorizontally))
                Gap(30f)
                MText("Keep this page open until it's done.", 18f, color = t.subtle)
            }
        }
        AppBar(buttons = listOf(BarButton(Icon.Close, "cancel", onClick = onCancel)))
    }
}
