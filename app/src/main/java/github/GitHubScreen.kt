package com.collabedit.app.ui.editor

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.collabedit.app.github.GitHubAuthManager
import com.collabedit.app.github.GitHubRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubScreen(
    onFileSelected: (content: String, fileName: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authState by GitHubAuthManager.authState.collectAsState()
    val currentUser by GitHubAuthManager.currentUser.collectAsState()

    var repos by remember { mutableStateOf<List<GitHubRepository.Repo>>(emptyList()) }
    var currentPath by remember { mutableStateOf<List<String>>(emptyList()) }
    var files by remember { mutableStateOf<List<GitHubRepository.FileItem>>(emptyList()) }
    var selectedRepo by remember { mutableStateOf<GitHubRepository.Repo?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        GitHubAuthManager.loadSavedAuth(context)
        if (GitHubAuthManager.isLoggedIn(context)) {
            loadRepos(context) { result, err ->
                repos = result
                error = err
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GitHub") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Close")
                    }
                },
                actions = {
                    if (authState == GitHubAuthManager.AuthState.LOGGED_IN) {
                        currentUser?.let { user ->
                            Text(
                                text = user.login,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                        IconButton(onClick = {
                            GitHubAuthManager.logout(context)
                            repos = emptyList()
                            selectedRepo = null
                        }) {
                            Icon(Icons.Default.ExitToApp, "Logout")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                authState != GitHubAuthManager.AuthState.LOGGED_IN -> {
                    // Not logged in — show login button
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.AccountCircle,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Connect GitHub",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Browse your repos and open files directly in the editor",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(32.dp))
                        Button(
                            onClick = { GitHubAuthManager.launchOAuthFlow(context) },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Lock, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Login with GitHub", fontSize = 16.sp)
                        }
                    }
                }

                selectedRepo == null -> {
                    // Show repo list
                    if (repos.isEmpty()) {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text("Loading repositories...")
                        }
                    } else {
                        LazyColumn {
                            item {
                                Text(
                                    "Your Repositories",
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(16.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            items(repos) { repo ->
                                RepoItem(repo = repo) {
                                    selectedRepo = repo
                                    isLoading = true
                                    scope.launch {
                                        val token = GitHubAuthManager.getAccessToken(context)!!
                                        val ghRepo = GitHubRepository(token)
                                        val parts = repo.fullName.split("/")
                                        files = ghRepo.getRepoContents(parts[0], parts[1])
                                        currentPath = listOf(repo.fullName)
                                        isLoading = false
                                    }
                                }
                            }
                        }
                    }
                }

                else -> {
                    // Show file browser
                    Column {
                        // Breadcrumb
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = {
                                selectedRepo = null
                                files = emptyList()
                                currentPath = emptyList()
                            }) {
                                Text(selectedRepo?.name ?: "")
                            }
                            if (currentPath.size > 1) {
                                Text(" / ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    currentPath.drop(1).joinToString(" / "),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        Divider()

                        LazyColumn {
                            items(files.sortedWith(compareBy({ it.type != "dir" }, { it.name }))) { file ->
                                FileItem(file = file) {
                                    if (file.type == "dir") {
                                        isLoading = true
                                        scope.launch {
                                            val token = GitHubAuthManager.getAccessToken(context)!!
                                            val ghRepo = GitHubRepository(token)
                                            val parts = selectedRepo!!.fullName.split("/")
                                            files = ghRepo.getRepoContents(parts[0], parts[1], file.path)
                                            currentPath = currentPath + file.name
                                            isLoading = false
                                        }
                                    } else {
                                        // Open file in editor
                                        isLoading = true
                                        scope.launch {
                                            val token = GitHubAuthManager.getAccessToken(context)!!
                                            val ghRepo = GitHubRepository(token)
                                            val parts = selectedRepo!!.fullName.split("/")
                                            val content = ghRepo.getFileContent(parts[0], parts[1], file.path)
                                            isLoading = false
                                            if (content != null) {
                                                onFileSelected(content.content, file.name)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            error?.let {
                Snackbar(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                ) { Text(it) }
            }
        }
    }
}

@Composable
private fun RepoItem(repo: GitHubRepository.Repo, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(repo.name, fontWeight = FontWeight.Medium) },
        supportingContent = {
            repo.description?.let { Text(it, maxLines = 1) }
        },
        leadingContent = {
            Icon(
                Icons.Default.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        trailingContent = {
            repo.language?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
    Divider(modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun FileItem(file: GitHubRepository.FileItem, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(file.name) },
        leadingContent = {
            Icon(
                if (file.type == "dir") Icons.Default.Folder else Icons.Default.InsertDriveFile,
                contentDescription = null,
                tint = if (file.type == "dir")
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
    Divider(modifier = Modifier.padding(horizontal = 16.dp))
}

private suspend fun loadRepos(
    context: Context,
    callback: (List<GitHubRepository.Repo>, String?) -> Unit
) {
    try {
        val token = GitHubAuthManager.getAccessToken(context) ?: return
        val repos = GitHubRepository(token).getUserRepos()
        callback(repos, null)
    } catch (e: Exception) {
        callback(emptyList(), "Failed to load repos: ${e.message}")
    }
}