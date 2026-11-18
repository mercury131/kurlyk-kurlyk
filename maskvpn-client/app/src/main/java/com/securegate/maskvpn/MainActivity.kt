package com.securegate.maskvpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securegate.maskvpn.core.TunnelConfig
import com.securegate.maskvpn.store.TunnelStore
import com.securegate.maskvpn.ui.MaskVpnTheme
import com.securegate.maskvpn.vpn.MaskVpnService
import com.securegate.maskvpn.vpn.Status
import com.securegate.maskvpn.vpn.VpnState
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaskVpnTheme { Surface(Modifier.fillMaxSize()) { AppScreen() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreen() {
    val ctx = LocalContext.current
    val store = remember { TunnelStore(ctx) }
    var tunnels by remember { mutableStateOf(store.list()) }
    var selfSigned by remember { mutableStateOf(store.allowSelfSigned) }
    val vpn by VpnState.state.collectAsStateWithLifecycle()
    var pendingConnect by remember { mutableStateOf<String?>(null) }
    var exportCfg by remember { mutableStateOf<TunnelConfig?>(null) }

    fun refresh() { tunnels = store.list() }
    fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show()

    fun addFromText(text: String) {
        runCatching { TunnelConfig.parse(text) }
            .onSuccess { store.add(it); refresh(); toast(ctx.getString(R.string.added, it.name)) }
            .onFailure { toast(ctx.getString(R.string.invalid_config)) }
    }

    // POST_NOTIFICATIONS (API 33+) for the foreground-service notification
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    val scan = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { addFromText(it) } }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } }
            .onSuccess { addFromText(it) }.onFailure { toast(ctx.getString(R.string.invalid_config)) }
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val cfg = exportCfg; exportCfg = null
        if (uri != null && cfg != null) runCatching {
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(cfg.toUri().toByteArray()) }
        }.onSuccess { toast("OK") }
    }
    val vpnConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val name = pendingConnect; pendingConnect = null
        if (res.resultCode == Activity.RESULT_OK && name != null) MaskVpnService.connect(ctx, name)
    }
    fun startConnect(name: String) {
        pendingConnect = name
        val prep = VpnService.prepare(ctx)
        if (prep != null) vpnConsent.launch(prep) else { MaskVpnService.connect(ctx, name); pendingConnect = null }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)) {
            StatusBanner(vpn)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.allow_self_signed), Modifier.weight(1f))
                Switch(checked = selfSigned, onCheckedChange = { store.allowSelfSigned = it; selfSigned = it })
            }
            HorizontalDivider()
            Text(stringResource(R.string.tunnels), fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
            if (tunnels.isEmpty()) {
                Text(stringResource(R.string.no_tunnels), Modifier.padding(vertical = 24.dp))
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(tunnels, key = { it.name }) { t ->
                        TunnelRow(
                            cfg = t,
                            active = vpn.tunnel == t.name && vpn.status != Status.DISCONNECTED,
                            status = vpn.status,
                            onConnect = { startConnect(t.name) },
                            onDisconnect = { MaskVpnService.disconnect(ctx) },
                            onExport = { exportCfg = t; exportFile.launch("${t.name}.sgvpn") },
                            onDelete = { store.remove(t.name); refresh() },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scan.launch(ScanOptions().setOrientationLocked(false).setBeepEnabled(false)
                        .setPrompt(ctx.getString(R.string.scan_qr)))
                }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.scan_qr)) }
                OutlinedButton(onClick = { importFile.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.import_file))
                }
            }
        }
    }
}

@Composable
private fun StatusBanner(vpn: VpnState.Snapshot) {
    val (label, res) = when (vpn.status) {
        Status.CONNECTED -> (vpn.address?.let { "${stringResource(R.string.status_connected)} · $it" }
            ?: stringResource(R.string.status_connected)) to MaterialTheme.colorScheme.primaryContainer
        Status.CONNECTING -> stringResource(R.string.status_connecting) to MaterialTheme.colorScheme.secondaryContainer
        Status.ERROR -> ("${stringResource(R.string.status_error)}: ${vpn.error ?: ""}") to MaterialTheme.colorScheme.errorContainer
        Status.DISCONNECTED -> stringResource(R.string.status_disconnected) to MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(color = res, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), shape = MaterialTheme.shapes.medium) {
        Text(label, Modifier.padding(14.dp), fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TunnelRow(
    cfg: TunnelConfig,
    active: Boolean,
    status: Status,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(cfg.name, fontWeight = FontWeight.Bold)
            Text("${cfg.host}:${cfg.port}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (active) {
                    Button(onClick = onDisconnect) { Text(stringResource(R.string.disconnect)) }
                } else {
                    Button(onClick = onConnect, enabled = status != Status.CONNECTING) {
                        Text(stringResource(R.string.connect))
                    }
                }
                TextButton(onClick = onExport) { Text(stringResource(R.string.export)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
            }
        }
    }
}
