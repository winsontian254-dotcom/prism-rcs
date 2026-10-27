// prism-rcs - an RCS-only Google Messages client for Android.
// Copyright (C) 2026 prism-rcs contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package dev.prism.rcs

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * The RCS chats, for other apps to show: a read-mostly content provider in the manner of
 * Android's own SMS provider, documented in android/README.md ("Chats provider"). Any app the user
 * grants [PERMISSION] to can read the threads and messages and act on them (send, react, mark
 * read...). Changes are announced with ContentResolver.notifyChange on the URIs below.
 *
 *   content://dev.prism.rcs.chats/status                    one row: the link's state
 *   content://dev.prism.rcs.chats/threads                   RCS threads, newest first
 *   content://dev.prism.rcs.chats/threads/<id>/messages     a thread's messages, oldest first
 *   content://dev.prism.rcs.chats/media/<message id>/<n>    an attachment's bytes (?thumb=1: its thumbnail)
 */
class ChatsProvider : ContentProvider() {
    private val repo get() = (context!!.applicationContext as RcsApp).repo

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        (ctx.applicationContext as RcsApp).repo.onChange = { what ->
            val uri = when {
                what == "threads" -> THREADS
                what == "status" -> STATUS
                what.startsWith("messages/") -> messagesUri(what.removePrefix("messages/"))
                else -> null
            }
            uri?.let { ctx.contentResolver.notifyChange(it, null) }
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val cursor = when (matcher.match(uri)) {
            M_STATUS -> status()
            M_THREADS -> threads()
            M_MESSAGES -> messages(uri.pathSegments[1])
            else -> return null
        }
        cursor.setNotificationUri(context!!.contentResolver, uri)
        return cursor
    }

    private fun status() = MatrixCursor(arrayOf("state", "connected", "note")).apply {
        val state = when (repo.link.value) {
            Link.Loading -> "loading"
            Link.SignedOut -> "signed_out"
            is Link.Pairing -> "pairing"
            Link.Ready -> "linked"
        }
        addRow(arrayOf<Any?>(state, if (repo.connected.value) 1 else 0, repo.note.value))
    }

    private fun threads() = MatrixCursor(
        arrayOf("id", "name", "snippet", "snippet_from", "from_me", "date", "unread", "is_group", "read_only", "typing"),
    ).apply {
        val now = System.currentTimeMillis()
        val typing = repo.typing.value
        repo.threads.value.forEach { t ->
            addRow(
                arrayOf<Any?>(
                    t.id, t.title, t.snippet, t.snippetFrom, t.fromMe.int, t.timestamp, t.unread.int, t.isGroup.int,
                    t.readOnly.int, ((typing[t.id] ?: 0L) > now).int,
                ),
            )
        }
    }

    private fun messages(threadId: String) = MatrixCursor(
        arrayOf("id", "thread_id", "date", "from_me", "sender", "body", "status", "reactions", "media"),
    ).apply {
        // asking for a thread's messages starts loading them; a change notice follows
        repo.messages(threadId).value.filter { !it.deleted }.forEach { m ->
            val status = when {
                m.failed -> "failed"
                m.sending -> "sending"
                !m.fromMe -> "received"
                m.status == "outgoing_displayed" -> "read"
                m.status == "outgoing_delivered" -> "delivered"
                else -> "sent"
            }
            val reactions = JSONArray(m.reactions.map { JSONObject().put("emoji", it.emoji).put("count", it.count).put("from_me", it.fromMe) })
            val media = JSONArray(
                m.media.mapIndexed { i, a ->
                    JSONObject().put("index", i).put("mime", a.mime).put("name", a.name).put("width", a.width).put("height", a.height)
                        .put("has_thumb", a.thumbId.isNotEmpty())
                },
            )
            addRow(arrayOf<Any?>(m.id, m.conversationId, m.timestamp, m.fromMe.int, m.senderName, m.text, status, reactions.toString(), media.toString()))
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (matcher.match(uri) != M_MEDIA || mode != "r") return null
        val message = repo.message(uri.pathSegments[1]) ?: return null
        val media = message.media.getOrNull(uri.pathSegments[2].toIntOrNull() ?: -1) ?: return null
        val thumb = uri.getQueryParameter("thumb") == "1"
        val (read, write) = ParcelFileDescriptor.createReliablePipe()
        thread(name = "rcs-media") {
            val bytes = repo.mediaBytes(media, thumb)
            if (bytes == null) write.closeWithError("couldn't download the attachment")
            else ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write(bytes) }
        }
        return read
    }

    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        M_MEDIA -> repo.message(uri.pathSegments[1])?.media?.getOrNull(uri.pathSegments[2].toIntOrNull() ?: -1)?.mime
        M_THREADS -> "vnd.android.cursor.dir/vnd.dev.prism.rcs.thread"
        M_MESSAGES -> "vnd.android.cursor.dir/vnd.dev.prism.rcs.message"
        M_STATUS -> "vnd.android.cursor.item/vnd.dev.prism.rcs.status"
        else -> null
    }

    /**
     * Actions. [arg] is a thread ID ("send", "mark_read", "typing", "older") or a message ID
     * ("react", "delete"); "refresh" takes none. Returns a bundle with "ok".
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        // call() isn't covered by the provider's read/write permissions, so check here
        if (context!!.checkCallingOrSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("needs $PERMISSION")
        }
        val ok = when (method) {
            "refresh" -> { repo.refreshThreads(); true }
            "send" -> {
                val text = extras?.getString("text")?.trim().orEmpty()
                if (arg != null && text.isNotEmpty()) { repo.sendText(arg, text); true } else false
            }
            "react" -> arg?.let(repo::message)?.let { m ->
                extras?.getString("emoji")?.takeIf { it.isNotEmpty() }?.let { repo.react(m, it); true }
            } ?: false
            "delete" -> arg?.let(repo::message)?.let { repo.delete(it); true } ?: false
            "mark_read" -> {
                val id = extras?.getString("message_id")
                if (arg != null && id != null) { repo.markRead(arg, id); true } else false
            }
            "typing" -> arg?.let { repo.typingNow(it); true } ?: false
            "older" -> arg?.let { repo.olderMessages(it); true } ?: false
            else -> false
        }
        return Bundle().apply { putBoolean("ok", ok) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "dev.prism.rcs.chats"
        const val PERMISSION = "dev.prism.rcs.permission.CHATS"
        val STATUS: Uri = Uri.parse("content://$AUTHORITY/status")
        val THREADS: Uri = Uri.parse("content://$AUTHORITY/threads")
        fun messagesUri(threadId: String): Uri = THREADS.buildUpon().appendPath(threadId).appendPath("messages").build()

        private const val M_STATUS = 1
        private const val M_THREADS = 2
        private const val M_MESSAGES = 3
        private const val M_MEDIA = 4
        private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "status", M_STATUS)
            addURI(AUTHORITY, "threads", M_THREADS)
            addURI(AUTHORITY, "threads/*/messages", M_MESSAGES)
            addURI(AUTHORITY, "media/*/#", M_MEDIA)
        }

        private val Boolean.int get() = if (this) 1 else 0
    }
}
