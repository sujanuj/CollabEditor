package com.collabedit.app.github

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "GitHubRepository"

/**
 * Handles all GitHub REST API calls.
 * - List user's repos
 * - Browse repo files
 * - Fetch file content
 * - Push file changes
 */
class GitHubRepository(private val accessToken: String) {

    data class Repo(
        val name: String,
        val fullName: String,
        val description: String?,
        val isPrivate: Boolean,
        val defaultBranch: String,
        val language: String?
    )

    data class FileItem(
        val name: String,
        val path: String,
        val type: String, // "file" or "dir"
        val size: Int,
        val sha: String
    )

    data class FileContent(
        val name: String,
        val path: String,
        val content: String, // decoded content
        val sha: String,     // needed for updating
        val encoding: String
    )

    /**
     * Get all repos for the authenticated user
     */
    suspend fun getUserRepos(): List<Repo> = withContext(Dispatchers.IO) {
        try {
            val response = makeRequest("https://api.github.com/user/repos?sort=updated&per_page=50")
            parseRepos(response)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get repos: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get contents of a directory in a repo
     */
    suspend fun getRepoContents(
        owner: String,
        repo: String,
        path: String = ""
    ): List<FileItem> = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.github.com/repos/$owner/$repo/contents/$path"
            val response = makeRequest(url)
            parseFileItems(response)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get contents: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get the content of a specific file
     */
    suspend fun getFileContent(
        owner: String,
        repo: String,
        path: String
    ): FileContent? = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.github.com/repos/$owner/$repo/contents/$path"
            val response = makeRequest(url)
            parseFileContent(response)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get file: ${e.message}")
            null
        }
    }

    /**
     * Push updated file content back to GitHub
     */
    suspend fun updateFile(
        owner: String,
        repo: String,
        path: String,
        content: String,
        sha: String,
        commitMessage: String = "Update via CollabEditor"
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.github.com/repos/$owner/$repo/contents/$path"
            val encodedContent = android.util.Base64.encodeToString(
                content.toByteArray(),
                android.util.Base64.NO_WRAP
            )
            val body = """
                {
                    "message": "$commitMessage",
                    "content": "$encodedContent",
                    "sha": "$sha"
                }
            """.trimIndent()

            makePutRequest(url, body)
            Log.d(TAG, "File updated: $path")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update file: ${e.message}")
            false
        }
    }

    // -------------------------------------------------------------------------
    // HTTP helpers
    // -------------------------------------------------------------------------

    private fun makeRequest(url: String): String {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
        connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
        return connection.inputStream.bufferedReader().readText()
    }

    private fun makePutRequest(url: String, body: String): String {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "PUT"
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
        connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.outputStream.write(body.toByteArray())
        return connection.inputStream.bufferedReader().readText()
    }

    // -------------------------------------------------------------------------
    // JSON parsers (simple regex-based to avoid adding Gson dependency issues)
    // -------------------------------------------------------------------------

    private fun parseRepos(json: String): List<Repo> {
        val repos = mutableListOf<Repo>()
        // Extract each repo object
        val nameRegex = """"name"\s*:\s*"([^"]+)"""".toRegex()
        val fullNameRegex = """"full_name"\s*:\s*"([^"]+)"""".toRegex()
        val descRegex = """"description"\s*:\s*"([^"]+)"""".toRegex()
        val branchRegex = """"default_branch"\s*:\s*"([^"]+)"""".toRegex()
        val langRegex = """"language"\s*:\s*"([^"]+)"""".toRegex()

        // Simple approach: split by full_name occurrences
        val fullNames = fullNameRegex.findAll(json).toList()
        val names = nameRegex.findAll(json).toList()
        val branches = branchRegex.findAll(json).toList()

        for (i in fullNames.indices) {
            try {
                val fullName = fullNames[i].groupValues[1]
                val parts = fullName.split("/")
                repos.add(Repo(
                    name = parts.lastOrNull() ?: fullName,
                    fullName = fullName,
                    description = null,
                    isPrivate = json.contains(""""private":true"""),
                    defaultBranch = branches.getOrNull(i)?.groupValues?.get(1) ?: "main",
                    language = null
                ))
            } catch (e: Exception) { /* skip */ }
        }
        return repos
    }

    private fun parseFileItems(json: String): List<FileItem> {
        val items = mutableListOf<FileItem>()
        val nameRegex = """"name"\s*:\s*"([^"]+)"""".toRegex()
        val pathRegex = """"path"\s*:\s*"([^"]+)"""".toRegex()
        val typeRegex = """"type"\s*:\s*"([^"]+)"""".toRegex()
        val shaRegex = """"sha"\s*:\s*"([^"]+)"""".toRegex()

        val names = nameRegex.findAll(json).toList()
        val paths = pathRegex.findAll(json).toList()
        val types = typeRegex.findAll(json).toList()
        val shas = shaRegex.findAll(json).toList()

        for (i in names.indices) {
            try {
                items.add(FileItem(
                    name = names[i].groupValues[1],
                    path = paths.getOrNull(i)?.groupValues?.get(1) ?: "",
                    type = types.getOrNull(i)?.groupValues?.get(1) ?: "file",
                    size = 0,
                    sha = shas.getOrNull(i)?.groupValues?.get(1) ?: ""
                ))
            } catch (e: Exception) { /* skip */ }
        }
        return items
    }

    private fun parseFileContent(json: String): FileContent? {
        return try {
            val nameRegex = """"name"\s*:\s*"([^"]+)"""".toRegex()
            val pathRegex = """"path"\s*:\s*"([^"]+)"""".toRegex()
            val contentRegex = """"content"\s*:\s*"([^"]+)"""".toRegex()
            val shaRegex = """"sha"\s*:\s*"([^"]+)"""".toRegex()
            val encodingRegex = """"encoding"\s*:\s*"([^"]+)"""".toRegex()

            val name = nameRegex.find(json)?.groupValues?.get(1) ?: return null
            val path = pathRegex.find(json)?.groupValues?.get(1) ?: return null
            val encodedContent = contentRegex.find(json)?.groupValues?.get(1) ?: return null
            val sha = shaRegex.find(json)?.groupValues?.get(1) ?: return null
            val encoding = encodingRegex.find(json)?.groupValues?.get(1) ?: "base64"

            // Decode base64 content (GitHub returns base64 with \n)
            val cleanContent = encodedContent.replace("\\n", "").replace("\n", "")
            val decodedBytes = android.util.Base64.decode(cleanContent, android.util.Base64.DEFAULT)
            val decodedContent = String(decodedBytes)

            FileContent(name, path, decodedContent, sha, encoding)
        } catch (e: Exception) {
            Log.e(TAG, "Parse file content failed: ${e.message}")
            null
        }
    }
}