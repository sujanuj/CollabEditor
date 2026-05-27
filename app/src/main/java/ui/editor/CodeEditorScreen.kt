package com.collabedit.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.collabedit.app.sync.SyncService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeEditorScreen(
    viewModel: EditorViewModel,
    onGitHubClick: () -> Unit = {}
) {
    val text by viewModel.text.collectAsStateWithLifecycle()
    val remoteOpCount by viewModel.remoteOpCount.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val users by viewModel.users.collectAsStateWithLifecycle()
    val sessionId by viewModel.sessionId.collectAsStateWithLifecycle()
    val aiSuggestion by viewModel.aiSuggestion.collectAsStateWithLifecycle()
    val aiLoading by viewModel.aiLoading.collectAsStateWithLifecycle()
    val aiEnabled by viewModel.aiEnabled.collectAsStateWithLifecycle()

    var localText by remember { mutableStateOf(text) }
    var lastSyncedRemoteCount by remember { mutableStateOf(remoteOpCount) }

    // Key change: triggered by remoteOpCount only (not text).
    // Every time ANY remote op arrives (initial join, reconnect after
    // user leaves, live INSERT from other user), we immediately sync
    // lastSyncedRemoteCount = remoteOpCount BEFORE onValueChange fires.
    // This means the guard in onValueChange always sees the correct value.
    LaunchedEffect(remoteOpCount) {
        lastSyncedRemoteCount = remoteOpCount
        localText = text
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "CollabEditor",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Session: $sessionId",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleAi() }) {
                        Icon(
                            imageVector = Icons.Default.Psychology,
                            contentDescription = "Toggle AI",
                            tint = if (aiEnabled)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onGitHubClick) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = "GitHub",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    ConnectionIndicator(connectionState)
                    Spacer(modifier = Modifier.width(4.dp))
                    users.values.take(3).forEach { user ->
                        UserAvatar(name = user.userName, color = user.userColor)
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (users.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = "${users.size} user${if (users.size != 1) "s" else ""} connected: ${
                            users.values.joinToString(", ") { it.userName }
                        }",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            if (aiSuggestion != null && aiEnabled) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(0.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "✨ AI suggestion ready",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { viewModel.acceptAiSuggestion() },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    "Accept (Tab)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            TextButton(
                                onClick = { viewModel.dismissAiSuggestion() },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    "Dismiss",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            if (aiLoading && aiEnabled) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {

                if (aiSuggestion != null && aiEnabled) {
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(color = Color.Transparent)) {
                                append(localText)
                            }
                            withStyle(SpanStyle(
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                            )) {
                                append(aiSuggestion!!)
                            }
                        },
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            lineHeight = 22.sp
                        ),
                        modifier = Modifier.fillMaxSize().padding(16.dp)
                    )
                }

                TextField(
                    value = localText,
                    onValueChange = { newText ->
                        if (remoteOpCount > lastSyncedRemoteCount) {
                            lastSyncedRemoteCount = remoteOpCount
                            return@TextField
                        }

                        if (aiSuggestion != null) viewModel.dismissAiSuggestion()
                        val oldText = localText
                        localText = newText
                        viewModel.onTextChanged(newText, oldText)
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .onKeyEvent { keyEvent ->
                            when {
                                keyEvent.type == KeyEventType.KeyDown &&
                                        keyEvent.key == Key.Tab &&
                                        aiSuggestion != null &&
                                        aiEnabled -> {
                                    viewModel.acceptAiSuggestion()
                                    true
                                }
                                keyEvent.type == KeyEventType.KeyDown &&
                                        keyEvent.key == Key.Escape &&
                                        aiSuggestion != null -> {
                                    viewModel.dismissAiSuggestion()
                                    true
                                }
                                else -> false
                            }
                        },
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        lineHeight = 22.sp
                    ),
                    placeholder = {
                        Text(
                            text = "// Start typing your code here...\n// AI suggestions appear after you pause typing",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    )
                )
            }
        }
    }
}

@Composable
fun ConnectionIndicator(state: SyncService.ConnectionState) {
    val (color, label) = when (state) {
        SyncService.ConnectionState.CONNECTED -> Color(0xFF4CAF50) to "Live"
        SyncService.ConnectionState.CONNECTING -> Color(0xFFFF9800) to "Connecting"
        SyncService.ConnectionState.RECONNECTING -> Color(0xFFFF9800) to "Reconnecting"
        SyncService.ConnectionState.DISCONNECTED -> Color(0xFFF44336) to "Offline"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Circle,
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(10.dp)
        )
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
fun UserAvatar(name: String, color: String) {
    val bgColor = try {
        Color(android.graphics.Color.parseColor(color))
    } catch (e: Exception) {
        Color(0xFF6200EE)
    }
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(bgColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name.take(1).uppercase(),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
    }
}