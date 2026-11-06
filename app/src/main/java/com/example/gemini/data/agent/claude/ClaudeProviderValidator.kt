package com.example.gemini.data.agent.claude

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object ClaudeProviderValidator {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun validate(baseUrl: String, apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        val trimmedUrl = baseUrl.trim().trimEnd('/')

        if (trimmedKey.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("API key cannot be empty"))
        }

        try {
            val requestBuilder = Request.Builder()
            when {
                trimmedUrl.contains("openrouter.ai") -> {
                    val req = requestBuilder
                        .url("https://openrouter.ai/api/v1/auth/key")
                        .header("Authorization", "Bearer $trimmedKey")
                        .build()
                    val resp = httpClient.newCall(req).execute()
                    val body = resp.body?.string().orEmpty()
                    when (resp.code) {
                        200 -> Result.success("OpenRouter key verified successfully")
                        401 -> Result.failure(Exception("Invalid OpenRouter API key (HTTP 401)"))
                        402 -> Result.failure(Exception("Insufficient credits on OpenRouter account"))
                        429 -> Result.failure(Exception("OpenRouter rate limit reached (HTTP 429)"))
                        else -> Result.failure(Exception("OpenRouter returned HTTP ${resp.code}: ${body.take(120)}"))
                    }
                }
                trimmedUrl.contains("anthropic.com") -> {
                    val req = requestBuilder
                        .url("https://api.anthropic.com/v1/models")
                        .header("x-api-key", trimmedKey)
                        .header("anthropic-version", "2023-06-01")
                        .build()
                    val resp = httpClient.newCall(req).execute()
                    val body = resp.body?.string().orEmpty()
                    when (resp.code) {
                        200 -> Result.success("Anthropic API key verified successfully")
                        401 -> Result.failure(Exception("Invalid Anthropic API key (HTTP 401)"))
                        403 -> Result.failure(Exception("Access forbidden. Check your Anthropic Console permissions (HTTP 403)"))
                        429 -> Result.failure(Exception("Anthropic rate limit reached (HTTP 429)"))
                        else -> Result.failure(Exception("Anthropic returned HTTP ${resp.code}: ${body.take(120)}"))
                    }
                }
                trimmedUrl.contains("deepseek.com") -> {
                    val req = requestBuilder
                        .url("https://api.deepseek.com/models")
                        .header("Authorization", "Bearer $trimmedKey")
                        .build()
                    val resp = httpClient.newCall(req).execute()
                    val body = resp.body?.string().orEmpty()
                    when (resp.code) {
                        200 -> Result.success("DeepSeek API key verified successfully")
                        401 -> Result.failure(Exception("Invalid DeepSeek API key (HTTP 401)"))
                        402 -> Result.failure(Exception("Insufficient balance on DeepSeek account"))
                        else -> Result.failure(Exception("DeepSeek returned HTTP ${resp.code}: ${body.take(120)}"))
                    }
                }
                else -> {
                    val testUrl = if (trimmedUrl.endsWith("/models")) trimmedUrl else "$trimmedUrl/models"
                    val req = requestBuilder
                        .url(testUrl)
                        .header("Authorization", "Bearer $trimmedKey")
                        .header("x-api-key", trimmedKey)
                        .header("anthropic-version", "2023-06-01")
                        .build()
                    val resp = httpClient.newCall(req).execute()
                    if (resp.isSuccessful) {
                        Result.success("Endpoint connected successfully (HTTP ${resp.code})")
                    } else if (resp.code == 401 || resp.code == 403) {
                        Result.failure(Exception("Authentication failed (HTTP ${resp.code}). Check your API key."))
                    } else {
                        Result.success("Endpoint responded with HTTP ${resp.code}")
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception("Connection failed: ${e.message ?: "Unknown network error"}"))
        }
    }
}
