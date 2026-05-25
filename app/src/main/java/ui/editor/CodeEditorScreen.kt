package com.collabedit.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
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
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val users by viewModel.users.collectAsStateWithLifecycle()
    val sessionId by viewModel.sessionId.collectAsStateWithLifecycle()

    var localText by remember { mutableStateOf(text) }
    var isApplyingRemote by remember { mutableStateOf(false) }

    LaunchedEffect(text) {
        if (localText != text) {
            isApplyingRemote = true
            localText = text
            isApplyingRemote = false
        }
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
                    // GitHub button
                    IconButton(onClick = onGitHubClick) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = "Open from GitHub",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    ConnectionIndicator(connectionState)
                    Spacer(modifier = Modifier.width(8.dp))
                    users.values.take(4).forEach { user ->
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
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    tonalElevation = 1.dp
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

            TextField(
                value = localText,
                onValueChange = { newText ->
                    if (!isApplyingRemote) {
                        val oldText = localText
                        localText = newText
                        viewModel.onTextChanged(newText, oldText)
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    lineHeight = 22.sp
                ),
                placeholder = {
                    Text(
                        text = "// Start typing your code here...\n// Share the Session ID to collaborate in real time",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )
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