package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.components.DoveBadge
import com.example.ui.theme.AuroraAccent
import com.example.ui.viewmodel.SignInUiState

private val CountryCodes = listOf(
    "+7" to "RU/KZ (+7)",
    "+1" to "US/CA (+1)",
    "+44" to "UK (+44)",
    "+49" to "DE (+49)",
    "+33" to "FR (+33)",
    "+380" to "UA (+380)",
    "+375" to "BY (+375)",
    "+995" to "GE (+995)",
    "+998" to "UZ (+998)",
    "+374" to "AM (+374)",
    "+90" to "TR (+90)",
    "+971" to "AE (+971)",
)

@Composable
fun SignInScreen(
    uiState: SignInUiState,
    allowSelfSigned: Boolean,
    onToggleSelfSigned: (Boolean) -> Unit,
    onSignIn: (domain: String, secret: String, countryCode: String, phone: String, name: String) -> Unit,
) {
    var domain by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var showSecret by remember { mutableStateOf(false) }
    var countryCode by remember { mutableStateOf("+7") }
    var showCountryMenu by remember { mutableStateOf(false) }
    var phone by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var tapCount by remember { mutableIntStateOf(0) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DoveBadge(
                size = 88.dp,
                contentDescription = stringResource(R.string.app_name),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.sign_in_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.sign_in_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.clickable { tapCount++ },
            )
            Spacer(Modifier.height(28.dp))

            OutlinedTextField(
                value = domain,
                onValueChange = { domain = it },
                label = { Text(stringResource(R.string.server_domain_label)) },
                placeholder = { Text(stringResource(R.string.server_domain_hint)) },
                leadingIcon = { Icon(Icons.Filled.Dns, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().testTag("domain_input"),
            )
            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                label = { Text(stringResource(R.string.secret_key_label)) },
                leadingIcon = { Icon(Icons.Filled.Key, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { showSecret = !showSecret }) {
                        Icon(
                            if (showSecret) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = "Toggle secret visibility",
                        )
                    }
                },
                visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("secret_input"),
            )
            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    OutlinedTextField(
                        value = countryCode,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.country_code_label)) },
                        trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                        modifier = Modifier
                            .width(116.dp)
                            .clickable { showCountryMenu = true },
                        enabled = false,
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable { showCountryMenu = true },
                    )
                    DropdownMenu(
                        expanded = showCountryMenu,
                        onDismissRequest = { showCountryMenu = false },
                    ) {
                        CountryCodes.forEach { (code, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    countryCode = code
                                    showCountryMenu = false
                                },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text(stringResource(R.string.phone_number_label)) },
                    leadingIcon = { Icon(Icons.Filled.Phone, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.weight(1f).testTag("phone_input"),
                )
            }
            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.display_name_label)) },
                leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("name_input"),
            )

            AnimatedVisibility(visible = tapCount >= 7 || allowSelfSigned) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.dev_self_signed),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = allowSelfSigned,
                            onCheckedChange = onToggleSelfSigned,
                        )
                    }
                }
            }

            if (uiState.error != null) {
                Spacer(Modifier.height(14.dp))
                Surface(
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = uiState.error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { onSignIn(domain, secret, countryCode, phone, name) },
                enabled = !uiState.isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = AuroraAccent),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("connect_button"),
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.connecting))
                } else {
                    Text(
                        text = stringResource(R.string.connect_button),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}
