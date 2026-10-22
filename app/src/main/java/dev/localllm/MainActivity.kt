package dev.localllm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            ) { ChatScreen(vm) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel) {
    var input by remember { mutableStateOf("") }
    var showSetup by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.lastIndex)
    }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Local LLM")
                        Text(vm.status, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    TextButton(enabled = !vm.busy && !vm.loading, onClick = { vm.newChat() }) { Text("New") }
                    TextButton(onClick = { showSetup = true }) { Text("Setup") }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) { items(vm.messages) { Bubble(it) } }

            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), maxLines = 5,
                    placeholder = { Text(if (vm.modelReady) "Message" else "Load a model in Setup") }
                )
                Spacer(Modifier.width(8.dp))
                if (vm.busy) Button(onClick = { vm.stop() }) { Text("Stop") }
                else Button(
                    enabled = vm.modelReady && !vm.loading && !vm.connecting && input.isNotBlank(),
                    onClick = { vm.send(input); input = "" }
                ) { Text("Send") }
            }
        }
    }
    if (showSetup) SetupDialog(vm) { showSetup = false }
}

@Composable
fun Bubble(m: UiMsg) {
    val user = m.role == "user"
    val tool = m.role == "tool"
    Box(Modifier.fillMaxWidth(), contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 340.dp),
            color = when {
                user -> MaterialTheme.colorScheme.primaryContainer
                tool -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        ) {
            Text(
                m.text.ifEmpty { "…" }, Modifier.padding(12.dp),
                fontFamily = if (tool) FontFamily.Monospace else null,
                fontSize = if (tool) 12.sp else 16.sp
            )
        }
    }
}

@Composable
fun SetupDialog(vm: ChatViewModel, onClose: () -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.loadModel(uri)
    }
    var ctx by remember { mutableStateOf(vm.nCtx.toString()) }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var layers by remember(vm.gpuSettings.gpuLayers) { mutableStateOf(vm.gpuSettings.gpuLayers.toString()) }
    val canConfigure = !vm.busy && !vm.loading
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
        title = { Text("Setup") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("GPU acceleration", style = MaterialTheme.typography.titleSmall)
                GpuBackend.entries.forEach { backend ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = vm.gpuSettings.backend == backend, enabled = canConfigure,
                            onClick = { vm.applyGpuSettings(vm.gpuSettings.copy(backend = backend)) })
                        Text(backend.label + if (backend == GpuBackend.VULKAN) " (default)" else "")
                    }
                }
                OutlinedTextField(value = layers, singleLine = true, enabled = canConfigure,
                    label = { Text("GPU layers (-ngl), default -1 (all layers)") },
                    isError = GpuSettings.parseLayers(layers) == null,
                    onValueChange = { value ->
                        layers = value
                        GpuSettings.parseLayers(value)?.let { vm.applyGpuSettings(vm.gpuSettings.copy(gpuLayers = it)) }
                    })
                Text("-1 = all layers; 0 = explicit CPU mode. Memory depends on the model and context.")
                Text("Active: ${vm.activeBackend}", style = MaterialTheme.typography.labelSmall)
                if (vm.settingsPending) Text("Saved settings differ from the loaded model. Reload to apply.")
                Button(enabled = canConfigure && vm.hasSavedModel && GpuSettings.parseLayers(layers) != null,
                    onClick = { vm.reloadModel() }) { Text("Reload model / apply settings") }
                HorizontalDivider()
                OutlinedTextField(
                    enabled = canConfigure,
                    value = ctx, singleLine = true, label = { Text("Context size (applies on load)") },
                    onValueChange = { s -> ctx = s.filter(Char::isDigit); vm.nCtx = ctx.toIntOrNull()?.coerceIn(512, 32768) ?: DEFAULT_CONTEXT_SIZE }
                )
                Button(enabled = canConfigure && GpuSettings.parseLayers(layers) != null, onClick = { picker.launch(arrayOf("*/*")) }) { Text("Pick & load .gguf model") }
                HorizontalDivider()
                Text("MCP servers (Streamable HTTP) · ${vm.toolCount} tools", style = MaterialTheme.typography.titleSmall)
                Text("${vm.toolsSent} / ${vm.toolCount} tools included (no catalog cutoff)")
                Button(enabled = canConfigure && !vm.connecting, onClick = { vm.refreshTools() }) {
                    Text(if (vm.connecting) "Connecting…" else "Refresh all tools")
                }
                vm.servers.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.url, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(enabled = canConfigure && !vm.connecting, onClick = { vm.saveServers(vm.servers - s) }) { Text("✕") }
                    }
                }
                OutlinedTextField(url, { url = it }, singleLine = true, label = { Text("https://host/mcp") })
                OutlinedTextField(token, { token = it }, singleLine = true, label = { Text("Bearer token (optional)") })
                Button(enabled = canConfigure && !vm.connecting, onClick = {
                    if (url.isNotBlank()) { vm.saveServers(vm.servers + ServerCfg(url.trim(), token.trim())); url = ""; token = "" }
                }) { Text("Add & connect") }
            }
        }
    )
}
