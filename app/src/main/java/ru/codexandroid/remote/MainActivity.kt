package ru.codexandroid.remote

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.content.FileProvider
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import kotlinx.coroutines.delay
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
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

data class CodexThread(val id: String, val title: String, val preview: String)
data class ChatMessage(val id: String, val role: String, val text: String)
data class PendingAttachment(val uri: Uri, val name: String, val mimeType: String? = null)
data class ModelOption(val id: String, val name: String, val efforts: List<String>)
enum class AccessOption(val label: String, val sandboxType: String?) {
    INHERIT("Как в сессии", null),
    READ_ONLY("Только чтение", "readOnly"),
    WORKSPACE("Запись в проект", "workspaceWrite"),
    FULL("Полный доступ", "dangerFullAccess"),
}

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
    val attachments: List<PendingAttachment> = emptyList(),
    val models: List<ModelOption> = emptyList(),
    val model: String? = null,
    val effort: String? = null,
    val access: AccessOption = AccessOption.INHERIT,
    val modelWarning: String? = null,
    val uploadProgress: Float? = null,
    val draft: String = "",
    val autoConnect: Boolean = false,
)

class BridgeClient(host: String, port: Int, token: String) : Closeable {
    private val socket = Socket()
    private val reader: BufferedReader
    private val writer: BufferedWriter
    private var nextId = 1
    private val resumedThreads = mutableSetOf<String>()
    val isOpen: Boolean get() = !socket.isClosed

    init {
        try {
            socket.connect(InetSocketAddress(host, port), 15_000)
            socket.soTimeout = 15_000
            reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            writer.write("codex-bridge $token\n")
            writer.flush()
            val hello = JSONObject(reader.readLine() ?: throw IOException("Bridge closed during authentication"))
            check(hello.optBoolean("ok")) { hello.optString("error", "Authentication failed") }
            request(
                "initialize",
                JSONObject().put(
                    "clientInfo",
                    JSONObject().put("name", "codex-android").put("version", "0.2.0"),
                ),
            )
            socket.soTimeout = 0
        } catch (error: Exception) {
            socket.close()
            throw error
        }
    }

    private fun request(method: String, params: JSONObject): Response {
        val id = nextId++
        writer.write(JSONObject().put("id", id).put("method", method).put("params", params).toString())
        writer.write("\n")
        writer.flush()
        val notifications = mutableListOf<JSONObject>()
        while (true) {
            val message = JSONObject(reader.readLine() ?: throw IOException("Bridge closed while waiting for $method"))
            if (message.has("method") && message.has("id")) {
                close()
                error("Запрос подтверждения пока не поддерживается приложением; соединение закрыто.")
            }
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

    fun startThread(cwd: String?): CodexThread {
        val params = JSONObject().put("ephemeral", false)
        cwd?.takeIf { it.isNotBlank() }?.let { params.put("cwd", it) }
        val result = request("thread/start", params).result
        val thread = result.optJSONObject("thread") ?: result
        val created = threadFromJson(thread)
        if (created.id.isNotBlank()) resumedThreads.add(created.id)
        return created
    }

    fun listModels(): List<ModelOption> {
        val models = mutableListOf<ModelOption>()
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        do {
            val params = JSONObject().put("limit", 100)
            cursor?.let { params.put("cursor", it) }
            val result = request("model/list", params).result
            val data = result.optJSONArray("data") ?: JSONArray()
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val id = item.optString("model").takeIf { it.isNotBlank() && it != "null" } ?: continue
                val supported = item.optJSONArray("supportedReasoningEfforts") ?: JSONArray()
                val efforts = (0 until supported.length()).mapNotNull {
                    supported.optJSONObject(it)?.optString("reasoningEffort")?.takeIf { e -> e.isNotBlank() }
                }
                models.add(ModelOption(id, item.optString("displayName", id), efforts))
            }
            cursor = if (result.isNull("nextCursor")) null else result.optString("nextCursor").takeIf { it.isNotBlank() }
            check(cursor == null || seen.add(cursor)) { "Повтор курсора списка моделей" }
        } while (cursor != null)
        return models.distinctBy { it.id }
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
        if (resumedThreads.add(id)) {
            try {
                request("thread/resume", JSONObject().put("threadId", id).put("excludeTurns", true))
            } catch (error: Exception) {
                resumedThreads.remove(id)
                throw error
            }
        }
    }

    fun upload(
        threadId: String,
        model: String?,
        name: String,
        mimeType: String?,
        size: Long,
        stream: java.io.InputStream,
        progress: (Long, Long) -> Unit,
    ): String {
        require(size in 1..(1024L * 1024 * 1024)) { "Размер файла должен быть от 1 байта до 1 ГиБ" }
        val params = JSONObject().put("threadId", threadId).put("fileName", name).put("size", size)
        model?.let { params.put("model", it) }
        mimeType?.let { params.put("mimeType", it) }
        val start = request("bridge/upload/start", params).result
        val uploadId = start.getString("uploadId")
        try {
            val buffer = ByteArray(start.optInt("chunkSize", 262144).coerceIn(1, 262144))
            var total = 0L
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                require(total <= size) { "Размер файла изменился" }
                request("bridge/upload/chunk", JSONObject().put("uploadId", uploadId)
                    .put("dataBase64", Base64.encodeToString(buffer, 0, count, Base64.NO_WRAP)))
                progress(total, size)
            }
            require(total == size) { "Файл прочитан не полностью" }
            return request("bridge/upload/finish", JSONObject().put("uploadId", uploadId)).result.getString("path")
        } catch (error: Exception) {
            try { request("bridge/upload/cancel", JSONObject().put("uploadId", uploadId)) } catch (_: Exception) {}
            throw error
        }
    }

    fun startTurn(
        id: String,
        text: String,
        images: List<Pair<String, String>>,
        documents: List<Pair<String, String>>,
        model: String?,
        effort: String?,
        access: AccessOption,
        onAccepted: () -> Unit,
        onDelta: (String) -> Unit,
    ): String {
        val input = JSONArray()
        val message = if (documents.isEmpty()) text else text + "\n\nПрикреплённые файлы:\n" +
            documents.joinToString("\n") { (name, path) -> "- $name\n  Путь: $path" }
        input.put(JSONObject().put("type", "text").put("text", message))
        images.forEach { (_, path) -> input.put(JSONObject().put("type", "localImage").put("path", path)) }
        val params = JSONObject()
            .put("threadId", id)
            .put("input", input)
        model?.let { params.put("model", it) }
        effort?.let { params.put("effort", it) }
        access.sandboxType?.let { params.put("sandboxPolicy", JSONObject().put("type", it)) }
        val response = request(
            "turn/start",
            params,
        )
        val turnId = response.result.optJSONObject("turn")?.optString("id").orEmpty()
        onAccepted()
        response.notifications.forEach { event ->
            if (event.has("id") && event.has("method")) {
                close()
                error("Codex запросил подтверждение, которое приложение пока не поддерживает. Соединение закрыто.")
            }
            if (event.optString("method") == "item/agentMessage/delta") {
                onDelta(event.optJSONObject("params")?.optString("delta").orEmpty())
            }
        }
        while (true) {
            val event = JSONObject(reader.readLine() ?: throw IOException("Bridge closed during turn"))
            if (event.has("id") && event.has("method")) {
                close()
                error("Codex запросил подтверждение, которое приложение пока не поддерживает. Соединение закрыто.")
            }
            when (event.optString("method")) {
                "item/agentMessage/delta" -> onDelta(event.optJSONObject("params")?.optString("delta").orEmpty())
                "turn/completed" -> {
                    val turn = event.optJSONObject("params")?.optJSONObject("turn")
                    if (turn?.optString("id") != turnId) continue
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
                    val text = (0 until content.length()).mapNotNull { contentIndex ->
                        val part = content.optJSONObject(contentIndex) ?: return@mapNotNull null
                        when (part.optString("type")) {
                            "text" -> part.optString("text")
                            "localImage" -> "📎 Изображение: " + part.optString("path").substringAfterLast('\\').substringAfterLast('/')
                            "image" -> "📎 Изображение"
                            else -> null
                        }
                    }.joinToString("\n")
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
            model = preferences.getString("selectedModel", null),
            effort = preferences.getString("selectedEffort", null),
            autoConnect = preferences.getBoolean("autoConnect", false),
            threads = loadCachedThreads(),
            status = "Нет подключения. Показаны сохранённые сессии.",
        ),
    )
    val state: StateFlow<AppState> = _state.asStateFlow()
    private var client: BridgeClient? = null

    init {
        if (_state.value.autoConnect && _state.value.host.isNotBlank() && _state.value.token.isNotBlank()) {
            connect()
        }
    }

    fun setAutoConnect(enabled: Boolean) {
        preferences.edit().putBoolean("autoConnect", enabled).apply()
        _state.value = _state.value.copy(autoConnect = enabled)
    }

    private fun loadCachedThreads(): List<CodexThread> = try {
        val data = JSONArray(preferences.getString("threads", "[]"))
        (0 until data.length()).map { threadFromJson(data.getJSONObject(it)) }
    } catch (_: Exception) { emptyList() }

    private fun saveThreads(threads: List<CodexThread>) {
        val data = JSONArray()
        threads.forEach { data.put(JSONObject().put("id", it.id).put("name", it.title).put("preview", it.preview)) }
        preferences.edit().putString("threads", data.toString()).apply()
    }

    private fun saveMessages(threadId: String, messages: List<ChatMessage>) {
        val data = JSONArray()
        messages.forEach { data.put(JSONObject().put("id", it.id).put("role", it.role).put("text", it.text)) }
        preferences.edit().putString("history:$threadId", data.toString()).apply()
    }

    private fun loadMessages(threadId: String): List<ChatMessage> = try {
        val data = JSONArray(preferences.getString("history:$threadId", "[]"))
        (0 until data.length()).map {
            val item = data.getJSONObject(it)
            ChatMessage(item.getString("id"), item.getString("role"), item.getString("text"))
        }
    } catch (_: Exception) { emptyList() }

    fun updateSettings(host: String, port: String, token: String) {
        _state.value = _state.value.copy(host = host, port = port, token = token)
        preferences.edit().putString("host", host).putString("port", port).putString("token", token).apply()
    }

    fun createNewThread(workspace: String) {
        if (!_state.value.connected || _state.value.loading || _state.value.sending) return
        _state.value = _state.value.copy(loading = true, status = "Создание нового диалога…")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val created = (client ?: error("Нет подключения к bridge")).startThread(workspace.trim().ifBlank { null })
                require(created.id.isNotBlank()) { "App Server не вернул идентификатор диалога" }
                preferences.edit().putString("workspace", workspace.trim()).apply()
                val updatedThreads = listOf(created) + _state.value.threads.filterNot { it.id == created.id }
                saveThreads(updatedThreads)
                _state.value = _state.value.copy(
                    connected = true,
                    loading = false,
                    threads = updatedThreads,
                    selected = created,
                    messages = emptyList(),
                    draft = "",
                    status = "Новый диалог создан",
                )
            } catch (error: Exception) {
                if (error is IOException || client?.isOpen == false) {
                    client?.close()
                    client = null
                    _state.value = _state.value.copy(connected = false)
                }
                _state.value = _state.value.copy(
                    loading = false,
                    status = "Не удалось подтвердить создание диалога: ${error.message}. Проверьте историю перед повтором.",
                )
            }
        }
    }

    fun lastWorkspace(): String = preferences.getString("workspace", "").orEmpty()

    fun draftFor(threadId: String): String =
        preferences.getString("draft:$threadId", "").orEmpty()

    fun saveDraft(threadId: String, draft: String) {
        preferences.edit().putString("draft:$threadId", draft).apply()
        if (_state.value.selected?.id == threadId) _state.value = _state.value.copy(draft = draft)
    }

    private fun saveChatOptions(model: String?, effort: String?) {
        preferences.edit()
            .putString("selectedModel", model)
            .putString("selectedEffort", effort)
            .apply()
    }

    fun connect() {
        if (_state.value.loading || _state.value.sending) return
        val current = _state.value
        _state.value = current.copy(loading = true, connected = false, status = "Подключение…")
        viewModelScope.launch(Dispatchers.IO) {
            var lastError: Exception? = null
            for (attempt in 1..3) {
              try {
                client?.close()
                client = BridgeClient(current.host.trim(), current.port.toInt(), current.token.trim())
                val threads = client!!.listThreads()
                saveThreads(threads)
                var warning: String? = null
                val models = try {
                    client!!.listModels()
                } catch (error: Exception) {
                    if (client?.isOpen != true || error is IOException) throw error
                    warning = "Не удалось загрузить модели: ${error.message}. Доступны настройки сессии."
                    emptyList()
                }
                _state.value = _state.value.copy(connected = true, loading = false, threads = threads, models = models, modelWarning = warning, status = "Подключено")
                return@launch
              } catch (error: Exception) {
                lastError = error
                client?.close()
                client = null
                // Invalid credentials/configuration are not transient network errors.
                if (error !is IOException || attempt == 3) break
                delay(500L * attempt)
              }
            }
            _state.value = _state.value.copy(connected = false, loading = false, status = "Не удалось подключиться: ${lastError?.message}")
        }
    }

    fun openThread(thread: CodexThread) {
        if (_state.value.loading || _state.value.sending) return
        _state.value = _state.value.copy(loading = true)
        if (!_state.value.connected) {
            _state.value = _state.value.copy(
                selected = thread,
                draft = draftFor(thread.id),
                messages = loadMessages(thread.id),
                status = "Нет подключения. Показана сохранённая история.",
                loading = false,
            )
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val messages = (client ?: error("Нет подключения к bridge")).readThread(thread.id)
                saveMessages(thread.id, messages)
                _state.value = _state.value.copy(selected = thread, draft = draftFor(thread.id), messages = messages, loading = false, status = "Открыта сессия")
            } catch (error: Exception) {
                if (error is IOException) {
                    client?.close()
                    client = null
                    _state.value = _state.value.copy(connected = false)
                }
                _state.value = _state.value.copy(
                    selected = thread,
                    draft = draftFor(thread.id),
                    messages = loadMessages(thread.id),
                    loading = false,
                    status = "Не удалось обновить историю: ${error.message}. Показан кэш.",
                )
            }
        }
    }

    fun closeThread() {
        if (_state.value.sending) return
        _state.value = _state.value.copy(selected = null, messages = emptyList(), attachments = emptyList())
    }

    fun addAttachment(attachment: PendingAttachment) {
        _state.value = _state.value.copy(attachments = _state.value.attachments + attachment)
    }

    fun removeAttachment(attachment: PendingAttachment) {
        _state.value = _state.value.copy(attachments = _state.value.attachments - attachment)
    }

    fun selectModel(model: String?) {
        _state.value = _state.value.copy(model = model, effort = null)
        saveChatOptions(model, null)
    }

    fun selectEffort(effort: String?) {
        _state.value = _state.value.copy(effort = effort)
        saveChatOptions(_state.value.model, effort)
    }

    fun selectAccess(access: AccessOption) {
        _state.value = _state.value.copy(access = access)
    }

    fun send(text: String) {
        val thread = _state.value.selected ?: return
        if ((text.isBlank() && _state.value.attachments.isEmpty()) || _state.value.sending || _state.value.loading || !_state.value.connected) return
        _state.value = _state.value.copy(sending = true)
        viewModelScope.launch(Dispatchers.IO) {
            val attachmentLabel = _state.value.attachments.joinToString("\n") { "📎 ${it.name}" }
            val displayText = listOf(attachmentLabel, text).filter { it.isNotBlank() }.joinToString("\n")
            val user = ChatMessage(UUID.randomUUID().toString(), "user", "$displayText\n⏳ Отправка")
            val assistantId = UUID.randomUUID().toString()
            var stage = "открытие сессии"
            var turnStarted = false
            _state.value = _state.value.copy(
                sending = true,
                status = "Открытие сессии…",
            )
            try {
                val currentClient = client ?: error("Нет подключения к bridge")
                currentClient.resumeThread(thread.id)
                val options = _state.value
                val images = mutableListOf<Pair<String, String>>()
                val documents = mutableListOf<Pair<String, String>>()
                for (attachment in options.attachments) {
                    stage = "загрузка файла ${attachment.name}"
                    val resolver = context.contentResolver
                    val size = resolver.query(attachment.uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                        if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
                    } ?: -1L
                    require(size > 0) { "Не удалось узнать размер ${attachment.name}" }
                    _state.value = _state.value.copy(uploadProgress = 0f, status = "Загрузка: ${attachment.name}")
                    val path = (resolver.openInputStream(attachment.uri) ?: error("Не удалось открыть файл")).use { stream ->
                        currentClient.upload(thread.id, options.model, attachment.name, attachment.mimeType, size, stream) { sent, total ->
                            _state.value = _state.value.copy(uploadProgress = sent.toFloat() / total.toFloat())
                        }
                    }
                    if (attachment.mimeType?.startsWith("image/") == true || resolver.getType(attachment.uri)?.startsWith("image/") == true || attachment.name == "Фото с камеры") {
                        images.add(attachment.name to path)
                    } else {
                        documents.add(attachment.name to path)
                    }
                }
                stage = "отправка сообщения"
                _state.value = _state.value.copy(
                    uploadProgress = null,
                    attachments = emptyList(),
                    messages = _state.value.messages + user + ChatMessage(assistantId, "assistant", ""),
                    status = "Модель отвечает…",
                )
                turnStarted = true
                currentClient.startTurn(thread.id, text, images, documents, options.model, options.effort, options.access,
                    onAccepted = {
                        // Do not erase a newer draft typed while this turn was starting.
                        if (draftFor(thread.id) == text) saveDraft(thread.id, "")
                        _state.value = _state.value.copy(messages = _state.value.messages.map {
                            if (it.id == user.id) {
                                it.copy(text = if (attachmentLabel.isNotBlank()) {
                                    "$displayText\n✓ Вложение передано, запрос принят Codex"
                                } else {
                                    displayText
                                })
                            } else it
                        })
                        saveMessages(thread.id, _state.value.messages)
                    }) { delta ->
                    _state.value = _state.value.copy(
                        messages = _state.value.messages.map { message ->
                            if (message.id == assistantId) message.copy(text = message.text + delta) else message
                        },
                    )
                }
                saveMessages(thread.id, _state.value.messages)
                _state.value = _state.value.copy(sending = false, status = "Готово")
            } catch (error: Exception) {
                val connectionError = error is IOException ||
                    error.message?.contains("соединение закрыто", ignoreCase = true) == true ||
                    error.message?.contains("connection reset", ignoreCase = true) == true
                if (connectionError) {
                    client?.close()
                    client = null
                    _state.value = _state.value.copy(connected = false)
                }
                val advice = if (turnStarted) "Проверьте историю перед повтором." else "Сообщение ещё не отправлено."
                _state.value = _state.value.copy(
                    sending = false,
                    uploadProgress = null,
                    messages = _state.value.messages.map {
                        if (it.id == user.id && it.text.endsWith("⏳ Отправка")) it.copy(text = "$displayText\n⚠ Статус отправки неизвестен") else it
                    },
                    status = "Ошибка: $stage: ${error.message}. $advice",
                )
                if (connectionError) {
                    _state.value = _state.value.copy(status = "Связь оборвалась. Переподключение…")
                    delay(500)
                    connect()
                }
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
    var showHome by remember { mutableStateOf(true) }
    LaunchedEffect(state.selected?.id) {
        if (state.selected != null) showHome = false
    }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = cameraUri
        if (success && uri != null) viewModel.addAttachment(PendingAttachment(uri, "camera.jpg", "image/jpeg"))
        cameraUri = null
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val file = File(context.cacheDir, "camera").apply { mkdirs() }
            val photo = File.createTempFile("codex-", ".jpg", file)
            val target = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photo)
            cameraUri = target
            camera.launch(target)
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val resolver = context.contentResolver
            val displayName = try {
                resolver.query(it, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
                }
            } catch (_: Exception) {
                null
            }
            viewModel.addAttachment(
                PendingAttachment(
                    it,
                    displayName?.takeIf(String::isNotBlank) ?: "attachment",
                    resolver.getType(it),
                ),
            )
        }
    }

    val colors = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color.White, onPrimary = Color.Black,
        surface = Color(0xFF212121), background = Color(0xFF212121),
        surfaceVariant = Color(0xFF303030), onSurfaceVariant = Color(0xFFB4B4B4),
    ) else lightColorScheme(
        primary = Color(0xFF171717), onPrimary = Color.White,
        surface = Color.White, background = Color.White,
        surfaceVariant = Color(0xFFF4F4F4), onSurfaceVariant = Color(0xFF686868),
    )
    MaterialTheme(colorScheme = colors) {
        BackHandler(enabled = !showHome && state.selected != null) {
            showHome = true
        }
        Surface(
            Modifier.fillMaxSize().border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(12.dp),
            ),
        ) {
            if (state.selected == null || showHome) {
                ConnectionScreen(
                    state = state,
                    host = host,
                    port = port,
                    token = token,
                    onHost = { host = it },
                    onPort = { port = it },
                    onToken = { token = it },
                    onAutoConnect = viewModel::setAutoConnect,
                    onConnect = {
                        viewModel.updateSettings(host, port, token)
                        viewModel.connect()
                    },
                    workspace = viewModel.lastWorkspace(),
                    onNewThread = viewModel::createNewThread,
                    onOpen = { thread ->
                        if (state.selected?.id == thread.id) showHome = false
                        else viewModel.openThread(thread)
                    },
                )
            } else {
                ChatScreen(
                    thread = state.selected!!,
                    messages = state.messages,
                    draft = state.draft,
                    sending = state.sending,
                    uploadProgress = state.uploadProgress,
                    connected = state.connected,
                    loading = state.loading,
                    status = state.status,
                    models = state.models,
                    model = state.model,
                    effort = state.effort,
                    access = state.access,
                    attachments = state.attachments,
                    modelWarning = state.modelWarning,
                    onModel = viewModel::selectModel,
                    onEffort = viewModel::selectEffort,
                    onAccess = viewModel::selectAccess,
                    onPickFile = { pickFile.launch("*/*") },
                    onCamera = { cameraPermission.launch(android.Manifest.permission.CAMERA) },
                    onRemoveAttachment = viewModel::removeAttachment,
                    onDraftChange = { viewModel.saveDraft(state.selected!!.id, it) },
                    onBack = { showHome = true },
                    onReconnect = viewModel::connect,
                    onSend = { viewModel.send(state.draft) },
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ConnectionScreen(
    state: AppState,
    host: String,
    port: String,
    token: String,
    onHost: (String) -> Unit,
    onPort: (String) -> Unit,
    onToken: (String) -> Unit,
    onAutoConnect: (Boolean) -> Unit,
    onConnect: () -> Unit,
    workspace: String,
    onNewThread: (String) -> Unit,
    onOpen: (CodexThread) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    var settings by remember { mutableStateOf(false) }
    var showNewThread by remember { mutableStateOf(false) }
    var newWorkspace by remember { mutableStateOf(workspace) }
    if (showNewThread) {
        AlertDialog(
            onDismissRequest = { showNewThread = false },
            title = { Text("Новый диалог") },
            text = {
                Column {
                    Text("Рабочая папка на ПК. Если оставить пустой, используется папка App Server.")
                    OutlinedTextField(
                        value = newWorkspace,
                        onValueChange = { newWorkspace = it },
                        label = { Text("Рабочая папка на ПК") },
                        placeholder = { Text("D:\\Pyfiles\\Codex_android") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showNewThread = false; onNewThread(newWorkspace) }) { Text("Создать") }
            },
            dismissButton = {
                TextButton(onClick = { showNewThread = false }) { Text("Отмена") }
            },
        )
    }
    BackHandler(settings) { settings = false }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Codex", style = MaterialTheme.typography.titleLarge) },
                actions = { TextButton(onClick = { settings = !settings }) { Text("Настройки") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Ваши диалоги", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text(state.status, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Автоподключение", style = MaterialTheme.typography.bodyMedium)
                        Text("При открытии приложения", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = state.autoConnect, onCheckedChange = onAutoConnect)
                }
            }
            if (settings || !state.connected) {
                item {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(24.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Подключение к компьютеру", style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(host, onHost, label = { Text("Tailscale IP ПК") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(port, onPort, label = { Text("Порт") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(token, onToken, label = { Text("Bridge token") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                            Button(onClick = onConnect, enabled = !state.loading && !state.sending, modifier = Modifier.fillMaxWidth()) {
                                if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                else Text("Подключиться")
                            }
                        }
                    }
                }
            }
            item {
                TextField(
                    search, { search = it },
                    placeholder = { Text("Поиск по истории") },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Button(
                    onClick = { showNewThread = true },
                    enabled = state.connected && !state.loading && !state.sending,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Text("+  Новый диалог")
                }
            }
            if (state.connected && !settings) {
                item { TextButton(onClick = onConnect, enabled = !state.loading && !state.sending) { Text("Обновить историю") } }
            }
            val visible = state.threads.filter { it.title.contains(search, true) || it.preview.contains(search, true) }
            if (visible.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("С чего начнём?", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(12.dp))
                        Text("Подключитесь к ПК, чтобы открыть диалоги Codex.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                item { Text("История", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(visible, key = { it.id }) { thread ->
                    Column(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = !state.loading && (!state.sending || state.selected?.id == thread.id)) { onOpen(thread) }
                            .padding(vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(thread.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(thread.preview, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
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
    uploadProgress: Float?,
    connected: Boolean,
    loading: Boolean,
    status: String,
    models: List<ModelOption>,
    model: String?,
    effort: String?,
    access: AccessOption,
    attachments: List<PendingAttachment>,
    modelWarning: String?,
    onModel: (String?) -> Unit,
    onEffort: (String?) -> Unit,
    onAccess: (AccessOption) -> Unit,
    onPickFile: () -> Unit,
    onCamera: () -> Unit,
    onRemoveAttachment: (PendingAttachment) -> Unit,
    onDraftChange: (String) -> Unit,
    onBack: () -> Unit,
    onReconnect: () -> Unit,
    onSend: () -> Unit,
) {
    val listState = rememberLazyListState()
    var showOptions by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    var confirmFullAccess by remember { mutableStateOf(false) }
    BackHandler(showOptions || attachMenu) { showOptions = false; attachMenu = false }
    if (confirmFullAccess) {
        AlertDialog(
            onDismissRequest = { confirmFullAccess = false },
            title = { Text("Полный доступ к ПК") },
            text = { Text("Codex сможет выполнять команды и изменять файлы вне проекта. Подтверждения команд в приложении пока не поддерживаются. Продолжить?") },
            confirmButton = { TextButton(onClick = { onAccess(AccessOption.FULL); confirmFullAccess = false }) { Text("Разрешить") } },
            dismissButton = { TextButton(onClick = { confirmFullAccess = false }) { Text("Отмена") } },
        )
    }
    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Codex", style = MaterialTheme.typography.titleLarge)
                        Text(thread.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Text("‹", style = MaterialTheme.typography.headlineMedium) } },
                actions = {
                    TextButton(onClick = { showOptions = !showOptions }) { Text("•••") }
                },
            )
        },
        bottomBar = {
            Column(Modifier.imePadding().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(status, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = onReconnect, enabled = !sending && !loading) {
                        Text(if (loading) "Подключение…" else "Переподключить", style = MaterialTheme.typography.labelSmall)
                    }
                }
                uploadProgress?.let {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Загрузка: ${(it * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                }
                if (showOptions) {
                Surface(
                    modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp)),
                    color = if (isSystemInDarkTheme()) Color(0xFF303030) else Color(0xFFE9E9E9),
                    shape = RoundedCornerShape(18.dp),
                ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text("Параметры ответа", style = MaterialTheme.typography.titleSmall)
                modelWarning?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    ChoiceMenu(
                        label = "Модель: ${models.firstOrNull { it.id == model }?.name ?: model ?: "как в сессии"}",
                        options = listOf("Использовать модель сессии" to null) + models.map { it.name to it.id },
                        enabled = !sending,
                        onSelect = onModel,
                    )
                    val efforts = models.firstOrNull { it.id == model }?.efforts.orEmpty()
                    val effortOptions = listOf<String?>(null) + efforts
                    val effortIndex = effortOptions.indexOf(effort).coerceAtLeast(0)
                    Text("Уровень рассуждения: ${effort ?: "как в сессии"}", style = MaterialTheme.typography.bodySmall)
                    if (effortOptions.size > 1) {
                        Slider(
                            value = effortIndex.toFloat(),
                            onValueChange = { value -> onEffort(effortOptions[value.roundToInt().coerceIn(0, effortOptions.lastIndex)]) },
                            valueRange = 0f..effortOptions.lastIndex.toFloat(),
                            steps = (effortOptions.size - 2).coerceAtLeast(0),
                            enabled = !sending,
                        )
                    } else {
                        Text("Выберите модель, чтобы увидеть доступные уровни", style = MaterialTheme.typography.labelSmall)
                    }
                ChoiceMenu(
                    label = "Разрешения: ${access.label}",
                    options = AccessOption.entries.map { it.label to it },
                    enabled = !sending,
                    onSelect = { if (it == AccessOption.FULL) confirmFullAccess = true else onAccess(it) },
                )
                }
                }
                }
                attachments.forEach { attachment ->
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("📎 ${attachment.name}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onRemoveAttachment(attachment) }, enabled = !sending) { Text("Убрать") }
                    }
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(28.dp)),
                ) {
                    Column(Modifier.fillMaxWidth().padding(8.dp)) {
                        TextField(
                            draft, onDraftChange,
                            placeholder = { Text("Сообщение Codex") },
                            maxLines = 4,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box {
                                IconButton(onClick = { attachMenu = true }, enabled = !sending && connected) { Text("+", style = MaterialTheme.typography.headlineMedium) }
                                DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                                    DropdownMenuItem(text = { Text("Прикрепить файл · до 1 ГиБ") }, onClick = { attachMenu = false; onPickFile() })
                                    DropdownMenuItem(text = { Text("Сделать фото") }, onClick = { attachMenu = false; onCamera() })
                                }
                            }
                            TextButton(onClick = { showOptions = !showOptions }, modifier = Modifier.weight(1f)) {
                                Text(models.firstOrNull { it.id == model }?.name ?: model ?: "Модель сессии", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                            }
                            Button(
                                onClick = onSend,
                                enabled = (draft.isNotBlank() || attachments.isNotEmpty()) && !sending && !loading && connected,
                                shape = RoundedCornerShape(24.dp),
                            ) { Text(if (sending) "…" else "↑", style = MaterialTheme.typography.titleLarge) }
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(messages, key = { it.id }) { message ->
                val isUser = message.role == "user"
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
                    Surface(
                        color = if (isUser) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                        shape = RoundedCornerShape(24.dp),
                        modifier = if (isUser) Modifier.widthIn(max = 320.dp) else Modifier.fillMaxWidth(),
                    ) {
                        SelectionContainer {
                            MarkdownText(
                                message.text.ifBlank { "…" },
                                modifier = Modifier.padding(if (isUser) 16.dp else 4.dp),
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

/**
 * Lightweight Markdown rendering. Unsupported TeX is left visible rather than
 * silently removed; this is not a full mathematical typesetting engine.
 */
private fun renderSimpleMath(source: String): String {
    var text = source.trim()
    val fraction = Regex("""\\frac\s*\{([^{}]*)}\s*\{([^{}]*)}""")
    val squareRoot = Regex("""\\sqrt\s*\{([^{}]*)}""")
    repeat(8) {
        val next = text.replace(fraction) { "(${it.groupValues[1]})/(${it.groupValues[2]})" }
            .replace(squareRoot) { "√(${it.groupValues[1]})" }
        if (next == text) return@repeat
        text = next
    }
    return text.replace("\\times", "×")
        .replace("\\cdot", "·")
        .replace("\\div", "÷")
        .replace("\\leq", "≤")
        .replace("\\geq", "≥")
        .replace("\\neq", "≠")
}

private fun markdownText(source: String): AnnotatedString = buildAnnotatedString {
    val text = source.replace("\r\n", "\n")
    var index = 0
    var lineStart = true
    while (index < text.length) {
        if (lineStart && text.startsWith("```", index)) {
            val headerEnd = text.indexOf('\n', index)
            if (headerEnd >= 0) {
                val end = text.indexOf("```", headerEnd + 1)
                val bodyEnd = if (end >= 0) end else text.length
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                    append(text.substring(headerEnd + 1, bodyEnd))
                }
                index = if (end >= 0) end + 3 else text.length
                lineStart = index > 0 && text[index - 1] == '\n'
                continue
            }
        }
        if (lineStart) {
            val lineEnd = text.indexOf('\n', index).let { if (it < 0) text.length else it }
            val line = text.substring(index, lineEnd)
            val header = Regex("""^(#{1,6})\s+""").find(line)
            if (header != null) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(line.substring(header.value.length))
                }
                index = lineEnd
                lineStart = false
                continue
            }
            val bullet = Regex("""^(\s*)[-*]\s+""").find(line)
            if (bullet != null) {
                append(bullet.groupValues[1])
                append("• ")
                index += bullet.value.length
                lineStart = false
                continue
            }
        }
        val math = when {
            text.startsWith("\\[", index) -> "\\[" to "\\]"
            text.startsWith("\\(", index) -> "\\(" to "\\)"
            text.startsWith("$$", index) -> "$$" to "$$"
            text.startsWith("$", index) -> "$" to "$"
            else -> null
        }
        if (math != null) {
            val end = text.indexOf(math.second, index + math.first.length)
            if (end >= 0) {
                append(renderSimpleMath(text.substring(index + math.first.length, end)))
                index = end + math.second.length
                lineStart = false
                continue
            }
        }
        val delimiter = when {
            text.startsWith("**", index) -> "**"
            text.startsWith("__", index) -> "__"
            text[index] == '`' -> "`"
            text[index] == '*' -> "*"
            text[index] == '_' -> "_"
            else -> null
        }
        if (delimiter != null) {
            val end = text.indexOf(delimiter, index + delimiter.length)
            if (end > index + delimiter.length && !text.substring(index + delimiter.length, end).contains('\n')) {
                val style = when (delimiter) {
                    "**", "__" -> SpanStyle(fontWeight = FontWeight.Bold)
                    "`" -> SpanStyle(fontFamily = FontFamily.Monospace)
                    else -> SpanStyle(fontStyle = FontStyle.Italic)
                }
                withStyle(style) { append(text.substring(index + delimiter.length, end)) }
                index = end + delimiter.length
                lineStart = false
                continue
            }
        }
        val character = text[index++]
        append(character)
        lineStart = character == '\n'
    }
}

@Composable
private fun MarkdownText(source: String, modifier: Modifier = Modifier) {
    val formatted = remember(source) { markdownText(source) }
    Text(formatted, modifier = modifier, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun <T> ChoiceMenu(
    label: String,
    options: List<Pair<String, T>>,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        TextButton(onClick = { expanded = true }, enabled = enabled) { Text(label, maxLines = 1) }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 240.dp).widthIn(max = 280.dp),
        ) {
            options.forEach { (title, value) ->
                DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; onSelect(value) })
            }
        }
    }
}
