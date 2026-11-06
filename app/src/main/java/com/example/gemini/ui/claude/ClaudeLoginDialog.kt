package com.example.gemini.ui.claude

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.agent.claude.ClaudeAccountManager
import com.example.gemini.data.agent.claude.ClaudeChatBackend
import com.example.gemini.data.agent.claude.ClaudeProvider
import com.example.gemini.data.agent.claude.ClaudeProviderModel
import com.example.gemini.data.agent.claude.ClaudeProviderValidator
import com.example.gemini.theme.QuotaGreen
import com.example.gemini.theme.QuotaRed
import com.example.gemini.ui.components.ClaudeAccent
import kotlinx.coroutines.launch

private enum class SetupTab(val label: String) {
    SUBSCRIPTION("Claude Sub"),
    OPENROUTER("OpenRouter"),
    DEEPSEEK("DeepSeek"),
    ANTHROPIC_KEY("Anthropic Key"),
    CUSTOM("Custom API")
}

/**
 * Unified Claude Code setup dialog:
 * Supports official Claude subscriptions (OAuth), direct Anthropic API keys,
 * OpenRouter, DeepSeek, and custom Anthropic-compatible endpoints.
 */
@Composable
fun ClaudeLoginDialog(
    account: ClaudeAccountManager,
    backend: ClaudeChatBackend? = null,
    onSelectModel: ((String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val login by account.login.collectAsState()
    val busy by account.isBusy.collectAsState()
    val status by account.status.collectAsState()

    var activeTab by remember { mutableStateOf(SetupTab.SUBSCRIPTION) }
    var useCode by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var openedFor by remember { mutableStateOf<String?>(null) }

    // OpenRouter state
    var openRouterKey by remember { mutableStateOf("") }
    var openRouterModel by remember { mutableStateOf("anthropic/claude-3.5-sonnet") }

    // DeepSeek state
    var deepSeekKey by remember { mutableStateOf("") }
    var deepSeekModel by remember { mutableStateOf("deepseek-chat") }

    // Anthropic API Key state
    var anthropicKey by remember { mutableStateOf("") }
    var anthropicModel by remember { mutableStateOf("claude-3-5-sonnet-latest") }

    // Custom API state
    var customName by remember { mutableStateOf("Custom Provider") }
    var customBaseUrl by remember { mutableStateOf("") }
    var customKey by remember { mutableStateOf("") }
    var customModel by remember { mutableStateOf("") }

    // Status state
    var isSaving by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var validationMessage by remember { mutableStateOf<String?>(null) }
    var validationSuccess by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    // close once signed in via OAuth
    LaunchedEffect(status?.auth?.loggedIn, login) {
        if (login == null && status?.auth?.loggedIn == true && openedFor != null) onDismiss()
    }
    // open the browser automatically when OAuth starts
    LaunchedEffect(login?.login_id) {
        val l = login ?: return@LaunchedEffect
        if (openedFor != l.login_id && l.automatic_url.isNotBlank()) {
            openedFor = l.login_id
            open(l.automatic_url)
        }
    }

    fun saveAndActivate(provider: ClaudeProvider, targetModelId: String) {
        if (backend == null) {
            errorMessage = "Claude backend service is not ready. Please try again."
            return
        }
        isSaving = true
        errorMessage = null
        backend.saveProvider(provider) { err, saved ->
            isSaving = false
            if (err == null) {
                val effectiveId = saved?.id?.ifBlank { null } ?: provider.id.ifBlank { provider.name.lowercase().replace(Regex("[^a-z0-9]"), "-") }
                val fullModelRef = "@$effectiveId/$targetModelId"
                account.refreshStatus()
                onSelectModel?.invoke(fullModelRef)
                Toast.makeText(context, "${provider.name} configured! Ready to chat.", Toast.LENGTH_SHORT).show()
                onDismiss()
            } else {
                errorMessage = err
            }
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (login != null) account.cancelLogin()
            onDismiss()
        },
        shape = RoundedCornerShape(18.dp),
        title = {
            Text("Set Up Claude Code CLI", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Provider Selection Chips
                if (backend != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SetupTab.entries.forEach { tab ->
                            FilterChip(
                                selected = activeTab == tab,
                                onClick = {
                                    activeTab = tab
                                    validationMessage = null
                                    errorMessage = null
                                },
                                label = { Text(tab.label, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ClaudeAccent.copy(alpha = 0.2f),
                                    selectedLabelColor = ClaudeAccent
                                )
                            )
                        }
                    }
                }

                when (activeTab) {
                    SetupTab.SUBSCRIPTION -> {
                        val l = login
                        if (l == null) {
                            Text(
                                "Sign in with your official Claude account or Anthropic Console via browser OAuth.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                            )
                            Button(
                                onClick = { account.startLogin("claudeai") },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)
                            ) {
                                Icon(Icons.Outlined.WorkspacePremium, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Claude subscription (Pro / Max)")
                            }
                            OutlinedButton(
                                onClick = { account.startLogin("console") },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Outlined.Key, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Anthropic Console (OAuth)")
                            }
                            if (busy) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Starting…", fontSize = 12.5.sp)
                                }
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = ClaudeAccent)
                                Spacer(Modifier.width(10.dp))
                                Text("Waiting for approval in browser…", fontSize = 13.sp)
                            }
                            OutlinedButton(onClick = { open(l.automatic_url) }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Outlined.OpenInBrowser, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Open sign-in page")
                            }
                            Spacer(Modifier.height(2.dp))
                            if (!useCode) {
                                TextButton(onClick = {
                                    useCode = true
                                    open(l.manual_url)
                                }) { Text("Can't return to app? Enter code manually", fontSize = 12.sp) }
                            } else {
                                Text(
                                    "Paste the approval code from Claude:",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                                )
                                OutlinedTextField(
                                    value = code,
                                    onValueChange = { code = it },
                                    singleLine = true,
                                    placeholder = { Text("code#state") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { open(l.manual_url) }) { Text("Open page", fontSize = 12.sp) }
                                    Button(
                                        onClick = { account.submitCode(code.trim()) },
                                        enabled = code.isNotBlank(),
                                        colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)
                                    ) { Text("Submit code", fontSize = 12.sp) }
                                }
                            }
                        }
                    }

                    SetupTab.OPENROUTER -> {
                        Text(
                            "Connect your OpenRouter account to run Claude 3.5 Sonnet, DeepSeek, and other top models through Claude Code.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )
                        OutlinedTextField(
                            value = openRouterKey,
                            onValueChange = { openRouterKey = it; validationMessage = null },
                            label = { Text("OpenRouter API Key") },
                            placeholder = { Text("sk-or-v1-…") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = openRouterModel,
                            onValueChange = { openRouterModel = it },
                            label = { Text("Default Model") },
                            placeholder = { Text("anthropic/claude-3.5-sonnet") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                enabled = !isTesting && openRouterKey.isNotBlank(),
                                onClick = {
                                    isTesting = true
                                    validationMessage = null
                                    coroutineScope.launch {
                                        val res = ClaudeProviderValidator.validate("https://openrouter.ai/api", openRouterKey)
                                        isTesting = false
                                        validationSuccess = res.isSuccess
                                        validationMessage = res.getOrElse { it.message ?: "Validation failed" }
                                    }
                                }
                            ) {
                                if (isTesting) {
                                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text("Test Key", fontSize = 12.sp)
                            }

                            Button(
                                enabled = !isSaving && openRouterKey.isNotBlank() && openRouterModel.isNotBlank(),
                                onClick = {
                                    val provider = ClaudeProvider(
                                        name = "OpenRouter",
                                        base_url = "https://openrouter.ai/api",
                                        api_key = openRouterKey.trim(),
                                        models_url = "https://openrouter.ai/api/v1/models",
                                        models = listOf(
                                            ClaudeProviderModel("anthropic/claude-3.5-sonnet", "Claude 3.5 Sonnet"),
                                            ClaudeProviderModel("anthropic/claude-3.7-sonnet", "Claude 3.7 Sonnet"),
                                            ClaudeProviderModel("deepseek/deepseek-chat", "DeepSeek V3"),
                                            ClaudeProviderModel("deepseek/deepseek-r1", "DeepSeek R1"),
                                            ClaudeProviderModel("google/gemini-2.5-pro", "Gemini 2.5 Pro")
                                        )
                                    )
                                    saveAndActivate(provider, openRouterModel.trim())
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)
                            ) {
                                if (isSaving) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                else Text("Save & Use OpenRouter", fontSize = 12.sp)
                            }
                        }
                    }

                    SetupTab.DEEPSEEK -> {
                        Text(
                            "Connect directly to DeepSeek's official Anthropic-compatible API endpoint.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )
                        OutlinedTextField(
                            value = deepSeekKey,
                            onValueChange = { deepSeekKey = it; validationMessage = null },
                            label = { Text("DeepSeek API Key") },
                            placeholder = { Text("sk-…") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = deepSeekModel,
                            onValueChange = { deepSeekModel = it },
                            label = { Text("Model ID") },
                            placeholder = { Text("deepseek-chat or deepseek-reasoner") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                enabled = !isTesting && deepSeekKey.isNotBlank(),
                                onClick = {
                                    isTesting = true
                                    validationMessage = null
                                    coroutineScope.launch {
                                        val res = ClaudeProviderValidator.validate("https://api.deepseek.com/anthropic", deepSeekKey)
                                        isTesting = false
                                        validationSuccess = res.isSuccess
                                        validationMessage = res.getOrElse { it.message ?: "Validation failed" }
                                    }
                                }
                            ) {
                                if (isTesting) {
                                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text("Test Key", fontSize = 12.sp)
                            }

                            Button(
                                enabled = !isSaving && deepSeekKey.isNotBlank() && deepSeekModel.isNotBlank(),
                                onClick = {
                                    val provider = ClaudeProvider(
                                        name = "DeepSeek",
                                        base_url = "https://api.deepseek.com/anthropic",
                                        api_key = deepSeekKey.trim(),
                                        models_url = "https://api.deepseek.com/models",
                                        models = listOf(
                                            ClaudeProviderModel("deepseek-chat", "DeepSeek Chat (V3)"),
                                            ClaudeProviderModel("deepseek-reasoner", "DeepSeek Reasoner (R1)")
                                        )
                                    )
                                    saveAndActivate(provider, deepSeekModel.trim())
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)
                            ) {
                                if (isSaving) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                else Text("Save & Use DeepSeek", fontSize = 12.sp)
                            }
                        }
                    }

                    SetupTab.ANTHROPIC_KEY -> {
                        Text(
                            "Use your Anthropic Console API key directly without browser OAuth redirection.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )
                        OutlinedTextField(
                            value = anthropicKey,
                            onValueChange = { anthropicKey = it; validationMessage = null },
                            label = { Text("Anthropic API Key") },
                            placeholder = { Text("sk-ant-…") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = anthropicModel,
                            onValueChange = { anthropicModel = it },
                            label = { Text("Model ID") },
                            placeholder = { Text("claude-3-5-sonnet-latest") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                enabled = !isTesting && anthropicKey.isNotBlank(),
                                onClick = {
                                    isTesting = true
                                    validationMessage = null
                                    coroutineScope.launch {
                                        val res = ClaudeProviderValidator.validate("https://api.anthropic.com", anthropicKey)
                                        isTesting = false
                                        validationSuccess = res.isSuccess
                                        validationMessage = res.getOrElse { it.message ?: "Validation failed" }
                                    }
                                }
                            ) {
                                if (isTesting) {
                                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text("Test Key", fontSize = 12.sp)
                            }

                            Button(
                                enabled = !isSaving && anthropicKey.isNotBlank() && anthropicModel.isNotBlank(),
                                onClick = {
                                    val provider = ClaudeProvider(
                                        name = "Anthropic API",
                                        base_url = "https://api.anthropic.com",
                                        api_key = anthropicKey.trim(),
                                        models_url = "https://api.anthropic.com/v1/models",
                                        models = listOf(
                                            ClaudeProviderModel("claude-3-7-sonnet-latest", "Claude 3.7 Sonnet"),
                                            ClaudeProviderModel("claude-3-5-sonnet-latest", "Claude 3.5 Sonnet"),
                                            ClaudeProviderModel("claude-3-5-haiku-latest", "Claude 3.5 Haiku"),
                                            ClaudeProviderModel("claude-3-opus-latest", "Claude 3 Opus")
                                        )
                                    )
                                    saveAndActivate(provider, anthropicModel.trim())
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)
                            ) {
                                if (isSaving) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                else Text("Save & Use Anthropic Key", fontSize = 12.sp)
                            }
                        }
                    }

                    SetupTab.CUSTOM -> {
                        Text(
                            "Enter any Anthropic-compatible reverse proxy or gateway endpoint.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )
                        OutlinedTextField(
                            value = customName,
                            onValueChange = { customName = it },
                            label = { Text("Provider Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = customBaseUrl,
                            onValueChange = { customBaseUrl = it; validationMessage = null },
                            label = { Text("Base URL") },
                            placeholder = { Text("https://api.example.com/anthropic") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = customKey,
                            onValueChange = { customKey = it; validationMessage = null },
                            label = { Text("API Key") },
                            placeholder = { Text("sk-…") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = customModel,
                            onValueChange = { customModel = it },
                            label = { Text("Model ID") },
                            placeholder = { Text("e.g. moonshotai/kimi-k2") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                enabled = !isTesting && customBaseUrl.isNotBlank() && customKey.isNotBlank(),
                                onClick = {
                                    isTesting = true
                                    validationMessage = null
                                    coroutineScope.launch {
                                        val res = ClaudeProviderValidator.validate(customBaseUrl, customKey)
                                        isTesting = false
                                        validationSuccess = res.isSuccess
                                        validationMessage = res.getOrElse { it.message ?: "Validation failed" }
                                    }
                                }
                            ) {
                                if (isTesting) {
                                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text("Test Endpoint", fontSize = 12.sp)
                            }

                            Button(
                                enabled = !isSaving && customName.isNotBlank() && customBaseUrl.isNotBlank() && customKey.isNotBlank() && customModel.isNotBlank(),
                                onClick = {
                                    val provider = ClaudeProvider(
                                        name = customName.trim(),
                                        base_url = customBaseUrl.trim(),
                                        api_key = customKey.trim(),
                                        models = listOf(ClaudeProviderModel(customModel.trim()))
                                    )
                                    saveAndActivate(provider, customModel.trim())
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)
                            ) {
                                if (isSaving) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                else Text("Save & Use Custom", fontSize = 12.sp)
                            }
                        }
                    }
                }

                // Feedback message displays
                validationMessage?.let { msg ->
                    Text(
                        text = (if (validationSuccess) "✓ " else "✗ ") + msg,
                        fontSize = 12.sp,
                        color = if (validationSuccess) QuotaGreen else QuotaRed,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                errorMessage?.let { msg ->
                    Text(
                        text = "Error: $msg",
                        fontSize = 12.sp,
                        color = QuotaRed,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = {
                if (login != null) account.cancelLogin()
                onDismiss()
            }) { Text(if (login != null) "Cancel sign-in" else "Close") }
        }
    )
}
