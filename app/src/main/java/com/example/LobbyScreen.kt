package com.example

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.random.Random

@Composable
fun LobbyScreen(
    connectionStatus: ConnectionStatus,
    updateManager: UpdateManager? = null,
    onJoinOrCreateRoom: (serverUrl: String, roomId: String, userName: String, isHost: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Host, 1: Join
    var userName by remember { mutableStateOf("Viewer_${Random.nextInt(100, 999)}") }
    var joinRoomId by remember { mutableStateOf("") }
    var hostRoomId by remember { mutableStateOf("ROOM_${Random.nextInt(1000, 9999)}") }
    var serverUrl by remember { mutableStateOf(AppConfig.DEFAULT_CLOUD_SERVER_URL) }
    var showServerSettings by remember { mutableStateOf(false) }

    val updateStatus by (updateManager?.status?.collectAsState() ?: remember { mutableStateOf(UpdateCheckStatus.IDLE) })
    val updateInfo by (updateManager?.updateInfo?.collectAsState() ?: remember { mutableStateOf(null) })
    val updateError by (updateManager?.errorMessage?.collectAsState() ?: remember { mutableStateOf(null) })
    val downloadProgress by (updateManager?.downloadProgress?.collectAsState() ?: remember { mutableStateOf(0) })

    // Auto-check for updates on launch
    LaunchedEffect(serverUrl) {
        updateManager?.checkForUpdates(serverUrl)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0D111D),
                        Color(0xFF090B14),
                        Color(0xFF05060A)
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top branding row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (serverUrl.startsWith("https://")) Color(0xFF00E5FF) else Color(0xFF00E676)
                        )
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (serverUrl.startsWith("https://")) "CLOUD SYNC (RENDER)" else "REALTIME SYNC",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (serverUrl.startsWith("https://")) Color(0xFF00E5FF) else Color(0xFF00E676)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (updateStatus == UpdateCheckStatus.UPDATE_AVAILABLE) {
                    Surface(
                        color = Color(0xFFFF5252).copy(alpha = 0.2f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF5252)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SystemUpdate,
                                contentDescription = null,
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "v${updateInfo?.versionName}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFF5252)
                            )
                        }
                    }
                }

                IconButton(
                    onClick = { showServerSettings = !showServerSettings },
                    modifier = Modifier.size(36.dp).testTag("server_settings_toggle")
                ) {
                    Icon(
                        imageVector = Icons.Default.Dns,
                        contentDescription = "Server settings",
                        tint = if (showServerSettings) Color(0xFF6C5CE7) else Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Update Notification Card if update is available
        if (updateStatus == UpdateCheckStatus.UPDATE_AVAILABLE && updateInfo != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1B162E)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6C5CE7))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.SystemUpdate,
                                contentDescription = null,
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "New Version Available (v${updateInfo?.versionName})",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    if (!updateInfo?.changelog.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = updateInfo?.changelog ?: "",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.7f),
                            lineHeight = 15.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            onClick = { updateManager?.openInBrowser() },
                            color = Color.White.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = "تنزيل من المتصفح",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                            )
                        }

                        Button(
                            onClick = {
                                updateInfo?.apkUrl?.let { url ->
                                    updateManager?.startDownload(url)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "تحديث فوري (مرة واحدة)",
                                color = Color.Black,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        } else if (updateStatus == UpdateCheckStatus.DOWNLOADING) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1B162E)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "جاري تحميل أحدث إصدار ($downloadProgress%)...",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = Color(0xFF00E5FF),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { downloadProgress / 100f },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = Color(0xFF00E5FF),
                        trackColor = Color(0xFF26324A)
                    )
                }
            }
        } else if (updateStatus == UpdateCheckStatus.READY_TO_INSTALL) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF102820)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E676))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "تم تحميل أحدث إصدار بنجاح!",
                            fontSize = 12.sp,
                            color = Color(0xFF00E676),
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "تم حذف كل التحديثات السابقة. اضغط تثبيت",
                            fontSize = 10.sp,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = { updateManager?.installApk() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text("تثبيت (Install)", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        IconButton(
                            onClick = { updateManager?.openInBrowser() },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Download directly",
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        } else if (updateStatus == UpdateCheckStatus.ERROR && updateError != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2E1616)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF5252))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "تعذر التحديث التلقائي",
                            fontSize = 12.sp,
                            color = Color(0xFFFF5252),
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = updateError ?: "خطأ أثناء التحديث",
                            fontSize = 10.sp,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                    Button(
                        onClick = { updateManager?.openInBrowser() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("تحميل عبر المتصفح", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Hero Cinema Icon
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF6C5CE7).copy(alpha = 0.4f), Color(0xFF131828))
                    )
                )
                .border(2.dp, Color(0xFF6C5CE7).copy(alpha = 0.6f), RoundedCornerShape(26.dp)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_watchroom_logo),
                contentDescription = "WatchRoom Logo",
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(20.dp))
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "WatchRoom",
            fontSize = 30.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
            letterSpacing = 0.5.sp
        )

        Text(
            text = "Synchronized Stream Sniffing & Watch Parties",
            fontSize = 13.sp,
            color = Color.White.copy(alpha = 0.65f),
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Card Container
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131826)),
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF232D42))
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // Host vs Join Tabs
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color(0xFF0C0F1A),
                    contentColor = Color.White,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = Color(0xFF6C5CE7),
                            height = 3.dp
                        )
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .height(44.dp)
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Text(
                                "Host Room",
                                fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp
                            )
                        },
                        selectedContentColor = Color(0xFF6C5CE7),
                        unselectedContentColor = Color.White.copy(alpha = 0.6f)
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Text(
                                "Join Room",
                                fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp
                            )
                        },
                        selectedContentColor = Color(0xFF6C5CE7),
                        unselectedContentColor = Color.White.copy(alpha = 0.6f)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Username Input
                OutlinedTextField(
                    value = userName,
                    onValueChange = { userName = it },
                    label = { Text("Your Display Name") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = Color(0xFF6C5CE7),
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6C5CE7),
                        unfocusedBorderColor = Color(0xFF26324A),
                        focusedContainerColor = Color(0xFF0B0E17),
                        unfocusedContainerColor = Color(0xFF0B0E17),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedLabelColor = Color(0xFF6C5CE7),
                        unfocusedLabelColor = Color.White.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().testTag("username_input")
                )

                Spacer(modifier = Modifier.height(14.dp))

                if (selectedTab == 0) {
                    // HOST ROOM INPUT
                    OutlinedTextField(
                        value = hostRoomId,
                        onValueChange = { hostRoomId = it },
                        label = { Text("Room ID Code") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Tv,
                                contentDescription = null,
                                tint = Color(0xFF6C5CE7),
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF6C5CE7),
                            unfocusedBorderColor = Color(0xFF26324A),
                            focusedContainerColor = Color(0xFF0B0E17),
                            unfocusedContainerColor = Color(0xFF0B0E17),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedLabelColor = Color(0xFF6C5CE7),
                            unfocusedLabelColor = Color.White.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().testTag("host_room_id_input")
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "As Host, you browse movie websites. Streams will be sniffed and broadcast automatically to all guests.",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.5f),
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = {
                            val code = hostRoomId.trim().ifEmpty { "ROOM_${Random.nextInt(1000, 9999)}" }
                            val user = userName.trim().ifEmpty { "Host" }
                            onJoinOrCreateRoom(serverUrl, code, user, true)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("create_room_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C5CE7))
                    ) {
                        if (connectionStatus == ConnectionStatus.CONNECTING) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.GroupAdd,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Create & Host Watch Party",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    // JOIN ROOM INPUT
                    OutlinedTextField(
                        value = joinRoomId,
                        onValueChange = { joinRoomId = it },
                        label = { Text("Enter Room ID to Join") },
                        placeholder = { Text("e.g. ROOM_4821") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Login,
                                contentDescription = null,
                                tint = Color(0xFF6C5CE7),
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF6C5CE7),
                            unfocusedBorderColor = Color(0xFF26324A),
                            focusedContainerColor = Color(0xFF0B0E17),
                            unfocusedContainerColor = Color(0xFF0B0E17),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedLabelColor = Color(0xFF6C5CE7),
                            unfocusedLabelColor = Color.White.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().testTag("join_room_id_input")
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "As Guest, your player will sync automatically with the Host's selected stream and playback controls.",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.5f),
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = {
                            val code = joinRoomId.trim()
                            val user = userName.trim().ifEmpty { "Guest" }
                            if (code.isNotBlank()) {
                                onJoinOrCreateRoom(serverUrl, code, user, false)
                            }
                        },
                        enabled = joinRoomId.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("join_room_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF6C5CE7),
                            disabledContainerColor = Color(0xFF232B3F)
                        )
                    ) {
                        if (connectionStatus == ConnectionStatus.CONNECTING) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Login,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Join Watch Party",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Expandable Server URL settings
                AnimatedVisibility(visible = showServerSettings) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0A0D16))
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Socket Server Address",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF6C5CE7)
                            )
                            // Quick Cloud / Local toggle buttons
                            Row {
                                TextButton(
                                    onClick = { serverUrl = AppConfig.DEFAULT_CLOUD_SERVER_URL },
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("Cloud (Render)", fontSize = 10.sp, color = Color(0xFF00E5FF))
                                }
                                TextButton(
                                    onClick = { serverUrl = AppConfig.LOCAL_EMULATOR_SERVER_URL },
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("Local", fontSize = 10.sp, color = Color.White.copy(alpha = 0.6f))
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = { serverUrl = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 12.sp,
                                color = Color.White
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF6C5CE7),
                                unfocusedBorderColor = Color(0xFF242E45)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().testTag("server_url_input")
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Default: Render Cloud Server (24/7 online)",
                                fontSize = 10.sp,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                            TextButton(
                                onClick = {
                                    coroutineScope.launch {
                                        updateManager?.checkForUpdates(serverUrl)
                                    }
                                },
                                modifier = Modifier.height(26.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = Color(0xFF6C5CE7)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Check Update", fontSize = 10.sp, color = Color(0xFF6C5CE7))
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Feature highlights
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            FeatureHighlight(icon = Icons.Default.Movie, title = "Direct Sniffing")
            FeatureHighlight(icon = Icons.Default.Tv, title = "Native 1080p")
            FeatureHighlight(icon = Icons.Default.GroupAdd, title = "Voice Mesh")
        }

        Spacer(modifier = Modifier.height(30.dp))
    }
}

@Composable
fun FeatureHighlight(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color(0xFF161E30)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = Color(0xFF00E5FF),
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.75f)
        )
    }
}
