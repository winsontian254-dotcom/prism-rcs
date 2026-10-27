// prism-rcs - an RCS-only Google Messages client for Android.
// Copyright (C) 2026 prism-rcs contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package dev.prism.rcs

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import dev.prism.rcs.go.rcsmobile.Client
import dev.prism.rcs.go.rcsmobile.Listener
import dev.prism.rcs.go.rcsmobile.Rcsmobile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class RcsApp : Application() {
    val repo by lazy { Repo(this) }
}

sealed interface Link {
    data object Loading : Link
    data object SignedOut : Link
    /** Signed in to Google; [emoji] is what to tap on the phone (null while it's being fetched). */
    data class Pairing(val emoji: String?) : Link
    data object Ready : Link
}

/**
 * Everything the screens show, kept here so it outlives them: the link to Google Messages, the RCS
 * threads, the messages of threads that have been opened, and who's typing.
 */
class Repo(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = SessionStore(app)
    private lateinit var client: Client

    val link = MutableStateFlow<Link>(Link.Loading)
    val threads = MutableStateFlow<List<Conversation>>(emptyList())
    /** Something to tell the user ("your phone isn't responding"), or null. */
    val note = MutableStateFlow<String?>(null)
    /** Conversation ID to the time (ms) its typing indicator runs out. */
    val typing = MutableStateFlow<Map<String, Long>>(emptyMap())
    val connected = MutableStateFlow(false)

    private val messages = HashMap<String, MutableStateFlow<List<Message>>>()
    private val cursors = HashMap<String, String?>()
    private val images = LruCache<String, Bitmap>(24)

    private val listener = object : Listener {
        override fun onEvent(kind: String, json: String) {
            scope.launch { handle(kind, json) }
        }

        override fun onLog(level: String, line: String) {
            when (level) {
                "error", "fatal" -> Log.e(TAG, line)
                "warn" -> Log.w(TAG, line)
                else -> Log.i(TAG, line)
            }
        }
    }

    init {
        scope.launch { start() }
    }

    private suspend fun start() {
        val session = store.load()
        client = runCatching { Rcsmobile.newClient(session ?: ByteArray(0), listener) }
            .getOrElse {
                Log.e(TAG, "Saved session unreadable, starting over", it)
                store.delete()
                Rcsmobile.newClient(ByteArray(0), listener)
            }
        if (client.isLoggedIn) {
            link.value = Link.Ready
            connect()
        } else {
            link.value = Link.SignedOut
        }
    }

    private fun connect() = scope.launch {
        runCatching { client.connect() }
            .onSuccess { refreshThreads() }
            .onFailure { note.value = "Couldn't connect: ${it.message}" }
    }

    private fun saveSession() {
        runCatching { store.save(client.session()) }.onFailure { Log.e(TAG, "Couldn't save session", it) }
    }

    private fun handle(kind: String, json: String) {
        when (kind) {
            "session" -> saveSession()
            "connected" -> { connected.value = true; note.value = null }
            "phone_offline" -> note.value = "Your phone isn't responding. Is it online?"
            "phone_online" -> note.value = null
            "error" -> Log.w(TAG, "temporary error: $json")
            "logged_out" -> {
                val why = runCatching { JSONObject(json).optString("error") }.getOrNull()
                forget()
                note.value = why?.let { "Unlinked: $it" }
            }
            "conversation" -> {
                val conv = Json.conversation(JSONObject(json))
                threads.update { list ->
                    val rest = list.filter { it.id != conv.id }
                    // plain SMS/MMS threads belong to Prism's own Messaging app
                    if (!conv.isRcs || conv.archived) rest else (rest + conv).sortedByDescending { it.timestamp }
                }
            }
            "message" -> upsert(Json.message(JSONObject(json)))
            "typing" -> {
                val o = JSONObject(json)
                val id = o.optString("conversationId")
                typing.update { if (o.optBoolean("typing")) it + (id to System.currentTimeMillis() + 15_000) else it - id }
            }
        }
    }

    private fun upsert(m: Message) {
        val flow = synchronized(messages) { messages[m.conversationId] } ?: return
        flow.update { list ->
            val rest = list.filter { it.id != m.id && !(it.pending && m.tmpId.isNotEmpty() && it.tmpId == m.tmpId) }
            (rest + m).sortedBy { it.timestamp }
        }
        if (!m.fromMe) typing.update { it - m.conversationId }
    }

    // ---- pairing ----

    fun pair(cookies: Map<String, String>) {
        link.value = Link.Pairing(null)
        note.value = null
        scope.launch {
            try {
                val emoji = client.startPairing(JSONObject(cookies).toString())
                link.value = Link.Pairing(emoji)
                client.waitPairing()
                saveSession()
                link.value = Link.Ready
                refreshThreads()
            } catch (e: Exception) {
                link.value = Link.SignedOut
                note.value = e.message ?: "Pairing failed"
            }
        }
    }

    fun cancelPairing() {
        scope.launch { client.cancelPairing() }
        link.value = Link.SignedOut
    }

    /** Unlinks from the phone and deletes the stored session. */
    fun unlink() = scope.launch {
        runCatching { client.unlink() }.onFailure { Log.w(TAG, "Unpair request failed", it) }
        forget()
    }

    private fun forget() {
        runCatching { client.disconnect() }
        store.delete()
        threads.value = emptyList()
        synchronized(messages) { messages.clear() }
        cursors.clear()
        images.evictAll()
        connected.value = false
        client = Rcsmobile.newClient(ByteArray(0), listener)
        link.value = Link.SignedOut
    }

    // ---- threads and messages ----

    fun refreshThreads() = scope.launch {
        runCatching { Json.conversations(client.listConversations(100, false)) }
            .onSuccess { all ->
                // plain SMS/MMS threads belong to Prism's own Messaging app
                val list = all.filter { it.isRcs && !it.archived }
                Log.i(TAG, "threads: ${all.size} in the inbox, ${list.size} RCS")
                threads.value = list.sortedByDescending { it.timestamp }
            }
            .onFailure { note.value = "Couldn't load threads: ${it.message}" }
    }

    fun messages(convId: String): StateFlow<List<Message>> = synchronized(messages) {
        messages.getOrPut(convId) {
            MutableStateFlow<List<Message>>(emptyList()).also { loadMore(convId, it) }
        }
    }

    private fun loadMore(convId: String, flow: MutableStateFlow<List<Message>>) = scope.launch {
        runCatching { Json.page(client.fetchMessages(convId, 40, cursors[convId] ?: "")) }
            .onSuccess { page ->
                cursors[convId] = page.cursor
                flow.update { old -> (old + page.messages).distinctBy { it.id }.sortedBy { it.timestamp } }
            }
            .onFailure { note.value = "Couldn't load messages: ${it.message}" }
    }

    fun olderMessages(convId: String) {
        if (cursors[convId] == null) return
        synchronized(messages) { messages[convId] }?.let { loadMore(convId, it) }
    }

    fun sendText(convId: String, text: String) {
        val flow = messages(convId) as MutableStateFlow
        scope.launch {
            runCatching { client.sendText(convId, text, "") }
                .onSuccess { tmp ->
                    // shown at once, replaced when the phone reports the real message
                    val placeholder = Message(
                        "pending:$tmp", convId, tmp, System.currentTimeMillis(), true, "", text,
                        emptyList(), "", false, false, emptyList(), pending = true,
                    )
                    flow.update { list -> if (list.any { it.tmpId == tmp }) list else list + placeholder }
                }
                .onFailure { note.value = "Not sent: ${it.message}" }
        }
    }

    fun sendMedia(convId: String, data: ByteArray, name: String, mime: String) = scope.launch {
        note.value = "Sending…"
        runCatching { client.sendMedia(convId, data, name, mime, "") }
            .onSuccess { note.value = null }
            .onFailure { note.value = "Not sent: ${it.message}" }
    }

    fun react(m: Message, emoji: String) = scope.launch {
        val mine = m.reactions.firstOrNull { it.fromMe }
        runCatching {
            when {
                mine?.emoji == emoji -> client.react(m.conversationId, m.id, emoji, false, true)
                mine != null -> client.react(m.conversationId, m.id, emoji, true, false)
                else -> client.react(m.conversationId, m.id, emoji, false, false)
            }
        }.onFailure { note.value = "Couldn't react: ${it.message}" }
    }

    fun delete(m: Message) = scope.launch {
        runCatching { client.deleteMessage(m.id) }
            .onSuccess { synchronized(messages) { messages[m.conversationId] }?.update { list -> list.filter { it.id != m.id } } }
            .onFailure { note.value = "Couldn't delete: ${it.message}" }
    }

    private var lastTyping = 0L

    fun typingNow(convId: String) {
        val now = System.currentTimeMillis()
        if (now - lastTyping < 5_000) return
        lastTyping = now
        scope.launch { runCatching { client.setTyping(convId) } }
    }

    private val lastRead = HashMap<String, String>()

    fun markRead(convId: String, messageId: String) {
        if (lastRead[convId] == messageId || messageId.startsWith("pending:")) return
        lastRead[convId] = messageId
        scope.launch {
            runCatching { client.markRead(convId, messageId) }
            threads.update { list -> list.map { if (it.id == convId) it.copy(unread = false) else it } }
        }
    }

    /** An attachment's picture (the thumbnail when there is one), decoded and cached. */
    suspend fun image(media: Media): Bitmap? = withContext(Dispatchers.IO) {
        val id = media.thumbId.ifEmpty { media.mediaId }
        images.get(id)?.let { return@withContext it }
        val key = if (media.thumbId.isNotEmpty()) media.thumbKey else media.key
        runCatching { client.downloadMedia(id, key) }.getOrNull()
            ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            ?.also { images.put(id, it) }
    }

    private companion object {
        const val TAG = "PrismRcs"
    }
}
