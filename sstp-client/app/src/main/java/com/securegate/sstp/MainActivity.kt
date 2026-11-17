package com.securegate.sstp

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securegate.sstp.ui.AppTheme
import com.securegate.sstp.vpn.Phase
import com.securegate.sstp.vpn.Profile
import com.securegate.sstp.vpn.Profiles
import com.securegate.sstp.vpn.SstpVpnService
import com.securegate.sstp.vpn.VpnState
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { MainScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen() {
    val ctx = LocalContext.current
    val status by VpnState.status.collectAsStateWithLifecycle()
    val log by VpnState.log.collectAsStateWithLifecycle()
    val saved = remember { Profiles.load(ctx) }

    var server by rememberSaveable { mutableStateOf(saved.server) }
    var user by rememberSaveable { mutableStateOf(saved.user) }
    var password by rememberSaveable { mutableStateOf(saved.password) }
    var fullTunnel by rememberSaveable { mutableStateOf(saved.fullTunnel) }
    var trustAll by rememberSaveable { mutableStateOf(saved.trustAll) }
    var autoReconnect by rememberSaveable { mutableStateOf(saved.autoReconnect) }
    var showPassword by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }

    val active = status.phase == Phase.CONNECTING || status.phase == Phase.CONNECTED || status.phase == Phase.RECONNECTING

    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) SstpVpnService.connect(ctx)
        else formError = ctx.getString(R.string.err_vpn_denied)
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun connect() {
        val p = Profile(server, user, password, fullTunnel, trustAll, autoReconnect)
        if (!p.isValid) {
            formError = ctx.getString(R.string.err_fill_fields)
            return
        }
        formError = null
        Profiles.save(ctx, p)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val intent = VpnService.prepare(ctx)
        if (intent != null) vpnPermission.launch(intent) else SstpVpnService.connect(ctx)
    }

    // ticking clock for duration / traffic
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status.phase) {
        while (status.phase == Phase.CONNECTED) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold) }) },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(status.phase, status.message, status.ip, status.dns, status.connectedSince, now)

            OutlinedTextField(
                value = server, onValueChange = { server = it }, enabled = !active, singleLine = true,
                label = { Text(stringResource(R.string.server)) },
                placeholder = { Text("vpn.example.com") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = user, onValueChange = { user = it }, enabled = !active, singleLine = true,
                label = { Text(stringResource(R.string.username)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it }, enabled = !active, singleLine = true,
                label = { Text(stringResource(R.string.password)) },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(stringResource(if (showPassword) R.string.hide else R.string.show))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow(stringResource(R.string.full_tunnel), stringResource(R.string.full_tunnel_hint), fullTunnel, !active) { fullTunnel = it }
            SwitchRow(stringResource(R.string.auto_reconnect), null, autoReconnect, !active) { autoReconnect = it }
            SwitchRow(stringResource(R.string.trust_all), stringResource(R.string.trust_all_hint), trustAll, !active) { trustAll = it }

            formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Button(
                onClick = { if (active) SstpVpnService.disconnect(ctx) else connect() },
                colors = if (active) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(stringResource(if (active) R.string.disconnect else R.string.connect), fontSize = 16.sp)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.log), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    val cm = ctx.getSystemService(ClipboardManager::class.java)
                    cm.setPrimaryClip(ClipData.newPlainText("log", log.joinToString("\n")))
                }) { Text(stringResource(R.string.copy)) }
                TextButton(onClick = { VpnState.clearLog() }) { Text(stringResource(R.string.clear)) }
            }
            Card(Modifier.fillMaxWidth().heightIn(min = 80.dp)) {
                Column(Modifier.padding(10.dp)) {
                    if (log.isEmpty()) Text("—", style = MaterialTheme.typography.bodySmall)
                    log.takeLast(120).forEach {
                        Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StatusCard(phase: Phase, message: String, ip: String?, dns: List<String>, since: Long, now: Long) {
    val (color, label) = when (phase) {
        Phase.CONNECTED -> Color(0xFF2EB872) to stringResource(R.string.st_connected)
        Phase.CONNECTING -> Color(0xFFF2A93B) to stringResource(R.string.st_connecting)
        Phase.RECONNECTING -> Color(0xFFF2A93B) to stringResource(R.string.st_reconnecting)
        Phase.ERROR -> MaterialTheme.colorScheme.error to stringResource(R.string.st_error)
        Phase.DISCONNECTED -> Color(0xFF8A94A6) to stringResource(R.string.st_disconnected)
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).background(color, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            if (phase == Phase.CONNECTED) {
                val st = VpnState.stats
                Text(stringResource(R.string.ip_address, ip ?: "-"))
                if (dns.isNotEmpty()) Text(stringResource(R.string.dns, dns.joinToString(", ")))
                Text(stringResource(R.string.duration, formatDuration((now - since).coerceAtLeast(0) / 1000)))
                Text(stringResource(R.string.traffic, formatBytes(st.bytesIn.get()), formatBytes(st.bytesOut.get())))
            } else if (message.isNotBlank()) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, hint: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

private fun formatDuration(sec: Long): String = "%d:%02d:%02d".format(sec / 3600, (sec / 60) % 60, sec % 60)

private fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.1f KB".format(b / 1024.0)
    else -> "$b B"
}
