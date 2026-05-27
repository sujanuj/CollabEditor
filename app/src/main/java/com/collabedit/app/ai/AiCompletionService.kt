package com.collabedit.app.ai

import android.util.Log
import com.collabedit.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "AiCompletionService"
private const val ANTHROPIC_API_KEY = BuildConfig.ANTHROPIC_API_KEY
private const val DEBOUNCE_MS = 1000L

/**
 * Provides AI-powered code completions using the Claude API.
 *
 * Flow:
 * 1. User types and pauses for 1 second
 * 2. We send the code context to Claude
 * 3. Claude returns a completion suggestion
 * 4. We show it as ghost text in the editor
 * 5. User presses Tab to accept or keeps typing to dismiss
 */
class AiCompletionService(private val scope: CoroutineScope) {

    private val _suggestion = MutableStateFlow<String?>(null)
    val suggestion: StateFlow<String?> = _suggestion.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var debounceJob: Job? = null
    private var fetchJob: Job? = null
    private var isEnabled = true

    /**
     * Called every time the user types.
     * Debounces requests so we only call the API after 1 second of no typing.
     */
    fun onTextChanged(currentText: String, cursorPosition: Int) {
        if (!isEnabled || currentText.isBlank()) {
            clearSuggestion()
            return
        }

        // Cancel both the pending debounce AND any in-flight HTTP call
        debounceJob?.cancel()
        fetchJob?.cancel()
        _isLoading.value = false

        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            if (isActive) {
                requestCompletion(currentText, cursorPosition)
            }
        }
    }

    /**
     * User accepted the suggestion by pressing Tab.
     * Returns the accepted text and clears the suggestion.
     */
    fun acceptSuggestion(): String? {
        val accepted = _suggestion.value
        clearSuggestion()
        return accepted
    }

    fun clearSuggestion() {
        debounceJob?.cancel()
        fetchJob?.cancel()
        _suggestion.value = null
        _isLoading.value = false
    }

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (!enabled) clearSuggestion()
    }

    private fun requestCompletion(code: String, cursorPosition: Int) {
        if (ANTHROPIC_API_KEY.isBlank() || ANTHROPIC_API_KEY == "YOUR_ANTHROPIC_API_KEY_HERE") {
            Log.w(TAG, "API key not set — skipping completion")
            return
        }

        _isLoading.value = true
        _suggestion.value = null

        fetchJob = scope.launch {
            try {
                val textBeforeCursor = code.substring(0, minOf(cursorPosition, code.length))
                val textAfterCursor = if (cursorPosition < code.length)
                    code.substring(cursorPosition) else ""

                val suggestion = callClaudeApi(textBeforeCursor, textAfterCursor)

                if (suggestion != null && suggestion.isNotBlank()) {
                    _suggestion.value = suggestion
                    Log.d(TAG, "Got suggestion: ${suggestion.take(50)}...")
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Completion cancelled (user kept typing)")
            } catch (e: Exception) {
                Log.e(TAG, "Completion failed: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun callClaudeApi(
        textBefore: String,
        textAfter: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.anthropic.com/v1/messages")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("x-api-key", ANTHROPIC_API_KEY)
            connection.setRequestProperty("anthropic-version", "2023-06-01")
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 15000

            val prompt = buildPrompt(textBefore, textAfter)

            val requestBody = JSONObject().apply {
                put("model", "claude-sonnet-4-5")
                put("max_tokens", 150)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                put(
                    "system",
                    """You are an expert code completion assistant embedded in a collaborative code editor.
Your job is to predict what comes next after the cursor position.

Rules:
- Return ONLY the completion text, nothing else
- No explanations, no markdown, no code blocks, no backticks
- Keep completions concise — typically 1 line, max 3 lines
- Match the indentation and style of the existing code
- If you cannot make a confident prediction, return an empty string
- Complete the current statement or start the next logical one"""
                )
            }

            connection.outputStream.write(requestBody.toString().toByteArray())

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val error = connection.errorStream?.bufferedReader()?.readText()
                Log.e(TAG, "API error $responseCode: $error")
                return@withContext null
            }

            val response = connection.inputStream.bufferedReader().readText()
            parseCompletion(response)

        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "API call failed: ${e.message}")
            null
        }
    }

    private fun buildPrompt(textBefore: String, textAfter: String): String {
        val contextBefore = if (textBefore.length > 500)
            "...\n${textBefore.takeLast(500)}" else textBefore

        return if (textAfter.isBlank()) {
            "Complete this code. The cursor is at the end:\n\n$contextBefore<CURSOR>"
        } else {
            val contextAfter = textAfter.take(100)
            "Complete this code at the cursor position:\n\n$contextBefore<CURSOR>$contextAfter"
        }
    }

    private fun parseCompletion(responseJson: String): String? {
        return try {
            val json = JSONObject(responseJson)
            val content = json.getJSONArray("content")
            if (content.length() > 0) {
                var text = content.getJSONObject(0).getString("text").trim()

                // Strip markdown code fences if Claude includes them despite instructions
                text = text
                    .removePrefix("```kotlin")
                    .removePrefix("```java")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()

                if (text.isEmpty()) null else text
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Parse failed: ${e.message}")
            null
        }
    }
}