package com.example.ui.components

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.R
import java.security.MessageDigest

@Composable
fun AppLockScreen(
    expectedPinHash: String?,
    onUnlocked: () -> Unit,
) {
    val context = LocalContext.current
    var pinInput by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val triggerBiometric = remember(context) {
        {
            val activity = context as? FragmentActivity
            if (activity != null) {
                val bm = BiometricManager.from(context)
                val canAuth = bm.canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                if (canAuth == BiometricManager.BIOMETRIC_SUCCESS) {
                    val prompt = BiometricPrompt(
                        activity,
                        ContextCompat.getMainExecutor(context),
                        object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                onUnlocked()
                            }
                        },
                    )
                    val info = BiometricPrompt.PromptInfo.Builder()
                        .setTitle(context.getString(R.string.app_lock_prompt))
                        .setAllowedAuthenticators(
                            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                                BiometricManager.Authenticators.DEVICE_CREDENTIAL
                        )
                        .build()
                    prompt.authenticate(info)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        triggerBiometric()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.height(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.app_lock_prompt),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(24.dp))
        if (!expectedPinHash.isNullOrBlank()) {
            OutlinedTextField(
                value = pinInput,
                onValueChange = {
                    pinInput = it.filter { ch -> ch.isDigit() }.take(8)
                    errorMsg = null
                },
                label = { Text("PIN") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (errorMsg != null) {
                Spacer(Modifier.height(8.dp))
                Text(errorMsg!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    if (sha256Hex(pinInput) == expectedPinHash) {
                        onUnlocked()
                    } else {
                        errorMsg = "Incorrect PIN"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Unlock")
            }
            Spacer(Modifier.height(12.dp))
        }
        OutlinedButton(
            onClick = { triggerBiometric() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Fingerprint, contentDescription = null)
            Spacer(Modifier.padding(4.dp))
            Text("Use Biometrics / Device Lock")
        }
    }
}

fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
