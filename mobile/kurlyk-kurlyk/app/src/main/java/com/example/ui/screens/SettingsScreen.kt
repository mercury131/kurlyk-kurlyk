package com.example.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.BuildConfig
import com.example.R
import com.example.data.api.ConfigResponse
import com.example.data.db.UserEntity
import com.example.data.prefs.AppSettings
import com.example.data.prefs.AuthSession
import com.example.data.prefs.ThemeMode
import com.example.ui.components.AvatarView
import com.example.ui.theme.AuroraAccent
import com.example.ui.theme.ErrorRed
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    session: AuthSession?,
    me: UserEntity?,
    settings: AppSettings,
    serverConfig: ConfigResponse?,
    cacheSizeBytes: Long,
    onBack: () -> Unit,
    onLoadConfig: () -> Unit,
    onPickAvatarForCrop: (Uri) -> Unit,
    onRemoveAvatar: () -> Unit,
    onUpdateName: (String) -> Unit,
    onSetTheme: (ThemeMode) -> Unit,
    onSetWallpaper: (Int) -> Unit,
    onSetSound: (Boolean) -> Unit,
    onSetVibration: (Boolean) -> Unit,
    onSetAutoDownloadWifi: (Boolean) -> Unit,
    onSetAppLock: (Boolean, String?) -> Unit,
    onSetAllowSelfSigned: (Boolean) -> Unit,
    onClearCache: (() -> Unit) -> Unit,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { onLoadConfig() }

    var showAvatarSheet by remember { mutableStateOf(false) }
    var showNameDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showWallpaperDialog by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var currentCacheBytes by remember(cacheSizeBytes) { mutableLongStateOf(cacheSizeBytes) }
    var devTapCount by remember { mutableIntStateOf(0) }

    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { onPickAvatarForCrop(it) }
    }

    var cameraFile by remember { mutableStateOf<File?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val f = cameraFile
        if (ok && f != null && f.exists()) {
            onPickAvatarForCrop(Uri.fromFile(f))
        }
    }

    val myName = me?.displayName ?: session?.name.orEmpty()
    val myPhone = session?.phone.orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(stringResource(R.string.settings)) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // Profile card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.clickable { showAvatarSheet = true }) {
                    AvatarView(
                        id = myPhone,
                        name = myName,
                        avatarLocalPath = me?.avatarLocalPath,
                        size = 72.dp,
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(AuroraAccent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = "Change avatar", tint = Color.White, modifier = Modifier.size(15.dp))
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(myName, style = MaterialTheme.typography.titleLarge)
                    Text(myPhone, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(session?.domain.orEmpty(), style = MaterialTheme.typography.labelMedium, color = AuroraAccent)
                }
                IconButton(onClick = { showNameDialog = true }) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit_name))
                }
            }

            HorizontalDivider()

            // Appearance
            SettingsRow(
                icon = Icons.Filled.Palette,
                title = stringResource(R.string.theme_title),
                subtitle = when (settings.themeMode) {
                    ThemeMode.DARK -> stringResource(R.string.theme_dark)
                    ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                    ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                },
                onClick = { showThemeDialog = true },
            )
            SettingsRow(
                icon = Icons.Filled.Wallpaper,
                title = stringResource(R.string.wallpaper_title),
                subtitle = "Pattern #${settings.wallpaperId + 1}",
                onClick = { showWallpaperDialog = true },
            )

            HorizontalDivider()

            // Notifications & Media
            SettingsSwitchRow(
                icon = Icons.Filled.Notifications,
                title = stringResource(R.string.notification_sound),
                checked = settings.notificationSound,
                onCheckedChange = onSetSound,
            )
            SettingsSwitchRow(
                icon = Icons.Filled.Notifications,
                title = stringResource(R.string.notification_vibration),
                checked = settings.notificationVibration,
                onCheckedChange = onSetVibration,
            )
            SettingsSwitchRow(
                icon = Icons.Filled.Wifi,
                title = stringResource(R.string.auto_download_title),
                checked = settings.autoDownloadWifi,
                onCheckedChange = onSetAutoDownloadWifi,
            )

            HorizontalDivider()

            // Security & Storage
            SettingsSwitchRow(
                icon = Icons.Filled.Lock,
                title = stringResource(R.string.app_lock_title),
                checked = settings.appLockEnabled,
                onCheckedChange = { enabled ->
                    if (enabled) showPinDialog = true else onSetAppLock(false, null)
                },
            )
            SettingsRow(
                icon = Icons.Filled.Storage,
                title = stringResource(R.string.storage_title),
                subtitle = formatFileSize(currentCacheBytes),
                onClick = {},
            )
            SettingsRow(
                icon = Icons.Filled.CleaningServices,
                title = stringResource(R.string.clear_cache),
                subtitle = null,
                onClick = {
                    onClearCache {
                        currentCacheBytes = 0L
                        Toast.makeText(context, context.getString(R.string.cache_cleared), Toast.LENGTH_SHORT).show()
                    }
                },
            )

            HorizontalDivider()

            // Server info
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.server_info_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Domain: ${session?.domain.orEmpty()} • v${serverConfig?.version ?: settings.serverVersion}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Max file: ${serverConfig?.maxFileMb ?: settings.maxFileMb} MB • File TTL: ${serverConfig?.fileTtlH ?: settings.fileTtlH}h • Max users: ${serverConfig?.maxUsers ?: settings.maxUsers}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Hidden developer switch (7 taps on version, or visible if enabled)
            AnimatedVisibility(visible = devTapCount >= 7 || settings.allowSelfSigned) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.dev_self_signed), modifier = Modifier.weight(1f))
                    Switch(checked = settings.allowSelfSigned, onCheckedChange = onSetAllowSelfSigned)
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showLogoutConfirm = true }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = ErrorRed)
                Spacer(Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.log_out),
                    color = ErrorRed,
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Text(
                text = stringResource(R.string.app_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(16.dp)
                    .clickable { devTapCount++ },
            )
        }
    }

    if (showAvatarSheet) {
        ModalBottomSheet(onDismissRequest = { showAvatarSheet = false }) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                ContextOption(stringResource(R.string.set_photo_gallery)) {
                    showAvatarSheet = false
                    galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                ContextOption(stringResource(R.string.take_photo)) {
                    showAvatarSheet = false
                    val camDir = File(context.cacheDir, "camera").apply { mkdirs() }
                    val f = File(camDir, "avatar_${System.currentTimeMillis()}.jpg")
                    cameraFile = f
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
                    cameraLauncher.launch(uri)
                }
                if (me?.avatarVer != 0L && me?.avatarLocalPath != null) {
                    ContextOption(stringResource(R.string.remove_photo), isDestructive = true) {
                        showAvatarSheet = false
                        onRemoveAvatar()
                    }
                }
            }
        }
    }

    if (showNameDialog) {
        var nameInput by remember(myName) { mutableStateOf(myName) }
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text(stringResource(R.string.edit_name)) },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (nameInput.isNotBlank()) onUpdateName(nameInput.trim())
                    showNameDialog = false
                }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text(stringResource(R.string.theme_title)) },
            text = {
                Column {
                    listOf(
                        ThemeMode.DARK to R.string.theme_dark,
                        ThemeMode.LIGHT to R.string.theme_light,
                        ThemeMode.SYSTEM to R.string.theme_system,
                    ).forEach { (mode, labelRes) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSetTheme(mode)
                                    showThemeDialog = false
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = settings.themeMode == mode, onClick = {
                                onSetTheme(mode)
                                showThemeDialog = false
                            })
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(labelRes))
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }

    if (showWallpaperDialog) {
        AlertDialog(
            onDismissRequest = { showWallpaperDialog = false },
            title = { Text(stringResource(R.string.wallpaper_title)) },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        0 to Color(0xFF152232),
                        1 to Color(0xFF1A2E47),
                        2 to Color(0xFF1D2333),
                        3 to Color(0xFF122830),
                    ).forEach { (id, color) ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(color)
                                .border(
                                    width = if (settings.wallpaperId == id) 3.dp else 1.dp,
                                    color = if (settings.wallpaperId == id) AuroraAccent else Color.Gray,
                                    shape = RoundedCornerShape(10.dp),
                                )
                                .clickable {
                                    onSetWallpaper(id)
                                    showWallpaperDialog = false
                                },
                        )
                    }
                }
            },
            confirmButton = {},
        )
    }

    if (showPinDialog) {
        var pin by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPinDialog = false },
            title = { Text("Set App Lock PIN (optional)") },
            text = {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter { ch -> ch.isDigit() }.take(8) },
                    label = { Text("4-8 digit PIN (optional)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(onClick = {
                    onSetAppLock(true, pin.ifBlank { null })
                    showPinDialog = false
                }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPinDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text(stringResource(R.string.log_out)) },
            text = { Text(stringResource(R.string.log_out_confirm)) },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                    onClick = {
                        showLogoutConfirm = false
                        onLogout()
                    },
                ) {
                    Text(stringResource(R.string.log_out))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Normal)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Normal, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
