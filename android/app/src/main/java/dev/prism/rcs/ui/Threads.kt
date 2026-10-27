// prism-rcs - an RCS-only Google Messages client for Android.
// Copyright (C) 2026 prism-rcs contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package dev.prism.rcs.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import dev.prism.rcs.Conversation
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val Clock = DateTimeFormatter.ofPattern("h:mma", Locale.US)
private val Weekday = DateTimeFormatter.ofPattern("EEE", Locale.US)
private val MonthDay = DateTimeFormatter.ofPattern("M/d", Locale.US)

private fun local(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())

/** "1:40p". */
fun clock(ms: Long): String = local(ms).format(Clock).lowercase().replace("am", "a").replace("pm", "p")

/** Today's as a time, this week's as a day, older ones as a date. */
fun shortWhen(ms: Long): String {
    val d = local(ms)
    val days = ChronoUnit.DAYS.between(d.toLocalDate(), LocalDate.now())
    return when {
        days < 1 -> clock(ms)
        days < 7 -> d.format(Weekday).lowercase()
        else -> d.format(MonthDay)
    }
}

/** The list of threads: who, the last message under it, and when at the right. */
@Composable
fun ThreadsPage(
    threads: List<Conversation>,
    typing: Set<String>,
    note: String?,
    connected: Boolean,
    caps: String = "RCS chats",
    heading: String = "threads",
    onOpen: (Conversation) -> Unit,
    bar: @Composable () -> Unit,
) {
    val t = Metro.t
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).statusBarsPadding().padding(horizontal = t.dp(24f))) {
            PageTitle(caps, heading)
            if (note != null) MText(note, 18f, Modifier.padding(vertical = t.dp(4f)), color = t.hot, maxLines = 3)
            Gap(10f)
            LazyColumn(Modifier.fillMaxSize()) {
                if (threads.isEmpty()) item {
                    MText(if (connected) "No RCS chats yet." else t.heading("loading…"), 24f, color = t.subtle, weight = FontWeight.Light)
                }
                items(threads, key = { it.id }) { th ->
                    val hot = th.unread
                    Row(Modifier.fillMaxWidth().press { onOpen(th) }.padding(vertical = t.dp(10f))) {
                        Column(Modifier.weight(1f)) {
                            MText(th.title, 34f, color = if (hot) t.hot else t.fg, weight = FontWeight.Light, maxLines = 1)
                            val line = when {
                                th.id in typing -> t.heading("typing…")
                                th.isGroup && th.snippetFrom.isNotEmpty() && !th.fromMe -> "${th.snippetFrom}: ${th.snippet}"
                                else -> th.snippet
                            }
                            MText(line.replace('\n', ' '), 20f, color = if (th.id in typing) t.hot else t.subtle, maxLines = 1)
                        }
                        Gap(10f)
                        MText(shortWhen(th.timestamp), 17f, Modifier.padding(top = t.dp(10f)), color = if (hot) t.hot else t.fg)
                    }
                }
                item { Gap(40f) }
            }
        }
        Box { bar() }
    }
}
