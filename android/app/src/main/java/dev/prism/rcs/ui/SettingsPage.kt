// prism-rcs - an RCS-only Google Messages client for Android.
// Copyright (C) 2026 prism-rcs contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package dev.prism.rcs.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import dev.prism.rcs.BuildConfig

/** Settings: where the look comes from, unlinking, and where the source code is (AGPL). */
@Composable
fun SettingsPage(linked: Boolean, onUnlink: () -> Unit, onBack: () -> Unit) {
    val t = Metro.t
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).statusBarsPadding().padding(horizontal = t.dp(24f)).verticalScroll(rememberScrollState())) {
            PageTitle("RCS chats", "settings")
            Gap(14f)
            MText(t.heading("style"), 30f, weight = FontWeight.Light)
            Gap(6f)
            MText(
                "The theme, accent colour, font, text size and Messaging design follow Prism Launcher's. " +
                    "Change them there; they're picked up the next time Prism opens this app.",
                18f, color = t.subtle, lineHeight = 25f,
            )
            if (linked) {
                Gap(24f)
                MText(t.heading("linked account"), 30f, weight = FontWeight.Light)
                Gap(6f)
                MText(
                    "Unlinking removes this device from Google Messages on your phone and deletes the saved session here.",
                    18f, color = t.subtle, lineHeight = 25f,
                )
                Gap(12f)
                if (!confirm) MButton("unlink") { confirm = true }
                else Row(horizontalArrangement = Arrangement.spacedBy(t.dp(12f))) {
                    MButton("yes, unlink") { confirm = false; onUnlink() }
                    MButton("keep it") { confirm = false }
                }
            }
            Gap(24f)
            MText(t.heading("about"), 30f, weight = FontWeight.Light)
            Gap(6f)
            MText(
                "RCS chats ${BuildConfig.VERSION_NAME} is free software under the GNU Affero General Public License v3, " +
                    "built on libgm from mautrix-gmessages. You can get its complete source code at:",
                18f, color = t.subtle, lineHeight = 25f,
            )
            Gap(6f)
            MText(BuildConfig.SOURCE_URL, 18f, color = t.hot)
            Gap(40f)
        }
        AppBar(buttons = listOf(BarButton(Icon.Back, "back", onClick = onBack)))
    }
}
