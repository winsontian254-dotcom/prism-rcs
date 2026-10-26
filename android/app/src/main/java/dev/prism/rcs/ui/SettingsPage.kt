// prism-rcs - an RCS-only Google Messages client for Android.
// Copyright (C) 2026 prism-rcs contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package dev.prism.rcs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import dev.prism.rcs.BuildConfig

/** Settings: the accent colour, unlinking, and where the source code is (AGPL). */
@Composable
fun SettingsPage(accent: Color, linked: Boolean, onAccent: (Color?) -> Unit, onUnlink: () -> Unit, onBack: () -> Unit) {
    val t = Metro.t
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).statusBarsPadding().padding(horizontal = t.dp(24f)).verticalScroll(rememberScrollState())) {
            PageTitle("rcs chats", "settings")
            Gap(14f)
            MText("accent colour", 30f, weight = FontWeight.Light)
            Gap(10f)
            Accents.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = t.dp(10f)), horizontalArrangement = Arrangement.spacedBy(t.dp(10f))) {
                    row.forEach { (label, c) ->
                        Box(
                            Modifier.weight(1f).aspectRatio(1f).background(c)
                                .then(if (accent.toArgb() == c.toArgb()) Modifier.border(t.dp(4f), t.fg) else Modifier)
                                .press { onAccent(c) },
                        ) { MText(label, 15f, Modifier.align(Alignment.BottomStart).padding(t.dp(6f)), color = Color.White) }
                    }
                }
            }
            MText("use Prism's accent", 20f, Modifier.press { onAccent(null) }.padding(vertical = t.dp(8f)), color = t.subtle)
            if (linked) {
                Gap(24f)
                MText("linked account", 30f, weight = FontWeight.Light)
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
            MText("about", 30f, weight = FontWeight.Light)
            Gap(6f)
            MText(
                "RCS chats ${BuildConfig.VERSION_NAME} is free software under the GNU Affero General Public License v3, " +
                    "built on libgm from mautrix-gmessages. You can get its complete source code at:",
                18f, color = t.subtle, lineHeight = 25f,
            )
            Gap(6f)
            MText(BuildConfig.SOURCE_URL, 18f, color = t.accent.lighter())
            Gap(40f)
        }
        AppBar(buttons = listOf(BarButton(Icon.Back, "back", onClick = onBack)))
    }
}
