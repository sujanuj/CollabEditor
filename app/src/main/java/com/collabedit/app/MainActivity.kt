package com.collabedit.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.collabedit.app.github.GitHubAuthManager
import com.collabedit.app.ui.editor.CodeEditorScreen
import com.collabedit.app.ui.editor.EditorViewModel
import com.collabedit.app.ui.editor.GitHubScreen
import com.collabedit.app.ui.editor.SessionScreen
import com.collabedit.app.ui.theme.CollabEditorTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle OAuth callback if app was opened via deep link
        handleIntent(intent)

        setContent {
            CollabEditorTheme {
                val sessionJoined by viewModel.sessionJoined.collectAsState()
                var showGitHub by remember { mutableStateOf(false) }

                when {
                    showGitHub -> {
                        GitHubScreen(
                            onFileSelected = { content, fileName ->
                                showGitHub = false
                                // Load file into editor
                                viewModel.loadFileContent(content)
                            },
                            onDismiss = { showGitHub = false }
                        )
                    }
                    sessionJoined -> {
                        CodeEditorScreen(
                            viewModel = viewModel,
                            onGitHubClick = { showGitHub = true }
                        )
                    }
                    else -> {
                        SessionScreen(
                            onJoinSession = { sessionId, userName ->
                                viewModel.joinSession(sessionId, userName)
                            },
                            onGitHubClick = { showGitHub = true }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "collabedit" && data.host == "callback") {
            val code = data.getQueryParameter("code") ?: return
            lifecycleScope.launch {
                GitHubAuthManager.handleCallback(this@MainActivity, code)
            }
        }
    }
}