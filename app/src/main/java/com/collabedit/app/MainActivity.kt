package com.collabedit.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.compose.ui.tooling.preview.Preview
import com.collabedit.app.ui.editor.CodeEditorScreen
import com.collabedit.app.ui.editor.EditorViewModel
import com.collabedit.app.ui.editor.SessionScreen
import com.collabedit.app.ui.theme.CollabEditorTheme

class MainActivity : ComponentActivity() {

    private val viewModel: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CollabEditorTheme {
                val sessionJoined by viewModel.sessionJoined.collectAsState()

                if (sessionJoined) {
                    CodeEditorScreen(viewModel = viewModel)
                } else {
                    SessionScreen(
                        onJoinSession = { sessionId, userName ->
                            viewModel.joinSession(sessionId, userName)
                        }
                    )
                }
            }
        }
    }
}