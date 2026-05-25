package com.collabedit.app.github

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "GitHubAuthManager"

/**
 * Manages GitHub OAuth authentication flow.
 *
 * Flow:
 * 1. App opens GitHub OAuth URL in browser
 * 2. User authorizes the app
 * 3. GitHub redirects to collabedit://callback?code=XXXX
 * 4. App intercepts the redirect via deep link
 * 5. App exchanges the code for an access token
 * 6. Access token stored in SharedPreferences
 */
object GitHubAuthManager {

    // Replace with your actual values
    private const val CLIENT_ID = "Ov23liWiOhL3rMHZ9x8d"
    private const val CLIENT_SECRET = "5720ac1629b783ed49835741c3d71e5e956ffa32"
    private const val REDIRECT_URI = "collabedit://callback"
    private const val SCOPE = "repo,user"

    private const val PREFS_NAME = "github_auth"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_USERNAME = "username"
    private const val KEY_AVATAR_URL = "avatar_url"

    private val _authState = MutableStateFlow(AuthState.LOGGED_OUT)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _currentUser = MutableStateFlow<GitHubUser?>(null)
    val currentUser: StateFlow<GitHubUser?> = _currentUser.asStateFlow()

    enum class AuthState {
        LOGGED_OUT, LOGGING_IN, LOGGED_IN, ERROR
    }

    data class GitHubUser(
        val login: String,
        val avatarUrl: String,
        val name: String?
    )

    /**
     * Step 1 — Open GitHub login in browser
     */
    fun launchOAuthFlow(context: Context) {
        val authUrl = Uri.parse("https://github.com/login/oauth/authorize")
            .buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("state", generateState())
            .build()

        val intent = Intent(Intent.ACTION_VIEW, authUrl).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        _authState.value = AuthState.LOGGING_IN
        Log.d(TAG, "Launched OAuth flow: $authUrl")
    }

    /**
     * Step 2 — Called when app receives the callback deep link
     * with the authorization code
     */
    suspend fun handleCallback(context: Context, code: String): Boolean {
        return try {
            Log.d(TAG, "Handling callback with code: ${code.take(8)}...")
            val token = exchangeCodeForToken(code)
            if (token != null) {
                saveToken(context, token)
                val user = fetchCurrentUser(token)
                if (user != null) {
                    saveUser(context, user)
                    _currentUser.value = user
                    _authState.value = AuthState.LOGGED_IN
                    Log.d(TAG, "Logged in as ${user.login}")
                    true
                } else {
                    _authState.value = AuthState.ERROR
                    false
                }
            } else {
                _authState.value = AuthState.ERROR
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "OAuth callback failed: ${e.message}")
            _authState.value = AuthState.ERROR
            false
        }
    }

    /**
     * Exchange authorization code for access token
     */
    private suspend fun exchangeCodeForToken(code: String): String? {
        return try {
            val url = java.net.URL("https://github.com/login/oauth/access_token")
            val body = "client_id=$CLIENT_ID&client_secret=$CLIENT_SECRET&code=$code&redirect_uri=$REDIRECT_URI"

            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.doOutput = true

            connection.outputStream.write(body.toByteArray())

            val response = connection.inputStream.bufferedReader().readText()
            Log.d(TAG, "Token response: $response")

            // Parse JSON response
            val tokenRegex = """"access_token"\s*:\s*"([^"]+)"""".toRegex()
            tokenRegex.find(response)?.groupValues?.get(1)
        } catch (e: Exception) {
            Log.e(TAG, "Token exchange failed: ${e.message}")
            null
        }
    }

    /**
     * Fetch authenticated user's profile
     */
    suspend fun fetchCurrentUser(token: String): GitHubUser? {
        return try {
            val url = java.net.URL("https://api.github.com/user")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")

            val response = connection.inputStream.bufferedReader().readText()

            val loginRegex = """"login"\s*:\s*"([^"]+)"""".toRegex()
            val avatarRegex = """"avatar_url"\s*:\s*"([^"]+)"""".toRegex()
            val nameRegex = """"name"\s*:\s*"([^"]+)"""".toRegex()

            val login = loginRegex.find(response)?.groupValues?.get(1) ?: return null
            val avatar = avatarRegex.find(response)?.groupValues?.get(1) ?: ""
            val name = nameRegex.find(response)?.groupValues?.get(1)

            GitHubUser(login, avatar, name)
        } catch (e: Exception) {
            Log.e(TAG, "Fetch user failed: ${e.message}")
            null
        }
    }

    fun getAccessToken(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACCESS_TOKEN, null)
    }

    fun isLoggedIn(context: Context): Boolean {
        return getAccessToken(context) != null
    }

    fun logout(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()
        _authState.value = AuthState.LOGGED_OUT
        _currentUser.value = null
    }

    fun loadSavedAuth(context: Context) {
        val token = getAccessToken(context)
        if (token != null) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val login = prefs.getString(KEY_USERNAME, null)
            val avatar = prefs.getString(KEY_AVATAR_URL, null)
            if (login != null) {
                _currentUser.value = GitHubUser(login, avatar ?: "", null)
                _authState.value = AuthState.LOGGED_IN
            }
        }
    }

    private fun saveToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACCESS_TOKEN, token).apply()
    }

    private fun saveUser(context: Context, user: GitHubUser) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_USERNAME, user.login)
            .putString(KEY_AVATAR_URL, user.avatarUrl)
            .apply()
    }

    private fun generateState(): String =
        java.util.UUID.randomUUID().toString().replace("-", "").take(16)
}