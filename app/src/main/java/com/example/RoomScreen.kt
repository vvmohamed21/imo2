package com.example

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RoomScreen(
    socketManager: SocketManager,
    voiceManager: WebRtcVoiceManager,
    onLeaveRoom: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    // State collections
    val roomId by socketManager.currentRoomId.collectAsState()
    val isHost by socketManager.isHost.collectAsState()
    val currentMedia by socketManager.currentMedia.collectAsState()
    val usersList by socketManager.usersList.collectAsState()
    val chatMessages by socketManager.chatMessages.collectAsState()
    val currentUser by socketManager.currentUser.collectAsState()

    // Remote control event
    var latestRemoteControl by remember { mutableStateOf<RemoteMediaControl?>(null) }
    LaunchedEffect(Unit) {
        socketManager.remoteControlEvents.collect { cmd ->
            latestRemoteControl = cmd
        }
    }

    // Voice states
    val isVoiceCallActive by voiceManager.isCallActive.collectAsState()
    val isMicrophoneMuted by voiceManager.isMicrophoneMuted.collectAsState()
    val isSpeaking by voiceManager.isSpeaking.collectAsState()

    // Host View Mode: "browser" or "player"
    var hostViewMode by remember { mutableStateOf("browser") }
    var isFullscreen by remember { mutableStateOf(false) }
    var showClearChatDialog by remember { mutableStateOf(false) }

    // Chat input
    var messageInput by remember { mutableStateOf("") }
    val chatListState = rememberLazyListState()

    // Auto-scroll chat on new message
    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) {
            chatListState.animateScrollToItem(chatMessages.size - 1)
        }
    }

    // When Host sniffs media, auto switch host view to player and show snackbar
    LaunchedEffect(currentMedia?.mediaUrl) {
        val url = currentMedia?.mediaUrl
        if (!url.isNullOrBlank()) {
            hostViewMode = "player"
            if (isHost) {
                scope.launch {
                    snackbarHostState.showSnackbar("Sniffed media stream! Broadcasting to room...")
                }
            }
        }
    }

    // Permission launcher for WebRTC microphone
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceManager.startVoiceCall()
        } else {
            Toast.makeText(context, "Microphone permission required for voice party", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF090C15))
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // ==========================================
        // TOP HALF: MEDIA PANE (45% of height)
        // ==========================================
        val topWeight = if (isFullscreen) 1.0f else 0.45f

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(topWeight)
                .background(Color.Black)
                .animateContentSize()
        ) {
            if (isHost) {
                // HOST EXPERIENCE:
                // BrowserPane stays alive in the background so navigating between browser and player
                // never reloads or destroys the movie page or video element.
                Box(modifier = Modifier.fillMaxSize()) {
                    // Browser layer
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(if (hostViewMode == "browser" || currentMedia?.mediaUrl.isNullOrBlank()) 1f else 0f)
                            .zIndex(if (hostViewMode == "browser" || currentMedia?.mediaUrl.isNullOrBlank()) 2f else 0f)
                    ) {
                        BrowserPane(
                            initialUrl = "https://www.egybest.co.in/",
                            onMediaCaptured = { streamUrl, refererUrl ->
                                socketManager.loadMedia(
                                    mediaUrl = streamUrl,
                                    timeSeconds = 0L,
                                    title = "Sniffed Movie Stream",
                                    referer = refererUrl
                                )
                                hostViewMode = "player"
                            }
                        )

                        // Floating switch to Player button if a video was already sniffed
                        if (!currentMedia?.mediaUrl.isNullOrBlank()) {
                            Surface(
                                onClick = { hostViewMode = "player" },
                                color = Color(0xFF6C5CE7),
                                shape = RoundedCornerShape(20.dp),
                                shadowElevation = 6.dp,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(12.dp)
                                    .testTag("resume_player_button")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Return to Player",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "مشغل الفلم (Player)",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }

                    // Native Player layer
                    if (hostViewMode == "player" && !currentMedia?.mediaUrl.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(3f)
                        ) {
                            PlayerPane(
                                mediaUrl = currentMedia?.mediaUrl.orEmpty(),
                                referer = currentMedia?.referer.orEmpty(),
                                title = currentMedia?.title ?: "WatchRoom Stream",
                                isHost = true,
                                onUserControlAction = { action, timeSeconds ->
                                    socketManager.sendMediaControl(action, timeSeconds)
                                },
                                onSwitchToBrowser = {
                                    hostViewMode = "browser"
                                },
                                onToggleFullscreen = {
                                    isFullscreen = !isFullscreen
                                },
                                isFullscreen = isFullscreen,
                                remoteControlCommand = latestRemoteControl
                            )
                        }
                    }
                }
            } else {
                // GUEST EXPERIENCE:
                // Sleek waiting placeholder until host sniffs/selects a video
                if (currentMedia?.mediaUrl.isNullOrBlank()) {
                    GuestWaitingPane()
                } else {
                    PlayerPane(
                        mediaUrl = currentMedia?.mediaUrl.orEmpty(),
                        referer = currentMedia?.referer.orEmpty(),
                        title = currentMedia?.title ?: "WatchRoom Stream",
                        isHost = false,
                        onUserControlAction = { action, timeSeconds ->
                            // Symmetric controls: Guest actions emit media:control to the room!
                            socketManager.sendMediaControl(action, timeSeconds)
                        },
                        onToggleFullscreen = {
                            isFullscreen = !isFullscreen
                        },
                        isFullscreen = isFullscreen,
                        remoteControlCommand = latestRemoteControl
                    )
                }
            }
        }

        // If Fullscreen is active, hide bottom pane to maximize viewing area
        if (!isFullscreen) {
            // ==========================================
            // BOTTOM HALF: CHAT & ROOM CONTROLS (55%)
            // ==========================================
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.55f)
                    .background(Color(0xFF0F1422))
            ) {
                // ROOM HEADER
                RoomHeader(
                    roomId = roomId,
                    isHost = isHost,
                    userCount = usersList.size,
                    isVoiceActive = isVoiceCallActive,
                    onToggleVoice = {
                        if (isVoiceCallActive) {
                            voiceManager.stopVoiceCall()
                        } else {
                            val permission = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            )
                            if (permission == PackageManager.PERMISSION_GRANTED) {
                                voiceManager.startVoiceCall()
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                    onClearChat = { showClearChatDialog = true },
                    onLeaveRoom = onLeaveRoom,
                    onCopyRoomId = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("WatchRoom ID", roomId)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Room ID copied to clipboard!", Toast.LENGTH_SHORT).show()
                    }
                )

                // Voice Party Active Banner
                AnimatedVisibility(visible = isVoiceCallActive) {
                    VoicePartyBanner(
                        isSpeaking = isSpeaking,
                        isMuted = isMicrophoneMuted,
                        onToggleMute = { voiceManager.toggleMute() }
                    )
                }

                // CHAT BUBBLES LIST
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                ) {
                    if (chatMessages.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Say hi! Everyone in the watch party sees your messages in real-time.",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.4f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 32.dp)
                            )
                        }
                    } else {
                        LazyColumn(
                            state = chatListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(chatMessages, key = { it.id }) { msg ->
                                val isSelf = msg.senderId == currentUser?.socketId
                                if (msg.isSystem) {
                                    SystemMessageItem(text = msg.text)
                                } else if (isSelf) {
                                    SelfChatBubble(message = msg)
                                } else {
                                    GuestChatBubble(message = msg)
                                }
                            }
                        }
                    }
                }

                // CHAT INPUT ROW
                ChatInputRow(
                    text = messageInput,
                    onTextChange = { messageInput = it },
                    onSend = {
                        if (messageInput.isNotBlank()) {
                            socketManager.sendChatMessage(messageInput)
                            messageInput = ""
                            focusManager.clearFocus()
                        }
                    }
                )
            }
        }
    }

    // Host-Only Clear Chat Confirmation Dialog
    if (showClearChatDialog) {
        AlertDialog(
            onDismissRequest = { showClearChatDialog = false },
            title = {
                Text(
                    text = "Clear Room Chat?",
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            },
            text = {
                Text(
                    text = "This will erase all chat messages for everyone in the room. This action cannot be undone.",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        socketManager.clearChat()
                        showClearChatDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
                ) {
                    Text("Clear All", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearChatDialog = false }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.7f))
                }
            },
            containerColor = Color(0xFF192033),
            shape = RoundedCornerShape(16.dp)
        )
    }

    SnackbarHost(hostState = snackbarHostState)
}

/**
 * Room Header: Room ID with 1-click copy, connected user count, audio call toggle, and Host Clear Chat
 */
@Composable
fun RoomHeader(
    roomId: String,
    isHost: Boolean,
    userCount: Int,
    isVoiceActive: Boolean,
    onToggleVoice: () -> Unit,
    onClearChat: () -> Unit,
    onLeaveRoom: () -> Unit,
    onCopyRoomId: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0xFF131A2B),
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Back button & Room ID with copy
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onLeaveRoom,
                    modifier = Modifier.size(34.dp).testTag("leave_room_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Leave Room",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // 1-Click Copy Token Pill
                Surface(
                    onClick = onCopyRoomId,
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF1E2840),
                    modifier = Modifier.testTag("copy_room_id_button")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Room: $roomId",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00E5FF)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Room ID",
                            tint = Color(0xFF00E5FF).copy(alpha = 0.8f),
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }

            // Right: User Count badge + Voice Call Toggle + Host Clear Chat
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Connected users count badge
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1A2235),
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00E676))
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Users",
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "$userCount",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                // Audio Call Toggle (WebRTC Audio-Only)
                IconButton(
                    onClick = onToggleVoice,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (isVoiceActive) Color(0xFF00E676) else Color(0xFF1E2840))
                        .testTag("toggle_voice_call_button")
                ) {
                    Icon(
                        imageVector = if (isVoiceActive) Icons.Default.Call else Icons.Default.Call,
                        contentDescription = "Voice Call",
                        tint = if (isVoiceActive) Color(0xFF0A1F12) else Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Host ONLY: Clear Chat Trash Icon
                if (isHost) {
                    IconButton(
                        onClick = onClearChat,
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("host_clear_chat_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Clear Chat (Host Only)",
                            tint = Color(0xFFFF5252).copy(alpha = 0.85f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Voice Party Banner when WebRTC Audio is active
 */
@Composable
fun VoicePartyBanner(
    isSpeaking: Boolean,
    isMuted: Boolean,
    onToggleMute: () -> Unit
) {
    Surface(
        color = Color(0xFF11261D),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E676).copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isSpeaking) Color(0xFF00E676) else Color(0xFF81C784))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isSpeaking) "Speaking..." else if (isMuted) "Microphone Muted" else "Voice Party Live",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF00E676)
                )
            }

            Surface(
                onClick = onToggleMute,
                shape = RoundedCornerShape(12.dp),
                color = if (isMuted) Color(0xFFFF5252).copy(alpha = 0.2f) else Color(0xFF00E676).copy(alpha = 0.2f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "Mute",
                        tint = if (isMuted) Color(0xFFFF5252) else Color(0xFF00E676),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isMuted) "Unmute" else "Mute",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isMuted) Color(0xFFFF5252) else Color(0xFF00E676)
                    )
                }
            }
        }
    }
}

/**
 * Current user messages: Aligned to far right, App-primary theme color (#6C5CE7), white text
 */
@Composable
fun SelfChatBubble(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Surface(
                color = Color(0xFF6C5CE7), // App-primary theme color
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = 16.dp,
                    bottomEnd = 4.dp
                ),
                shadowElevation = 1.dp
            ) {
                Text(
                    text = message.text,
                    color = Color.White,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
            Text(
                text = formatMessageTime(message.timestamp),
                fontSize = 9.sp,
                color = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.padding(top = 2.dp, end = 4.dp)
            )
        }
    }
}

/**
 * Guest/Other messages: Aligned to far left, Light grey (#ECEEF0) bubble, dark #1C1C1E text,
 * with sender Name and Avatar displayed clearly above the bubble
 */
@Composable
fun GuestChatBubble(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        // Avatar circle
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(
                    if (message.isHost) Color(0xFFFF9800) else Color(0xFF3F51B5)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = message.senderName.take(1).uppercase(),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            // Sender name and optional HOST badge above bubble
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 2.dp)
            ) {
                Text(
                    text = message.senderName,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.85f)
                )
                if (message.isHost) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Surface(
                        color = Color(0xFFFF9800).copy(alpha = 0.2f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "HOST",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFFB74D),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            // Light grey bubble, dark text
            Surface(
                color = Color(0xFFECEEF0), // Requested light grey
                shape = RoundedCornerShape(
                    topStart = 4.dp,
                    topEnd = 16.dp,
                    bottomStart = 16.dp,
                    bottomEnd = 16.dp
                ),
                shadowElevation = 1.dp
            ) {
                Text(
                    text = message.text,
                    color = Color(0xFF1C1C1E), // Requested dark text
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            Text(
                text = formatMessageTime(message.timestamp),
                fontSize = 9.sp,
                color = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.padding(top = 2.dp, start = 4.dp)
            )
        }
    }
}

/**
 * Centered System message
 */
@Composable
fun SystemMessageItem(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = Color(0xFF1C2438),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = text,
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.65f),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
    }
}

/**
 * Chat Input Row with text field and optimistic send button
 */
@Composable
fun ChatInputRow(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0xFF131A2B),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = {
                    Text(
                        text = "Message room...",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.4f)
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("chat_input_field"),
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    color = Color.White
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF6C5CE7),
                    unfocusedBorderColor = Color(0xFF26324A),
                    focusedContainerColor = Color(0xFF0B0E17),
                    unfocusedContainerColor = Color(0xFF0B0E17),
                    cursorColor = Color(0xFF6C5CE7)
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() })
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Optimistic Send Button
            IconButton(
                onClick = onSend,
                enabled = text.isNotBlank(),
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (text.isNotBlank()) Color(0xFF6C5CE7) else Color(0xFF222B3F)
                    )
                    .testTag("chat_send_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (text.isNotBlank()) Color.White else Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun GuestWaitingPane(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F141F)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF6C5CE7).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = Color(0xFF6C5CE7),
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(36.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "بانتظار المضيف لاختيار الفلم...",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "يقوم المضيف حالياً باختيار الفلم من المتصفح. سيبدأ البث تلقائياً لديك فور بدء التشغيل!",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.65f),
                textAlign = TextAlign.Center
            )
        }
    }
}

fun formatMessageTime(timestamp: Long): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}
