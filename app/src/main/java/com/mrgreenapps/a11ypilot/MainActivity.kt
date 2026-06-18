package com.mrgreenapps.a11ypilot

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mrgreenapps.a11ypilot.agent.AgentEngine
import com.mrgreenapps.a11ypilot.agent.AgentSettings
import com.mrgreenapps.a11ypilot.agent.NetUtil
import com.mrgreenapps.a11ypilot.agent.SpeechInput
import com.mrgreenapps.a11ypilot.mcp.McpServer
import com.mrgreenapps.a11ypilot.mcp.McpService
import com.mrgreenapps.a11ypilot.ui.theme.A11yPilotTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var agentEngine: AgentEngine
    private lateinit var speechInput: SpeechInput

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ServiceState.refresh(this)
        agentEngine = AgentEngine(applicationContext)
        speechInput = SpeechInput(applicationContext)
        setContent {
            A11yPilotTheme {
                AppShell(agentEngine, speechInput)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ServiceState.refresh(this)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppShell(engine: AgentEngine, speech: SpeechInput) {
    var settingsOpen by remember { mutableStateOf(false) }
    var settingsVersion by remember { mutableIntStateOf(0) }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.title_ai_phone_agent)) },
                actions = {
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.content_desc_settings))
                    }
                }
            )
        }
    ) { innerPadding ->
        MainScreen(
            engine = engine,
            speech = speech,
            onOpenSettings = { settingsOpen = true },
            settingsVersion = settingsVersion,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    }
    if (settingsOpen) {
        SettingsSheet(onDismiss = { settingsOpen = false; settingsVersion++ })
    }
}

@Composable
private fun MainScreen(
    engine: AgentEngine,
    speech: SpeechInput,
    onOpenSettings: () -> Unit,
    settingsVersion: Int = 0,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val enabled by ServiceState.enabled.collectAsState()
    val events by EventLog.events.collectAsState()
    val agentState by engine.state.collectAsState()
    val usage by engine.usage.collectAsState()
    val turns by engine.turns.collectAsState()
    val mcpEnabled by AgentSettings.mcpEnabled(context).collectAsState(initial = false)

    var instruction by remember { mutableStateOf("") }
    val apiKeySet = remember(agentState, settingsVersion) { AgentSettings.apiKey(context).isNotEmpty() }
    val canDraw = Settings.canDrawOverlays(context)

    val openAccessibility = {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
    val openOverlay = {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HeroCard(
            accessibilityOk = enabled,
            apiKeyOk = apiKeySet,
            mcpOn = mcpEnabled
        )

        SetupCard(
            accessibilityOk = enabled,
            apiKeyOk = apiKeySet,
            overlayOk = canDraw,
            onEnableAccessibility = openAccessibility,
            onOpenSettings = onOpenSettings,
            onGrantOverlay = openOverlay
        )

        AgentCard(
            enabled = enabled,
            apiKeySet = apiKeySet,
            state = agentState,
            speech = speech,
            instruction = instruction,
            onInstructionChange = { instruction = it },
            onRun = { engine.run(it) },
            onCancel = { engine.cancel() }
        )

        TryThisCard(
            enabled = apiKeySet && agentState !is AgentEngine.State.Running,
            onPick = { instruction = it }
        )

        UsageCard(state = agentState, usage = usage, turns = turns)

        McpCard()

        FeaturesCard()

        PrivacyCard()

        EventLogCard(events = events)

        FooterCard()

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun HeroCard(accessibilityOk: Boolean, apiKeyOk: Boolean, mcpOn: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.hero_title),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            Text(
                stringResource(R.string.hero_body),
                fontSize = 13.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusPill(stringResource(R.string.status_accessibility), accessibilityOk)
                StatusPill(stringResource(R.string.status_api_key), apiKeyOk)
                StatusPill(stringResource(R.string.status_mcp), mcpOn, neutralOff = true)
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, ok: Boolean, neutralOff: Boolean = false) {
    val color = when {
        ok -> Color(0xFF2E7D32)
        neutralOff -> Color(0xFF6B6B6B)
        else -> Color(0xFFC62828)
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.size(6.dp))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SetupCard(
    accessibilityOk: Boolean,
    apiKeyOk: Boolean,
    overlayOk: Boolean,
    onEnableAccessibility: () -> Unit,
    onOpenSettings: () -> Unit,
    onGrantOverlay: () -> Unit
) {
    val allDone = accessibilityOk && apiKeyOk && overlayOk
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (allDone)
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        else CardDefaults.cardColors()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (allDone) stringResource(R.string.setup_complete) else stringResource(R.string.setup),
                fontWeight = FontWeight.SemiBold
            )
            SetupRow(
                done = accessibilityOk,
                title = stringResource(R.string.setup_accessibility_title),
                detail = stringResource(R.string.setup_accessibility_detail),
                actionLabel = if (accessibilityOk) stringResource(R.string.setup_accessibility_open_settings) else stringResource(R.string.setup_accessibility_enable),
                onAction = onEnableAccessibility
            )
            SetupRow(
                done = apiKeyOk,
                title = stringResource(R.string.setup_api_key_title),
                detail = stringResource(R.string.setup_api_key_detail),
                actionLabel = if (apiKeyOk) stringResource(R.string.setup_api_key_change) else stringResource(R.string.setup_api_key_add),
                onAction = onOpenSettings
            )
            SetupRow(
                done = overlayOk,
                title = stringResource(R.string.setup_overlay_title),
                detail = stringResource(R.string.setup_overlay_detail),
                actionLabel = if (overlayOk) stringResource(R.string.setup_overlay_granted) else stringResource(R.string.setup_overlay_grant),
                onAction = onGrantOverlay
            )
        }
    }
}

@Composable
private fun SetupRow(
    done: Boolean,
    title: String,
    detail: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(if (done) Color(0xFF2E7D32) else Color(0xFF9E9E9E)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (done) "✓" else "•",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = onAction) { Text(actionLabel, fontSize = 12.sp) }
    }
}

@Composable
private fun AgentCard(
    enabled: Boolean,
    apiKeySet: Boolean,
    state: AgentEngine.State,
    speech: SpeechInput,
    instruction: String,
    onInstructionChange: (String) -> Unit,
    onRun: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var listening by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    val running = state is AgentEngine.State.Running
    val canRun = apiKeySet && !running

    val startListening: () -> Unit = {
        listening = true
        partial = ""
        speech.start(object : SpeechInput.Listener {
            override fun onPartial(text: String) { partial = text }
            override fun onFinal(text: String) {
                onInstructionChange(text)
                partial = ""
                if (canRun && text.isNotBlank()) onRun(text.trim())
            }
            override fun onError(message: String) {
                partial = ""
                EventLog.append("speech: $message")
            }
            override fun onEnd() { listening = false; partial = "" }
        })
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startListening() else EventLog.append("speech: RECORD_AUDIO denied") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.agent_tell_what_to_do), fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = if (listening && partial.isNotEmpty()) partial else instruction,
                    onValueChange = onInstructionChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.agent_placeholder)) },
                    singleLine = false,
                    enabled = !running && !listening
                )
                IconButton(
                    onClick = {
                        if (listening) speech.stop()
                        else {
                            val granted = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) startListening()
                            else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    enabled = !running
                ) {
                    Icon(
                        imageVector = if (listening) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = if (listening) stringResource(R.string.agent_stop_listening) else stringResource(R.string.agent_speak_instruction),
                        tint = if (listening) Color(0xFFC62828) else MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(
                    onClick = {
                        val text = instruction.trim()
                        if (text.isNotEmpty()) onRun(text)
                    },
                    enabled = canRun && instruction.isNotBlank()
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.agent_run))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = canRun && instruction.isNotBlank(),
                    onClick = { onRun(instruction.trim()) }
                ) { Text(stringResource(R.string.run)) }
                OutlinedButton(enabled = running, onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }
            Text(
                text = stringResource(R.string.status_prefix) + when (state) {
                    AgentEngine.State.Idle -> stringResource(R.string.status_idle)
                    is AgentEngine.State.Running -> stringResource(R.string.status_step, state.step, state.last)
                    is AgentEngine.State.Done ->
                        if (state.success) stringResource(R.string.status_done_ok, state.steps) else stringResource(R.string.status_done_fail, state.steps)
                    is AgentEngine.State.Error -> stringResource(R.string.status_error_message, state.steps, state.message)
                },
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            when {
                !enabled -> Text(
                    stringResource(R.string.warning_accessibility_off),
                    fontSize = 11.sp,
                    color = Color(0xFFC62828)
                )
                !apiKeySet -> Text(
                    stringResource(R.string.warning_api_key_missing),
                    fontSize = 11.sp,
                    color = Color(0xFFC62828)
                )
            }
        }
    }
}

@Composable
private fun TryThisCard(enabled: Boolean, onPick: (String) -> Unit) {
    val examples = listOf(
        stringResource(R.string.example_1),
        stringResource(R.string.example_2),
        stringResource(R.string.example_3),
        stringResource(R.string.example_4),
        stringResource(R.string.example_5),
        stringResource(R.string.example_6)
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.try_this), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.try_this_hint),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            examples.forEach { ex ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = enabled) { onPick(ex) }
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("›  ", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    Text(ex, fontSize = 13.sp, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun UsageCard(
    state: AgentEngine.State,
    usage: AgentEngine.Usage,
    turns: List<AgentEngine.Turn>
) {
    var expanded by remember { mutableStateOf(false) }
    val showCard = usage.turns > 0 || state is AgentEngine.State.Running ||
        state is AgentEngine.State.Done || state is AgentEngine.State.Error
    if (!showCard) return

    val finished = state is AgentEngine.State.Done || state is AgentEngine.State.Error
    val highlight = state is AgentEngine.State.Done && state.success

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (highlight)
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        else
            CardDefaults.cardColors()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when (state) {
                        AgentEngine.State.Idle -> stringResource(R.string.run_details)
                        is AgentEngine.State.Running -> stringResource(R.string.run_in_progress)
                        is AgentEngine.State.Done ->
                            if (state.success) stringResource(R.string.status_done_ok, state.steps) else stringResource(R.string.status_done_fail, state.steps)
                        is AgentEngine.State.Error -> stringResource(R.string.status_error, state.steps)
                    },
                    fontWeight = FontWeight.SemiBold
                )
                if (turns.isNotEmpty()) {
                    OutlinedButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) stringResource(R.string.hide_turns) else stringResource(R.string.show_turns, turns.size))
                    }
                }
            }

            when (state) {
                is AgentEngine.State.Done -> Text(state.summary, fontSize = 13.sp)
                is AgentEngine.State.Error -> Text(state.message, fontSize = 13.sp, color = Color(0xFFC62828))
                else -> {}
            }

            Text(
                "Tokens: in ${usage.input}  cache_read ${usage.cacheRead}  cache_create ${usage.cacheCreation}  out ${usage.output}",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
            Text(
                "Billed input ${usage.billedInput} (cache_create + non-cached). Cache hits ${usage.cacheRead}.",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
            if (finished) {
                Text(
                    stringResource(R.string.returned_to_app),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded && turns.isNotEmpty()) {
                Spacer(Modifier.size(4.dp))
                Text(stringResource(R.string.per_turn_breakdown), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                turns.forEach { t ->
                    Text(
                        "T${t.turn}  in=${t.input}  cR=${t.cacheRead}  cC=${t.cacheCreation}  out=${t.output}  \u2192 ${t.tools.joinToString(",").ifEmpty { stringResource(R.string.no_tool) }}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun McpCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    val mcpEnabled by AgentSettings.mcpEnabled(context).collectAsState(initial = false)
    val mcpPort by AgentSettings.mcpPort(context).collectAsState(initial = AgentSettings.DEFAULT_MCP_PORT)

    var portText by remember(mcpPort) { mutableStateOf(mcpPort.toString()) }
    var bearer by remember { mutableStateOf(AgentSettings.mcpToken(context)) }
    var revealBearer by remember { mutableStateOf(false) }
    val ip = remember(mcpEnabled) { NetUtil.activeIpv4(context) }
    val onWifi = remember(mcpEnabled) { NetUtil.isOnWifi(context) }
    val url = ip?.let { "http://$it:$mcpPort/mcp" }

    fun copy(label: String, value: String) {
        clipboard.setText(androidx.compose.ui.text.AnnotatedString(value))
        EventLog.append("mcp> copied $label")
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.mcp_server_lan), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.mcp_expose_tools),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = mcpEnabled,
                    onCheckedChange = { wantOn ->
                        scope.launch {
                            AgentSettings.setMcpEnabled(context, wantOn)
                            if (wantOn) {
                                bearer = AgentSettings.ensureMcpToken(context)
                                McpService.start(context)
                            } else {
                                McpService.stop(context)
                            }
                        }
                    }
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                mcpEnabled && url != null && onWifi -> Color(0xFF2E7D32)
                                mcpEnabled -> Color(0xFFEF6C00)
                                else -> Color(0xFFC62828)
                            }
                        )
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = when {
                        mcpEnabled && url != null && onWifi -> stringResource(R.string.mcp_running_wifi)
                        mcpEnabled && !onWifi -> stringResource(R.string.mcp_running_no_wifi)
                        mcpEnabled -> stringResource(R.string.mcp_running_no_ip)
                        else -> stringResource(R.string.mcp_stopped)
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (!onWifi) {
                Text(
                    stringResource(R.string.mcp_warning_no_wifi),
                    fontSize = 11.sp,
                    color = Color(0xFFC62828)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.mcp_ip), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            ip ?: "\u2014",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        if (ip != null) {
                            IconButton(onClick = { copy("IP", ip) }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.mcp_copy_ip))
                            }
                        }
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.mcp_port), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { v ->
                            portText = v.filter { it.isDigit() }.take(5)
                            portText.toIntOrNull()?.let { p ->
                                scope.launch { AgentSettings.setMcpPort(context, p) }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !mcpEnabled,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            }

            Column {
                Text(stringResource(R.string.mcp_endpoint_url), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        url ?: "http://<ip>:$mcpPort/mcp",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (url != null) {
                        IconButton(onClick = { copy("URL", url) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.mcp_copy_ip))
                        }
                    }
                }
            }

            Column {
                Text(stringResource(R.string.mcp_bearer_token), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (bearer.isEmpty()) stringResource(R.string.mcp_bearer_generated)
                               else if (revealBearer) bearer
                               else "\u2022".repeat(bearer.length.coerceAtMost(28)),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (bearer.isNotEmpty()) {
                        IconButton(onClick = { revealBearer = !revealBearer }) {
                            Icon(
                                if (revealBearer) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (revealBearer) stringResource(R.string.mcp_hide_bearer) else stringResource(R.string.mcp_show_bearer)
                            )
                        }
                        IconButton(onClick = { copy("bearer", bearer) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.mcp_copy_bearer))
                        }
                    }
                }
                if (bearer.isEmpty()) {
                    OutlinedButton(onClick = { bearer = AgentSettings.ensureMcpToken(context) }) {
                        Text(stringResource(R.string.mcp_generate_token))
                    }
                }
            }

            Text(
                stringResource(R.string.mcp_jsonrpc_methods),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            var selfTestResult by remember { mutableStateOf(McpServer.LAST_STATUS) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = mcpEnabled,
                    onClick = {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val r = McpServer.selfTest(mcpPort)
                            selfTestResult = r.fold(
                                onSuccess = { "Listening: $it" },
                                onFailure = { "FAIL: ${it.message}" }
                            )
                        }
                    }
                ) { Text(stringResource(R.string.mcp_self_test)) }
                Text(selfTestResult, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }

            val curl = "curl -H \"Authorization: Bearer $bearer\" \\\n" +
                "  -H \"Content-Type: application/json\" \\\n" +
                "  -d '{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}' \\\n" +
                "  ${url ?: "http://<ip>:$mcpPort/mcp"}"

            val claudeConfig = """{
  "mcpServers": {
    "phone-accessibility": {
      "command": "npx",
      "args": [
        "-y",
        "mcp-remote",
        "${url ?: "http://<ip>:$mcpPort/mcp"}",
        "--allow-http",
        "--header",
        "Authorization: Bearer $bearer"
      ]
    }
  }
}"""

            Text(stringResource(R.string.mcp_quick_test), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    curl,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { copy("curl", curl) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.mcp_copy_curl))
                }
            }

            Text(
                stringResource(R.string.mcp_claude_config),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    claudeConfig,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { copy("Claude config", claudeConfig) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.mcp_copy_config))
                }
            }

            if (!mcpEnabled) {
                Text(
                    stringResource(R.string.mcp_off_hint),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FeaturesCard() {
    val features = listOf(
        stringResource(R.string.feature_screen_title) to
            stringResource(R.string.feature_screen_body),
        stringResource(R.string.feature_planning_title) to
            stringResource(R.string.feature_planning_body),
        stringResource(R.string.feature_voice_title) to
            stringResource(R.string.feature_voice_body),
        stringResource(R.string.feature_mcp_title) to
            stringResource(R.string.feature_mcp_body),
        stringResource(R.string.feature_overlay_title) to
            stringResource(R.string.feature_overlay_body),
        stringResource(R.string.feature_tokens_title) to
            stringResource(R.string.feature_tokens_body)
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.whats_in_here), fontWeight = FontWeight.SemiBold)
            features.forEach { (title, body) ->
                Column {
                    Text("• $title", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        body,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PrivacyCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.privacy_safety), fontWeight = FontWeight.SemiBold)
            Bullet(stringResource(R.string.privacy_1))
            Bullet(stringResource(R.string.privacy_2))
            Bullet(stringResource(R.string.privacy_3))
            Bullet(stringResource(R.string.privacy_4))
            Bullet(stringResource(R.string.privacy_5))
            Bullet(stringResource(R.string.privacy_6))
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text("•  ", fontSize = 12.sp)
        Text(text, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun EventLogCard(events: List<String>) {
    val listState = rememberLazyListState()
    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) listState.animateScrollToItem(events.size - 1)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.live_event_log), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.event_log_hint),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = { EventLog.clear() }) { Text(stringResource(R.string.clear)) }
            }
            if (events.isEmpty()) {
                Text(stringResource(R.string.no_events), fontSize = 12.sp)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 320.dp)
                ) {
                    items(events) { line ->
                        Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun FooterCard() {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AssistChip(
            onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString("https://docs.anthropic.com"))
            },
            label = { Text(stringResource(R.string.anthropic_docs), fontSize = 11.sp) },
            colors = AssistChipDefaults.assistChipColors()
        )
        AssistChip(
            onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString("https://modelcontextprotocol.io"))
            },
            label = { Text(stringResource(R.string.mcp_spec), fontSize = 11.sp) },
            colors = AssistChipDefaults.assistChipColors()
        )
        Text(
            stringResource(R.string.open_source_hint),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val initialSnapshot = remember { runCatching {
        kotlinx.coroutines.runBlocking { AgentSettings.snapshot(context) }
    }.getOrNull() }

    var apiKey by remember { mutableStateOf(initialSnapshot?.apiKey.orEmpty()) }
    var model by remember { mutableStateOf(initialSnapshot?.model ?: AgentSettings.DEFAULT_MODEL) }
    var maxSteps by remember { mutableStateOf(initialSnapshot?.maxSteps?.toFloat() ?: AgentSettings.DEFAULT_MAX_STEPS.toFloat()) }
    var provider by remember { mutableStateOf(initialSnapshot?.provider ?: AgentSettings.DEFAULT_PROVIDER) }
    var baseUrl by remember { mutableStateOf(initialSnapshot?.baseUrl.orEmpty()) }
    var systemPrompt by remember { mutableStateOf(initialSnapshot?.systemPrompt.orEmpty()) }
    var mcpServers by remember { mutableStateOf(initialSnapshot?.mcpServers ?: AgentSettings.DEFAULT_MCP_SERVERS) }

    val providerOptions = listOf("anthropic", "anthropic_compat", "openai")
    val providerLabels = mapOf(
        "anthropic" to stringResource(R.string.provider_anthropic),
        "anthropic_compat" to stringResource(R.string.provider_anthropic_compat),
        "openai" to stringResource(R.string.provider_openai)
    )
    var providerExpanded by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.agent_settings), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)

            Box {
                OutlinedTextField(
                    value = providerLabels[provider] ?: provider,
                    onValueChange = {},
                    label = { Text(stringResource(R.string.provider_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = true,
                    trailingIcon = {
                        IconButton(onClick = { providerExpanded = !providerExpanded }) {
                            Icon(
                                if (providerExpanded) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null
                            )
                        }
                    }
                )
                DropdownMenu(
                    expanded = providerExpanded,
                    onDismissRequest = { providerExpanded = false }
                ) {
                    providerOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(providerLabels[option] ?: option) },
                            onClick = {
                                provider = option
                                providerExpanded = false
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text(stringResource(R.string.api_key_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text(stringResource(R.string.model_id_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
            )
            Text(stringResource(R.string.model_suggestion), fontSize = 11.sp)

            if (provider != "anthropic") {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.base_url_label)) },
                    placeholder = { Text(stringResource(R.string.base_url_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            Text(stringResource(R.string.max_tool_calls, maxSteps.toInt()))
            Slider(
                value = maxSteps,
                onValueChange = { maxSteps = it },
                valueRange = 5f..50f,
                steps = 44
            )

            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                label = { Text(stringResource(R.string.system_prompt_label)) },
                placeholder = { Text(stringResource(R.string.system_prompt_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 5
            )

            OutlinedTextField(
                value = mcpServers,
                onValueChange = { mcpServers = it },
                label = { Text(stringResource(R.string.mcp_servers_label)) },
                placeholder = { Text(stringResource(R.string.mcp_servers_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 5
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    AgentSettings.setApiKey(context, apiKey.trim())
                    scope.launch {
                        AgentSettings.setModel(context, model.trim().ifEmpty { AgentSettings.DEFAULT_MODEL })
                        AgentSettings.setMaxSteps(context, maxSteps.toInt())
                        AgentSettings.setProvider(context, provider)
                        AgentSettings.setBaseUrl(context, baseUrl.trim())
                        AgentSettings.setSystemPrompt(context, systemPrompt.trim())
                        AgentSettings.setMcpServers(context, mcpServers.trim())
                        onDismiss()
                    }
                }) { Text(stringResource(R.string.save)) }
                OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}
