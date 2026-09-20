package com.example

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URISyntaxException

private const val TAG = "SocketManager"

data class ChatMessage(
    val id: String,
    val senderId: String,
    val senderName: String,
    val avatar: String = "",
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isHost: Boolean = false,
    val isSystem: Boolean = false
)

data class RoomUser(
    val socketId: String,
    val userName: String,
    val avatar: String,
    val isHost: Boolean,
    val isMuted: Boolean = false
)

data class RemoteMediaControl(
    val action: String, // "play", "pause", "seek"
    val timeSeconds: Long,
    val senderId: String,
    val timestamp: Long
)

data class MediaLoadEvent(
    val mediaUrl: String,
    val timeSeconds: Long = 0L,
    val title: String = "WatchRoom Stream",
    val senderId: String = ""
)

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

/**
 * Production-ready Socket.io client manager for WatchRoom
 */
class SocketManager {

    private var socket: Socket? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _currentRoomId = MutableStateFlow("")
    val currentRoomId: StateFlow<String> = _currentRoomId.asStateFlow()

    private val _isHost = MutableStateFlow(false)
    val isHost: StateFlow<Boolean> = _isHost.asStateFlow()

    private val _currentUser = MutableStateFlow<RoomUser?>(null)
    val currentUser: StateFlow<RoomUser?> = _currentUser.asStateFlow()

    private val _usersList = MutableStateFlow<List<RoomUser>>(emptyList())
    val usersList: StateFlow<List<RoomUser>> = _usersList.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _currentMedia = MutableStateFlow<MediaLoadEvent?>(null)
    val currentMedia: StateFlow<MediaLoadEvent?> = _currentMedia.asStateFlow()

    // Shared flow for remote media commands (Play/Pause/Seek)
    private val _remoteControlEvents = MutableSharedFlow<RemoteMediaControl>(extraBufferCapacity = 10)
    val remoteControlEvents: SharedFlow<RemoteMediaControl> = _remoteControlEvents.asSharedFlow()

    // Voice Chat status
    private val _isVoiceActive = MutableStateFlow(false)
    val isVoiceActive: StateFlow<Boolean> = _isVoiceActive.asStateFlow()

    private val _isAudioMuted = MutableStateFlow(false)
    val isAudioMuted: StateFlow<Boolean> = _isAudioMuted.asStateFlow()

    // WebRTC signaling event flow
    private val _signalingEvents = MutableSharedFlow<JSONObject>(extraBufferCapacity = 20)
    val signalingEvents: SharedFlow<JSONObject> = _signalingEvents.asSharedFlow()

    // Anti-Echo suppression window (1.5 seconds)
    @Volatile
    private var echoSuppressionUntilMs: Long = 0L

    /**
     * Connect and join room
     */
    fun joinRoom(
        serverUrl: String,
        roomId: String,
        userName: String,
        isHostRole: Boolean,
        avatar: String = ""
    ) {
        disconnect()

        _currentRoomId.value = roomId
        _isHost.value = isHostRole
        _connectionStatus.value = ConnectionStatus.CONNECTING

        try {
            val opts = IO.Options().apply {
                forceNew = true
                reconnection = true
                reconnectionAttempts = 5
                reconnectionDelay = 1500
                timeout = 10000
            }

            val validUrl = if (serverUrl.isBlank()) AppConfig.DEFAULT_CLOUD_SERVER_URL else serverUrl
            val socketInstance = IO.socket(validUrl, opts)
            this.socket = socketInstance

            setupListeners(socketInstance, roomId, userName, isHostRole, avatar)
            socketInstance.connect()

        } catch (e: URISyntaxException) {
            Log.e(TAG, "Invalid server URL: $serverUrl", e)
            _connectionStatus.value = ConnectionStatus.ERROR
        }
    }

    private fun setupListeners(
        sock: Socket,
        roomId: String,
        userName: String,
        isHostRole: Boolean,
        avatar: String
    ) {
        sock.on(Socket.EVENT_CONNECT) {
            Log.i(TAG, "Connected to Socket server: ${sock.id()}")
            scope.launch {
                _connectionStatus.value = ConnectionStatus.CONNECTED

                // Emit join_room
                val joinData = JSONObject().apply {
                    put("roomId", roomId)
                    put("userName", userName)
                    put("isHost", isHostRole)
                    put("avatar", avatar)
                }
                sock.emit("join_room", joinData)
            }
        }

        sock.on(Socket.EVENT_DISCONNECT) {
            Log.w(TAG, "Disconnected from Socket server")
            scope.launch {
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
            }
        }

        sock.on(Socket.EVENT_CONNECT_ERROR) { args ->
            val error = args.getOrNull(0)
            Log.e(TAG, "Connection error: $error")
            scope.launch {
                _connectionStatus.value = ConnectionStatus.ERROR
            }
        }

        // Room Joined initial state snapshot
        sock.on("room:joined") { args ->
            val data = args.getOrNull(0) as? JSONObject ?: return@on
            scope.launch {
                val hostAssigned = data.optBoolean("isHost", isHostRole)
                _isHost.value = hostAssigned

                val userObj = data.optJSONObject("user")
                if (userObj != null) {
                    _currentUser.value = RoomUser(
                        socketId = userObj.optString("socketId"),
                        userName = userObj.optString("userName", userName),
                        avatar = userObj.optString("avatar"),
                        isHost = userObj.optBoolean("isHost", hostAssigned),
                        isMuted = userObj.optBoolean("isMuted", false)
                    )
                }

                // Parse media state
                val mediaObj = data.optJSONObject("media")
                if (mediaObj != null) {
                    val mediaUrl = mediaObj.optString("mediaUrl")
                    if (mediaUrl.isNotBlank()) {
                        val time = mediaObj.optDouble("currentTime", 0.0).toLong()
                        val title = mediaObj.optString("title", "WatchRoom Stream")
                        _currentMedia.value = MediaLoadEvent(
                            mediaUrl = mediaUrl,
                            timeSeconds = time,
                            title = title
                        )
                    }
                }

                // Parse messages
                val messagesArray = data.optJSONArray("messages")
                if (messagesArray != null) {
                    val list = mutableListOf<ChatMessage>()
                    for (i in 0 until messagesArray.length()) {
                        val m = messagesArray.optJSONObject(i) ?: continue
                        list.add(parseChatMessage(m))
                    }
                    _chatMessages.value = list
                }

                // Parse users
                val usersArray = data.optJSONArray("users")
                if (usersArray != null) {
                    _usersList.value = parseUsersList(usersArray)
                }
            }
        }

        // Users updated
        sock.on("room:users_updated") { args ->
            val data = args.getOrNull(0) as? JSONObject ?: return@on
            val usersArray = data.optJSONArray("users") ?: return@on
            scope.launch {
                _usersList.value = parseUsersList(usersArray)
            }
        }

        // Host reassignment
        sock.on("room:host_assigned") { args ->
            val data = args.getOrNull(0) as? JSONObject ?: return@on
            scope.launch {
                _isHost.value = data.optBoolean("isHost", true)
            }
        }

        // Media Load event from Host
        sock.on("media:load") { args ->
            val data = args.getOrNull(0) as? JSONObject ?: return@on
            val mediaUrl = data.optString("mediaUrl")
            if (mediaUrl.isNotBlank()) {
                val time = data.optLong("time", 0L)
                val title = data.optString("title", "WatchRoom Stream")
                val senderId = data.optString("senderId", "")
                Log.i(TAG, "Received media:load -> $mediaUrl at $time s")
                scope.launch {
                    _currentMedia.value = MediaLoadEvent(
                        mediaUrl = mediaUrl,
                        timeSeconds = time,
                        title = title,
                        senderId = senderId
                    )
                }
            }
        }

        // Symmetric Media Control event (Play / Pause / Seek)
        sock.on("media:control") { args ->
            val data = args.getOrNull(0) as? JSONObject ?: return@on
            val action = data.optString("action")
            val time = data.optLong("time", 0L)
            val senderId = data.optString("senderId", "")
            val timestamp = data.optLong("timestamp", System.currentTimeMillis())

            Log.i(TAG, "Received remote media:control -> $action at $time s from $senderId")

            // Anti-Echo window: suppress local outgoing events for 1.5 seconds
            echoSuppressionUntilMs = System.currentTimeMillis() + 1500L

            scope.launch {
                _remoteControlEvents.emit(
                    RemoteMediaControl(
                        action = action,
                        timeSeconds = time,
                        senderId = senderId,
                        timestamp = timestamp
                    )
                )
            }
        }

        // Chat Message
        sock.on("chat:message") { args ->
            val data = args.getOrNull(0) as? JSONObject ?: return@on
            val msg = parseChatMessage(data)
            scope.launch {
                _chatMessages.value = _chatMessages.value + msg
            }
        }

        // Chat Cleared (Host Action)
        sock.on("chat:cleared") { args ->
            val data = args.getOrNull(0) as? JSONObject
            val clearedBy = data?.optString("clearedBy", "Host") ?: "Host"
            Log.i(TAG, "Chat cleared by $clearedBy")
            scope.launch {
                // Clear existing messages and insert system alert
                _chatMessages.value = listOf(
                    ChatMessage(
                        id = "sys-${System.currentTimeMillis()}",
                        senderId = "system",
                        senderName = "System",
                        text = "Chat history was cleared by $clearedBy",
                        timestamp = System.currentTimeMillis(),
                        isSystem = true
                    )
                )
            }
        }

        // WebRTC Audio Signaling Relays
        sock.on("signal:offer") { args ->
            val data = args.getOrNull(0) as? JSONObject ?: return@on
            scope.launch { _signalingEvents.emit(data) }
        }

        sock.on("signal:answer") { args ->
            val data = args.getOrNull(0) as? JSONObject ?: return@on
            scope.launch { _signalingEvents.emit(data) }
        }

        sock.on("signal:candidate") { args ->
            val data = args.getOrNull(0) as? JSONObject ?: return@on
            scope.launch { _signalingEvents.emit(data) }
        }

        // Voice status changed
        sock.on("voice:status_changed") { args ->
            val data = args.getOrNull(0) as? JSONObject ?: return@on
            val socketId = data.optString("socketId")
            val isMuted = data.optBoolean("isMuted", false)
            scope.launch {
                _usersList.value = _usersList.value.map { user ->
                    if (user.socketId == socketId) user.copy(isMuted = isMuted) else user
                }
            }
        }
    }

    /**
     * Broadcast Media Load (Host intercepts stream URL)
     */
    fun loadMedia(mediaUrl: String, timeSeconds: Long = 0L, title: String = "WatchRoom Stream") {
        val sock = socket ?: return
        _currentMedia.value = MediaLoadEvent(mediaUrl, timeSeconds, title)

        val payload = JSONObject().apply {
            put("mediaUrl", mediaUrl)
            put("time", timeSeconds)
            put("title", title)
        }
        sock.emit("media:load", payload)
        Log.i(TAG, "Emitted media:load -> $mediaUrl")
    }

    /**
     * Broadcast Symmetric Media Control action (Play / Pause / Seek ±15s)
     * Suppressed if inside Anti-Echo window!
     */
    fun sendMediaControl(action: String, timeSeconds: Long) {
        val now = System.currentTimeMillis()
        if (now < echoSuppressionUntilMs) {
            Log.d(TAG, "Suppressed local media control '$action' due to active anti-echo window")
            return
        }

        val sock = socket ?: return
        val payload = JSONObject().apply {
            put("action", action)
            put("time", timeSeconds)
        }
        sock.emit("media:control", payload)
        Log.i(TAG, "Emitted media:control -> $action at $timeSeconds s")
    }

    /**
     * Send chat message with optimistic local update
     */
    fun sendChatMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val sock = socket ?: return
        val payload = JSONObject().apply {
            put("text", trimmed)
        }
        sock.emit("chat:message", payload)
    }

    /**
     * Clear room chat history (Host ONLY)
     */
    fun clearChat() {
        if (!_isHost.value) {
            Log.w(TAG, "Only Host can clear chat history")
            return
        }
        val sock = socket ?: return
        sock.emit("chat:clear")
    }

    /**
     * Toggle WebRTC Audio voice chat
     */
    fun toggleVoiceChat() {
        val newState = !_isVoiceActive.value
        _isVoiceActive.value = newState
        if (!newState) {
            _isAudioMuted.value = false
        }
    }

    /**
     * Toggle Mute / Unmute
     */
    fun setAudioMuted(muted: Boolean) {
        _isAudioMuted.value = muted
        val sock = socket ?: return
        val payload = JSONObject().apply {
            put("isMuted", muted)
        }
        sock.emit("voice:toggle", payload)
    }

    /**
     * Send WebRTC signal offer/answer/candidate
     */
    fun sendSignal(event: String, payload: JSONObject) {
        socket?.emit(event, payload)
    }

    fun disconnect() {
        socket?.let {
            it.emit("leave_room")
            it.disconnect()
            it.off()
        }
        socket = null
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _usersList.value = emptyList()
        _isVoiceActive.value = false
    }

    private fun parseChatMessage(obj: JSONObject): ChatMessage {
        return ChatMessage(
            id = obj.optString("id", "msg-${System.currentTimeMillis()}"),
            senderId = obj.optString("senderId"),
            senderName = obj.optString("senderName", "Guest"),
            avatar = obj.optString("avatar"),
            text = obj.optString("text"),
            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
            isHost = obj.optBoolean("isHost", false),
            isSystem = obj.optBoolean("isSystem", false)
        )
    }

    private fun parseUsersList(array: JSONArray): List<RoomUser> {
        val list = mutableListOf<RoomUser>()
        for (i in 0 until array.length()) {
            val u = array.optJSONObject(i) ?: continue
            list.add(
                RoomUser(
                    socketId = u.optString("socketId"),
                    userName = u.optString("userName", "Guest"),
                    avatar = u.optString("avatar"),
                    isHost = u.optBoolean("isHost", false),
                    isMuted = u.optBoolean("isMuted", false)
                )
            )
        }
        return list
    }
}
