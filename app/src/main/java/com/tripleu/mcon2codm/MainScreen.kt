package com.tripleu.mcon2codm

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import android.content.ComponentName
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs.of(ctx) }

    var devices by remember { mutableStateOf<List<DeviceRowData>>(emptyList()) }
    var selected by rememberSaveable { mutableStateOf<String?>(prefs.lastSelectedPath) }
    var running by remember { mutableStateOf(BridgeRunner.isRunning()) }
    var status by rememberSaveable { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var backend by remember { mutableStateOf(BridgeRunner.backend(ctx)) }
    var probing by remember { mutableStateOf(true) }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var btEnabled by remember { mutableStateOf(isBtEnabled(ctx)) }
    var bgUnrestricted by remember { mutableStateOf(isBgUnrestricted(ctx)) }
    var pairingCode by rememberSaveable { mutableStateOf("") }
    var pairingBusy by remember { mutableStateOf(false) }
    var pairingError by rememberSaveable { mutableStateOf<String?>(null) }

    // Persist selection.
    LaunchedEffect(selected) { prefs.lastSelectedPath = selected }

    // Status polling
    LaunchedEffect(Unit) {
        while (true) {
            running = BridgeRunner.isRunning()
            backend = BridgeRunner.backend(ctx)
            bgUnrestricted = isBgUnrestricted(ctx)
            delay(1500)
        }
    }

    // Listen for BT state / connection changes — auto-rescan.
    DisposableEffect(ctx) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                btEnabled = isBtEnabled(ctx)
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ctx.registerReceiver(receiver, filter)
        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
    }

    val perms = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* proceed regardless */ }

    fun scan() {
        scope.launch {
            scanning = true
            val b = BridgeRunner.backend(ctx)
            backend = b
            status = "Scanning…"

            val btDevices = enumerateBluetoothHidDevices(ctx)

            val eventNodes: Map<String, String> =
                if (b != BridgeRunner.Backend.NONE) {
                    val found = withContext(Dispatchers.IO) { BridgeRunner.listDevices(ctx) }
                    found.associate { (path, name) -> name to path }
                } else emptyMap()

            val rows = btDevices.map { info ->
                val matchPath = eventNodes.entries.firstOrNull { entry ->
                    entry.key.contains(info.name, ignoreCase = true) ||
                    info.name.contains(entry.key, ignoreCase = true)
                }?.value
                DeviceRowData(
                    displayName = info.name,
                    targetPath = matchPath ?: "bt:${info.name}",
                    active = matchPath != null,
                    connected = info.connected,
                    deviceClass = info.deviceClass,
                )
            }
            devices = rows
            val connectedCount = rows.count { it.connected }
            status = when {
                !btEnabled -> "Bluetooth is off."
                rows.isEmpty() -> "No paired controllers."
                connectedCount == 0 -> "No controllers connected. Turn yours on."
                b == BridgeRunner.Backend.NONE -> "Follow the setup steps below."
                rows.none { it.active && it.connected } -> "Connected — press any button on the controller."
                else -> if (connectedCount == 1) "Ready." else "$connectedCount controllers ready."
            }
            // Auto-select if nothing selected but exactly one live device.
            if (selected == null) {
                rows.firstOrNull { it.connected && it.active }?.let { selected = it.targetPath }
            }
            scanning = false
        }
    }

    fun toggle() {
        val dev = selected
        scope.launch {
            if (running) {
                BridgeService.stop(ctx)
                running = false
                status = "Stopped."
                return@launch
            }
            // Validate selection against the current live device list — a stale
            // `selected` value (from prefs or a prior session) is worse than no
            // selection because it looks valid to us but won't actually work.
            val match = dev?.let { d ->
                devices.firstOrNull { it.targetPath == d && it.connected && it.active }
            }
            if (dev == null || match == null) {
                selected = null
                status = "No connected controller selected."
                return@launch
            }
            if (backend == BridgeRunner.Backend.NONE) {
                status = "Finish the setup first."
                return@launch
            }
            status = "Starting bridge…"
            withContext(Dispatchers.IO) { BridgeService.start(ctx, dev) }
            // BridgeRunner.start runs on a worker thread inside the service;
            // poll up to ~2s for the child process to actually come up.
            var confirmed = false
            var tries = 0
            while (tries < 8 && !confirmed) {
                delay(250)
                confirmed = BridgeRunner.isRunning()
                tries++
            }
            if (confirmed) {
                running = true
                status = "Bridged → Xbox Wireless Controller"
            } else {
                running = false
                status = "Failed to start the bridge. Try again."
            }
        }
    }

    // On first appearance: request permissions, probe backend, then scan.
    LaunchedEffect(Unit) {
        val missing = perms.filter { p ->
            ctx.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
        // If we have a stored ADB key, try to reconnect silently before revealing UI.
        if (prefs.adbPaired) {
            withContext(Dispatchers.IO) {
                runCatching { AdbConnectionManager.get(ctx).ensureConnected(ctx) }
            }
        }
        backend = BridgeRunner.backend(ctx)
        probing = false
        scan()
    }

    // Re-scan when BT state flips.
    LaunchedEffect(btEnabled) { if (btEnabled) scan() }

    if (showInfo) {
        InfoDialog(onDismiss = { showInfo = false })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MCON2CODM", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    Image(
                        painter = painterResource(id = R.drawable.mcon_logo),
                        contentDescription = null,
                        modifier = Modifier
                            .padding(start = 12.dp, end = 4.dp)
                            .size(36.dp)
                            .clip(CircleShape),
                    )
                },
                actions = {
                    IconButton(onClick = { showInfo = true }) {
                        Icon(Icons.Default.Info, contentDescription = "About")
                    }
                    IconButton(onClick = ::scan, enabled = !scanning && !running) {
                        Icon(Icons.Default.Refresh, contentDescription = "Rescan")
                    }
                },
            )
        },
        floatingActionButton = {
            val canStart = selected != null && !selected!!.startsWith("bt:") &&
                    backend != BridgeRunner.Backend.NONE
            ExtendedFloatingActionButton(
                onClick = ::toggle,
                icon = {
                    Icon(
                        if (running) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                    )
                },
                text = { Text(if (running) "Stop" else "Spoof") },
                containerColor = when {
                    running -> MaterialTheme.colorScheme.errorContainer
                    canStart -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                expanded = !running || canStart,
            )
        },
    ) { padding ->
        if (probing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = 12.dp, bottom = 96.dp,  // leaves room for FAB
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "status") {
                StatusCard(running = running, status = status, backend = backend)
            }

            item(key = "pairing") {
                AnimatedVisibility(
                    visible = backend == BridgeRunner.Backend.NONE,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    PairingOnboarding(
                        code = pairingCode,
                        onCodeChange = { pairingCode = it },
                        busy = pairingBusy,
                        error = pairingError,
                        onPair = { host, port ->
                            pairingBusy = true; pairingError = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    AdbConnectionManager.get(ctx)
                                        .pairOnce(host, port, pairingCode)
                                }
                                pairingBusy = false
                                result.onSuccess {
                                    pairingCode = ""; pairingError = null
                                    backend = BridgeRunner.backend(ctx)
                                    scan()
                                }.onFailure {
                                    pairingError = it.message ?: "Pairing failed"
                                }
                            }
                        },
                    )
                }
            }

            item(key = "bt_warning") {
                AnimatedVisibility(!btEnabled) {
                    WarningRow(
                        icon = Icons.Default.BluetoothDisabled,
                        text = "Bluetooth is off",
                        actionLabel = "Turn on",
                        onAction = {
                            ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        },
                    )
                }
            }

            item(key = "bg_warning") {
                AnimatedVisibility(!bgUnrestricted) {
                    WarningRow(
                        icon = Icons.Default.Info,
                        text = "Allow background for reliable spoofing",
                        actionLabel = "Allow",
                        onAction = { requestIgnoreBatteryOptimizations(ctx) },
                    )
                }
            }

            item(key = "header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Controllers",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (scanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }

            if (devices.isEmpty() && !scanning) {
                item(key = "empty") { EmptyState(btEnabled = btEnabled) }
            }
            items(devices, key = { it.targetPath }) { row ->
                // Only truly live (connected AND kernel input node present)
                // rows are selectable. Prevents stale selection that looks OK
                // but fails when spoof starts.
                val canSelect = !running && row.connected && row.active &&
                    backend != BridgeRunner.Backend.NONE
                DeviceRow(
                    data = row,
                    selected = selected == row.targetPath,
                    enabled = canSelect,
                    onClick = { selected = row.targetPath },
                )
            }
        }
    }
}

@Composable
private fun StatusCard(running: Boolean, status: String, backend: BridgeRunner.Backend) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (running) MaterialTheme.colorScheme.primaryContainer
                             else MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = if (running) Icons.Default.CheckCircle
                              else Icons.AutoMirrored.Filled.BluetoothSearching,
                contentDescription = null,
                tint = if (running) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (running) "Spoofing as Xbox Wireless Controller" else "Ready",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(status, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BackendChip(backend)
        }
    }
}

@Composable
private fun BackendChip(backend: BridgeRunner.Backend) {
    val (label, color) = when (backend) {
        BridgeRunner.Backend.ROOT     -> "root"     to MaterialTheme.colorScheme.tertiaryContainer
        BridgeRunner.Backend.SHIZUKU  -> "shizuku"  to MaterialTheme.colorScheme.secondaryContainer
        BridgeRunner.Backend.ADB_WIFI -> "adb wifi" to MaterialTheme.colorScheme.primaryContainer
        BridgeRunner.Backend.NONE     -> "no access" to MaterialTheme.colorScheme.errorContainer
    }
    Surface(color = color, shape = RoundedCornerShape(50)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Pairing onboarding: clean 3-step card. No IP/port fields — pairing always
// happens on 127.0.0.1 and the port is auto-discovered via mDNS. User only
// enters the 6-digit code.
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairingOnboarding(
    code: String,
    onCodeChange: (String) -> Unit,
    busy: Boolean,
    error: String?,
    onPair: (host: String, port: Int) -> Unit,
) {
    val ctx = LocalContext.current
    val detected by AdbDiscovery.pairingPortFlow(ctx).collectAsState(initial = null)

    // Which step the user has tapped — lights that row up.
    // Null = nothing selected yet.
    var activeStep by rememberSaveable { mutableStateOf<Int?>(null) }
    // Manual host/port fallback if mDNS can't find the pairing service.
    var manualHost by rememberSaveable { mutableStateOf("") }
    var manualPort by rememberSaveable { mutableStateOf("") }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Set up Wireless Debugging",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            StepRow(
                number = 1,
                active = activeStep == 1,
                title = "Turn on Wireless Debugging",
                subtitle = "Settings → System → Developer options → Wireless debugging",
                onClick = { activeStep = 1 },
            )

            StepRow(
                number = 2,
                active = activeStep == 2,
                title = "Tap ‘Pair device with pairing code’",
                subtitle = "A 6-digit code will appear. Tip: put this screen in split-screen with MCON2CODM so IP and port auto-detect — highly recommended.",
                onClick = { activeStep = 2 },
            )

            StepRow(
                number = 3,
                active = activeStep == 3,
                title = "Enter the 6-digit code",
                subtitle = if (activeStep == 3) "Type the code, then Pair"
                           else "Tap to enter code",
                onClick = { activeStep = 3 },
            ) {
                // Only rendered when step 3 is active (see StepRow logic).
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { onCodeChange(it.filter { c -> c.isDigit() }.take(6)) },
                        label = { Text("Pairing code") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    )
                    // Show IP/Port fields only when mDNS can't detect them.
                    if (detected == null) {
                        Text(
                            "Can't auto-detect the pairing service. Enter the IP and port shown on the pairing screen:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = manualHost,
                                onValueChange = { manualHost = it.trim() },
                                label = { Text("IP") },
                                placeholder = { Text("192.168.x.x") },
                                singleLine = true,
                                modifier = Modifier.weight(2f),
                                enabled = !busy,
                            )
                            OutlinedTextField(
                                value = manualPort,
                                onValueChange = { manualPort = it.filter { c -> c.isDigit() }.take(5) },
                                label = { Text("Port") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                enabled = !busy,
                            )
                        }
                    }
                    error?.let {
                        Text(
                            it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    val host = detected?.host?.hostAddress ?: manualHost.ifBlank { "127.0.0.1" }
                    val port = detected?.port ?: manualPort.toIntOrNull() ?: 0
                    val canPair = !busy && code.length == 6 &&
                                  (detected != null || manualPort.toIntOrNull() != null)
                    Button(
                        onClick = { onPair(host, port) },
                        enabled = canPair,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Pairing…")
                        } else {
                            Icon(Icons.Default.Key, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Pair")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepRow(
    number: Int,
    active: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    action: @Composable (() -> Unit)? = null,
) {
    val rowBg = if (active) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
    val indicatorBg = if (active) MaterialTheme.colorScheme.primary
                     else MaterialTheme.colorScheme.surface
    val indicatorFg = if (active) MaterialTheme.colorScheme.onPrimary
                     else MaterialTheme.colorScheme.onSurfaceVariant
    val titleColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                     else MaterialTheme.colorScheme.onSurface
    val subtitleColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                        else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        color = rowBg,
        shape = RoundedCornerShape(14.dp),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        border = if (active) null
                 else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.size(28.dp),
                shape = CircleShape,
                color = indicatorBg,
                border = if (active) null
                         else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "$number",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = indicatorFg,
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = titleColor,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor,
                )
                if (active && action != null) {
                    action()
                }
            }
        }
    }
}

@Composable
private fun WarningRow(
    icon: ImageVector,
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(text, modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun EmptyState(btEnabled: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (btEnabled) Icons.Default.Bluetooth else Icons.Default.BluetoothDisabled,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (btEnabled) "No paired controllers" else "Bluetooth is off",
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

data class DeviceRowData(
    val displayName: String,
    val targetPath: String,   // "/dev/input/eventX" if active, else "bt:<name>"
    val active: Boolean,
    val connected: Boolean,
    val deviceClass: Int = 0,
)

@Composable
private fun DeviceRow(
    data: DeviceRowData, selected: Boolean, enabled: Boolean, onClick: () -> Unit,
) {
    val subtitle = when {
        data.connected && data.active -> "Ready"
        data.connected && !data.active -> "Connected — needs backend to spoof"
        else -> "Paired, not connected"
    }
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RadioButton(selected = selected, onClick = onClick, enabled = enabled)
            Icon(
                imageVector = iconForDeviceClass(data.deviceClass),
                contentDescription = null,
                tint = if (data.connected) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(data.displayName, style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (data.connected) FontWeight.Medium else FontWeight.Normal)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ConnectionChip(connected = data.connected, active = data.active)
        }
    }
}

@Composable
private fun ConnectionChip(connected: Boolean, active: Boolean) {
    val (label, bg, fg) = when {
        connected && active -> Triple("live",
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.onPrimary)
        connected -> Triple("connected",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer)
        else -> Triple("paired",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Surface(color = bg, shape = RoundedCornerShape(50)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = fg,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

private fun iconForDeviceClass(deviceClass: Int): ImageVector =
    when (deviceClass) {
        BluetoothClass.Device.PERIPHERAL_POINTING           -> Icons.Default.Mouse
        BluetoothClass.Device.PERIPHERAL_KEYBOARD           -> Icons.Default.Keyboard
        BluetoothClass.Device.PERIPHERAL_KEYBOARD_POINTING  -> Icons.Default.Keyboard
        // Everything else we might list (joystick, gamepad, remote control)
        // renders as a gamepad — that's what this app is about.
        else -> Icons.Default.SportsEsports
    }

@Composable
private fun InfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How this works") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "This app forwards events from any paired Bluetooth controller " +
                    "to a virtual Xbox Wireless Controller (Model 1708) so games like " +
                    "CODM see a supported controller.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Enumerating devices uses normal Bluetooth permissions. " +
                    "Spoofing requires writing to /dev/uhid, which Android " +
                    "only exposes to shell / root. The app self-pairs with " +
                    "Wireless Debugging to get that access — no extra apps needed.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

// ---------------------------------------------------------------------------
// Bluetooth enumeration helpers (top-level, not @Composable)
// ---------------------------------------------------------------------------

private fun isBtEnabled(ctx: Context): Boolean = try {
    val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    mgr?.adapter?.isEnabled == true
} catch (_: Throwable) { false }

data class BtDeviceInfo(
    val name: String,
    val address: String,
    val connected: Boolean,
    val deviceClass: Int,
)

private suspend fun enumerateBluetoothHidDevices(ctx: Context): List<BtDeviceInfo> {
    val hasConnect = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED
    if (!hasConnect) return emptyList()

    val btManager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        ?: return emptyList()
    val adapter: BluetoothAdapter = btManager.adapter ?: return emptyList()
    if (!adapter.isEnabled) return emptyList()

    val bonded: List<BluetoothDevice> = try {
        @Suppress("MissingPermission")
        adapter.bondedDevices
            .filter { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PERIPHERAL }
            .toList()
    } catch (_: SecurityException) { return emptyList() }

    val connectedAddrs: Set<String> = getConnectedHidAddresses(ctx, adapter)

    return bonded
        .filter { it.address in connectedAddrs }  // connected-only
        .map { dev ->
            @Suppress("MissingPermission")
            BtDeviceInfo(
                name = dev.name ?: dev.address,
                address = dev.address,
                connected = true,
                deviceClass = dev.bluetoothClass?.deviceClass ?: 0,
            )
        }
        .sortedBy { it.name }
}

@Suppress("MissingPermission")
private suspend fun getConnectedHidAddresses(
    ctx: Context,
    adapter: BluetoothAdapter,
): Set<String> = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
    val addrs = mutableSetOf<String>()
    val listener = object : android.bluetooth.BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) {
            try { proxy.connectedDevices.forEach { addrs.add(it.address) } }
            catch (_: Throwable) {}
            try { adapter.closeProfileProxy(profile, proxy) } catch (_: Throwable) {}
            if (cont.isActive) cont.resumeWith(Result.success(addrs))
        }
        override fun onServiceDisconnected(profile: Int) {
            if (cont.isActive) cont.resumeWith(Result.success(addrs))
        }
    }
    val HID_HOST = 4
    val ok = adapter.getProfileProxy(ctx, listener, HID_HOST)
    if (!ok && cont.isActive) cont.resumeWith(Result.success(emptySet()))
}

// ---------------------------------------------------------------------------
// Settings intent helpers. Tries multiple known actions + direct components
// so we reliably land on the right screen across OEMs and Android versions.
// ---------------------------------------------------------------------------

private fun openWirelessDebugging(ctx: Context) {
    val candidates = listOf(
        Intent("android.settings.ADB_WIRELESS_SETTINGS"),
        Intent().setComponent(ComponentName(
            "com.android.settings",
            "com.android.settings.Settings\$AdbWirelessSettingsActivity")),
        Intent().setComponent(ComponentName(
            "com.android.settings",
            "com.android.settings.Settings\$WirelessDebuggingActivity")),
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    tryLaunch(ctx, candidates, "Couldn't open Wireless Debugging")
}

private fun openDevOptions(ctx: Context) {
    val candidates = listOf(
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        Intent().setComponent(ComponentName(
            "com.android.settings",
            "com.android.settings.DevelopmentSettings")),
        Intent().setComponent(ComponentName(
            "com.android.settings",
            "com.android.settings.Settings\$DevelopmentSettingsActivity")),
        Intent("com.android.settings.APPLICATION_DEVELOPMENT_SETTINGS"),
        Intent(Settings.ACTION_SETTINGS),
    )
    tryLaunch(ctx, candidates, "Couldn't open Developer Options — enable it in About Phone → tap Build Number 7×")
}

private fun tryLaunch(ctx: Context, intents: List<Intent>, fallbackMessage: String) {
    for (i in intents) {
        try {
            // NEW_TASK + MULTIPLE_TASK keeps Settings in its own recents entry
            // so the user can swipe back to MCON → CODM without losing state.
            i.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                Intent.FLAG_ACTIVITY_NEW_DOCUMENT
            )
            // Resolve first so we fail-fast on the fallback chain.
            if (i.resolveActivity(ctx.packageManager) == null) continue
            ctx.startActivity(i)
            return
        } catch (_: Exception) { /* try next */ }
    }
    Toast.makeText(ctx, fallbackMessage, Toast.LENGTH_LONG).show()
}

private fun isBgUnrestricted(ctx: Context): Boolean = try {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
    pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
} catch (_: Throwable) { false }

@Suppress("BatteryLife")
private fun requestIgnoreBatteryOptimizations(ctx: Context) {
    try {
        val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    } catch (_: Exception) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {}
    }
}
