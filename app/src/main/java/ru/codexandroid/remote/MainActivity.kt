package ru.codexandroid.remote

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket

data class CodexThread(val id: String, val title: String)

class BridgeClient(
    private val host: String,
    private val port: Int,
    private val token: String,
) : AutoCloseable {
    private val socket = Socket(host, port)
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
    private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
    private var nextId = 1

    init {
        writer.write("codex-bridge $token\n")
        writer.flush()
        val hello = JSONObject(reader.readLine())
        check(hello.optBoolean("ok")) { hello.toString() }
    }

    private fun request(method: String, params: JSONObject): JSONObject {
        val id = nextId++
        writer.write(JSONObject().put("id", id).put("method", method).put("params", params).toString())
        writer.write("\n")
        writer.flush()
        while (true) {
            val message = JSONObject(reader.readLine())
            if (message.optInt("id", -1) == id) {
                check(!message.has("error")) { message.getJSONObject("error").toString() }
                return message.getJSONObject("result")
            }
        }
    }

    fun listThreads(): List<CodexThread> {
        val result = request(
            "initialize",
            JSONObject().put(
                "clientInfo",
                JSONObject().put("name", "codex-android").put("version", "0.1.0"),
            ),
        )
        check(result.has("platformFamily"))
        val threads = request(
            "thread/list",
            JSONObject().put("archived", false).put("limit", 50).put("useStateDbOnly", true),
        ).getJSONArray("data")
        return (0 until threads.length()).map { index ->
            val item = threads.getJSONObject(index)
            CodexThread(
                id = item.getString("id"),
                title = item.optString("name").ifBlank { item.optString("preview", "Без названия") },
            )
        }
    }

    override fun close() {
        socket.close()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CodexApp() }
    }
}

@androidx.compose.runtime.Composable
private fun CodexApp() {
    var host by remember { mutableStateOf("100.114.88.98") }
    var port by remember { mutableStateOf("8765") }
    var token by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Не подключено") }
    var threads by remember { mutableStateOf<List<CodexThread>>(emptyList()) }
    var connected by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Codex Android Remote", style = MaterialTheme.typography.headlineSmall)
                Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(host, { host = it }, label = { Text("Tailscale IP ПК") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(port, { port = it }, label = { Text("Порт") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(token, { token = it }, label = { Text("Bridge token") }, modifier = Modifier.weight(2f))
                }
                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            try {
                                BridgeClient(host, port.toInt(), token).use { client ->
                                    val result = client.listThreads()
                                    threads = result
                                }
                                connected = true
                                status = "Подключено · ${threads.size} сессий"
                            } catch (error: Exception) {
                                connected = false
                                status = "Ошибка: ${error.message ?: "неизвестная ошибка"}"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Подключиться и загрузить сессии") }
                if (connected) {
                    Text("История Codex", style = MaterialTheme.typography.titleLarge)
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(threads, key = { it.id }) { thread ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp)) {
                                    Text(thread.title, style = MaterialTheme.typography.titleMedium)
                                    Text(thread.id, style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = { status = "Выбрана сессия ${thread.id}" }) {
                                        Text("Открыть")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
