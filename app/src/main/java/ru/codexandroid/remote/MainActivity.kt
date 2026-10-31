package ru.codexandroid.remote

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

data class CodexThread(val id: String, val title: String, val preview: String)
data class ChatMessage(val id: String, val role: String, val text: String)

data class AppState(
    val host: String = "",
    val port: String = "8765",
    val token: String = "",
    val connected: Boolean = false,
    val loading: Boolean = false,
    val sending: Boolean = false,
    val status: String = "Введите адрес bridge",
    val threads: List<CodexThread> = emptyList(),
    val selected: CodexThread? = null,
    val messages: List<ChatMessage> = emptyList(),
)

class BridgeClient(host: String, port: Int, token: String) : Closeable {
    private val socket = Socket()
    private val reader: BufferedReader
    private val writer: BufferedWriter
    private var nextId = 1

    init {
        socket.connect(InetSocketAddress(host, port), 15_000)
        reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        writer.write("codex-bridge $token\n")
        writer.flush()
        val hello = JSONObject(reader.readLine() ?: error("Bridge closed during authentication"))
        check(hello.optBoolean("ok")) { hello.optString("error", "Authentication failed") }
        request(
            "initialize",
            JSONObject().put(
                "clientInfo",
                JSONObject().put("name", "codex-android").put("version", "0.2.0"),
            ),
        )
    }

    private fun request(method: String, params: JSONObject): Response {
        val id = nextId++
        writer.write(JSONObject().put("id", id).put("method", method).put("params", params).toString())
        writer.write("\n")
        writer.flush()
        val notifications = mutableListOf<JSONObject>()
        while (true) {
            val message = JSONObject(reader.readLine() ?: error("Bridge closed while waiting for $method"))
            if (message.optInt("id", -1) != id) {
                notifications += message
                continue
            }
            if (message.has("error")) error(message.getJSONObject("error").toString())
            return Response(message.getJSONObject("result"), notifications)
        }
    }

    fun listThreads(): List<CodexThread> {
        val data = request(
            "thread/list",
            JSONObject().put("archived", false).put("limit", 100).put("useStateDbOnly", true),
        ).result.optJSONArray("data") ?: JSONArray()
        return (0 until data.length()).map { threadFromJson(data.getJSONObject(it)) }
    }

    fun readThread(id: String): List<ChatMessage> {
        // Do not hydrate an entire rollout in one response. Large or active
        // threads can contain huge tool outputs and may close the socket.
        request("thread/read", JSONObject().put("threadId", id).put("includeTurns", false))
        val turns = request(
            "thread/turns/list",
            JSONObject()
                .put("threadId", id)
                .put("limit", 50)
                .put("sortDirection", "asc")
                .put("itemsView", "full"),
        ).result.optJSONArray("data") ?: JSONArray()
        return extractTurnMessages(turns)
    }

    fun resumeThread(id: String) {
        request("thread/resume", JSONObject().put("threadId", id).put("excludeTurns", true))
    }

    fun startTurn(id: String, text: String, onDelta: (String) -> Unit): String {
        val response = request(
            "turn/start",
            JSONObject()
                .put("threadId", id)
                .put("input", JSONArray().put(JSONObject().put("type", "text").put("text", text))),
        )
        val turnId = response.result.optJSONObject("turn")?.optString("id").orEmpty()
        response.notifications.forEach { event ->
            if (event.optString("method") == "item/agentMessage/delta") {
                onDelta(event.optJSONObject("params")?.optString("delta").orEmpty())
            }
        }
        while (true) {
            val event = JSONObject(reader.readLine() ?: error("Bridge closed during turn"))
            when (event.optString("method")) {
                "item/agentMessage/delta" -> onDelta(event.optJSONObject("params")?.optString("delta").orEmpty())
                "turn/completed" -> {
                    val turn = event.optJSONObject("params")?.optJSONObject("turn")
                    check(turn?.optString("status") == "completed") {
                        turn?.optJSONObject("error")?.toString() ?: "Turn failed"
                    }
                    return turnId
                }
            }
        }
    }

    override fun close() {
        socket.close()
    }

    private data class Response(val result: JSONObject, val notifications: List<JSONObject>)
}

private fun threadFromJson(item: JSONObject): CodexThread {
    val name = item.optString("name")
    val preview = item.optString("preview")
    return CodexThread(
        item.optString("id"),
        name.takeIf { it.isNotBlank() && it != "null" }
            ?: preview.lineSequence().firstOrNull().orEmpty().ifBlank { "Без названия" },
        preview,
    )
}

private fun extractMessages(value: Any?): List<ChatMessage> {
    val result = mutableListOf<ChatMessage>()
    fun walk(node: Any?) {
        when (node) {
            is JSONObject -> {
                val type = node.optString("type")
                val role = when {
                    type.contains("user", true) -> "user"
                    type.contains("agent", true) || type.contains("assistant", true) -> "assistant"
                    else -> ""
                }
                val text = node.optString("text").ifBlank { node.optString("content").orEmpty() }
                if (role.isNotBlank() && text.isNotBlank()) {
                    result += ChatMessage(UUID.randomUUID().toString(), role, text)
                }
                node.keys().forEach { key -> walk(node.opt(key)) }
            }
            is JSONArray -> for (index in 0 until node.length()) walk(node.opt(index))
        }
    }
    walk(value)
    return result.distinctBy { "${it.role}:${it.text}" }
}

private fun extractTurnMessages(turns: JSONArray): List<ChatMessage> {
    val result = mutableListOf<ChatMessage>()
    for (index in 0 until turns.length()) {
        val turn = turns.optJSONObject(index) ?: continue
        val items = turn.optJSONArray("items") ?: continue
        for (itemIndex in 0 until items.length()) {
            val item = items.optJSONObject(itemIndex) ?: continue
            when (item.optString("type")) {
                "userMessage" -> {
                    val content = item.optJSONArray("content") ?: continue
                    val text = (0 until content.length()).joinToString("") { content.optJSONObject(it)?.optString("text").orEmpty() }
                    if (text.isNotBlank()) result += ChatMessage(UUID.randomUUID().toString(), "user", text)
                }
                "agentMessage" -> {
                    val text = item.optString("text")
                    if (text.isNotBlank()) result += ChatMessage(UUID.randomUUID().toString(), "assistant", text)
                }
            }
        }
    }
    return result
}

class CodexViewModel(private val context: Context) : ViewModel() {
    private val preferences = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        AppState(
            host = preferences.getString("host", "100.114.88.98").orEmpty(),
            port = preferences.getString("port", "8765").orEmpty(),
            token = preferences.getString("token", "").orEmpty(),
        ),
    )
    val state: StateFlow<AppState> = _state.asStateFlow()
    private var client: BridgeClient? = null

    fun updateSettings(host: String, port: String, token: String) {
        _state.value = _state.value.copy(host = host, port = port, token = token)
        preferences.edit().putString("host", host).putString("port", port).putString("token", token).apply()
    }

    fun connect() {
        val current = _state.value
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = current.copy(loading = true, status = "Подключение…")
            try {
                client?.close()
                client = BridgeClient(current.host.trim(), current.port.toInt(), current.token.trim())
                val threads = client!!.listThreads()
                _state.value = _state.value.copy(connected = true, loading = false, threads = threads, status = "Подключено")
            } catch (error: Exception) {
                client?.close()
                client = null
                _state.value = _state.value.copy(connected = false, loading = false, status = "Ошибка: ${error.message}")
            }
        }
    }

    fun openThread(thread: CodexThread) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val messages = client?.readThread(thread.id).orEmpty()
                _state.value = _state.value.copy(selected = thread, messages = messages, status = "Открыта сессия")
            } catch (error: Exception) {
                _state.value = _state.value.copy(status = "Не удалось открыть: ${error.message}")
            }
        }
    }

    fun closeThread() {
        _state.value = _state.value.copy(selected = null, messages = emptyList())
    }

    fun send(text: String) {
        val thread = _state.value.selected ?: return
        if (text.isBlank() || _state.value.sending) return
        viewModelScope.launch(Dispatchers.IO) {
            val user = ChatMessage(UUID.randomUUID().toString(), "user", text)
            val assistantId = UUID.randomUUID().toString()
            _state.value = _state.value.copy(
                sending = true,
                messages = _state.value.messages + user + ChatMessage(assistantId, "assistant", ""),
                status = "Модель отвечает…",
            )
            try {
                val currentClient = client ?: error("Нет подключения к bridge")
                currentClient.resumeThread(thread.id)
                currentClient.startTurn(thread.id, text) { delta ->
                    _state.value = _state.value.copy(
                        messages = _state.value.messages.map { message ->
                            if (message.id == assistantId) message.copy(text = message.text + delta) else message
                        },
                    )
                }
                _state.value = _state.value.copy(sending = false, status = "Готово")
            } catch (error: Exception) {
                _state.value = _state.value.copy(sending = false, status = "Ошибка ответа: ${error.message}")
            }
        }
    }

    override fun onCleared() {
        client?.close()
        super.onCleared()
    }
}

class CodexViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return CodexViewModel(context.applicationContext) as T
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CodexApp() }
    }
}

@Composable
private fun CodexApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val viewModel: CodexViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = CodexViewModelFactory(context),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    var host by remember(state.host) { mutableStateOf(state.host) }
    var port by remember(state.port) { mutableStateOf(state.port) }
    var token by remember(state.token) { mutableStateOf(state.token) }
    var draft by remember { mutableStateOf("") }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            if (state.selected == null) {
                ConnectionScreen(
                    state = state,
                    host = host,
                    port = port,
                    token = token,
                    onHost = { host = it },
                    onPort = { port = it },
                    onToken = { token = it },
                    onConnect = {
                        viewModel.updateSettings(host, port, token)
                        viewModel.connect()
                    },
                    onOpen = { thread -> viewModel.openThread(thread) },
                )
            } else {
                ChatScreen(
                    thread = state.selected!!,
                    messages = state.messages,
                    draft = draft,
                    sending = state.sending,
                    status = state.status,
                    onDraftChange = { draft = it },
                    onBack = viewModel::closeThread,
                    onSend = { viewModel.send(draft); draft = "" },
                )
            }
        }
    }
}

@Composable
private fun ConnectionScreen(
    state: AppState,
    host: String,
    port: String,
    token: String,
    onHost: (String) -> Unit,
    onPort: (String) -> Unit,
    onToken: (String) -> Unit,
    onConnect: () -> Unit,
    onOpen: (CodexThread) -> Unit,
) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Codex Android Remote", style = MaterialTheme.typography.headlineSmall)
            Text(state.status, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(host, onHost, label = { Text("Tailscale IP ПК") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(port, onPort, label = { Text("Порт") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                OutlinedTextField(token, onToken, label = { Text("Bridge token") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(2f))
            }
            Button(onClick = onConnect, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                if (state.loading) CircularProgressIndicator() else Text("Подключиться и загрузить сессии")
            }
            if (state.connected) {
                Text("История Codex", style = MaterialTheme.typography.titleLarge)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.threads, key = { it.id }) { thread ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text(thread.title, style = MaterialTheme.typography.titleMedium)
                                Text(thread.preview, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { onOpen(thread) }) { Text("Открыть") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ChatScreen(
    thread: CodexThread,
    messages: List<ChatMessage>,
    draft: String,
    sending: Boolean,
    status: String,
    onDraftChange: (String) -> Unit,
    onBack: () -> Unit,
    onSend: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(thread.title, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Text("‹") } },
            )
        },
        bottomBar = {
            Column(Modifier.padding(12.dp)) {
                Text(status, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(draft, onDraftChange, label = { Text("Сообщение") }, modifier = Modifier.weight(1f))
                    Button(onClick = onSend, enabled = draft.isNotBlank() && !sending) { Text("Отправить") }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages, key = { it.id }) { message ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(if (message.role == "user") "Вы" else "Codex", style = MaterialTheme.typography.labelLarge)
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        Text(message.text.ifBlank { "…" })
                    }
                }
            }
        }
    }
}
