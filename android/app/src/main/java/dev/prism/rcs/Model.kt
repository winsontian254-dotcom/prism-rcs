// prism-rcs - an RCS-only Google Messages client for Android.
// Copyright (C) 2026 prism-rcs contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package dev.prism.rcs

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/*
 * The JSON shapes rcsmobile (android/rcsmobile/convert.go) hands over, read into Kotlin.
 * Timestamps are Unix milliseconds.
 */

data class Participant(val id: String, val name: String, val number: String, val isMe: Boolean)

data class Conversation(
    val id: String,
    val name: String,
    val snippet: String,
    val snippetFrom: String,
    val fromMe: Boolean,
    val timestamp: Long,
    val unread: Boolean,
    val isGroup: Boolean,
    val isRcs: Boolean,
    val readOnly: Boolean,
    val archived: Boolean,
    val participants: List<Participant>,
) {
    val title: String get() = name.ifBlank { participants.filter { !it.isMe }.joinToString { it.name.ifBlank { it.number } } }
}

class Media(
    val mediaId: String,
    val key: ByteArray,
    val thumbId: String,
    val thumbKey: ByteArray,
    val mime: String,
    val name: String,
    val width: Long,
    val height: Long,
) {
    val isImage: Boolean get() = mime.startsWith("image/")
}

data class Reaction(val emoji: String, val count: Int, val fromMe: Boolean)

data class Message(
    val id: String,
    val conversationId: String,
    val tmpId: String,
    val timestamp: Long,
    val fromMe: Boolean,
    val senderName: String,
    val text: String,
    val media: List<Media>,
    val status: String,
    val failed: Boolean,
    val deleted: Boolean,
    val reactions: List<Reaction>,
    /** Shown while it's on its way, before the phone reports the real message. */
    val pending: Boolean = false,
) {
    val sending: Boolean get() = pending || status in SendingStatuses

    companion object {
        private val SendingStatuses = setOf(
            "outgoing_yet_to_send", "outgoing_sending", "outgoing_resending", "outgoing_awaiting_retry",
            "outgoing_send_after_processing", "outgoing_validating",
        )
    }
}

class MessagePage(val messages: List<Message>, val cursor: String?)

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

private fun JSONObject.bytes(name: String): ByteArray =
    optString(name).takeIf { it.isNotEmpty() && it != "null" }?.let { Base64.decode(it, Base64.DEFAULT) } ?: ByteArray(0)

object Json {
    fun conversation(o: JSONObject) = Conversation(
        id = o.getString("id"),
        name = o.optString("name"),
        snippet = o.optString("snippet"),
        snippetFrom = o.optString("snippetFrom"),
        fromMe = o.optBoolean("fromMe"),
        timestamp = o.optLong("timestamp"),
        unread = o.optBoolean("unread"),
        isGroup = o.optBoolean("isGroup"),
        isRcs = o.optBoolean("isRcs"),
        readOnly = o.optBoolean("readOnly"),
        archived = o.optBoolean("archived"),
        participants = o.optJSONArray("participants").objects().map {
            Participant(it.optString("id"), it.optString("name"), it.optString("number"), it.optBoolean("isMe"))
        },
    )

    fun conversations(s: String) = JSONArray(s).objects().map(::conversation)

    fun message(o: JSONObject) = Message(
        id = o.getString("id"),
        conversationId = o.optString("conversationId"),
        tmpId = o.optString("tmpId"),
        timestamp = o.optLong("timestamp"),
        fromMe = o.optBoolean("fromMe"),
        senderName = o.optString("senderName"),
        text = o.optString("text"),
        media = o.optJSONArray("media").objects().map {
            Media(
                it.optString("mediaId"), it.bytes("key"), it.optString("thumbId"), it.bytes("thumbKey"),
                it.optString("mime"), it.optString("name"), it.optLong("width"), it.optLong("height"),
            )
        },
        status = o.optString("status"),
        failed = o.optBoolean("failed"),
        deleted = o.optBoolean("deleted"),
        reactions = o.optJSONArray("reactions").objects().map {
            Reaction(it.optString("emoji"), it.optInt("count"), it.optBoolean("fromMe"))
        },
    )

    fun page(s: String): MessagePage {
        val o = JSONObject(s)
        return MessagePage(o.optJSONArray("messages").objects().map(::message), o.optJSONObject("cursor")?.toString())
    }
}
