package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private lateinit var socketManager: SocketManager
    private lateinit var voiceManager: WebRtcVoiceManager
    private lateinit var updateManager: UpdateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        socketManager = SocketManager()
        voiceManager = WebRtcVoiceManager(this, socketManager)
        updateManager = UpdateManager(this)

        setContent {
            MyApplicationTheme {
                WatchRoomApp(
                    socketManager = socketManager,
                    voiceManager = voiceManager,
                    updateManager = updateManager
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceManager.stopVoiceCall()
        socketManager.disconnect()
    }
}

@Composable
fun WatchRoomApp(
    socketManager: SocketManager,
    voiceManager: WebRtcVoiceManager,
    updateManager: UpdateManager
) {
    val connectionStatus by socketManager.connectionStatus.collectAsState()
    val currentRoomId by socketManager.currentRoomId.collectAsState()
    var inRoom by remember { mutableStateOf(false) }

    // If room is joined and connected, show RoomScreen
    if (inRoom && currentRoomId.isNotBlank()) {
        RoomScreen(
            socketManager = socketManager,
            voiceManager = voiceManager,
            onLeaveRoom = {
                voiceManager.stopVoiceCall()
                socketManager.disconnect()
                inRoom = false
            },
            modifier = Modifier.fillMaxSize()
        )
    } else {
        LobbyScreen(
            connectionStatus = connectionStatus,
            updateManager = updateManager,
            onJoinOrCreateRoom = { serverUrl, roomId, userName, isHost ->
                socketManager.joinRoom(
                    serverUrl = serverUrl,
                    roomId = roomId,
                    userName = userName,
                    isHostRole = isHost
                )
                inRoom = true
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
