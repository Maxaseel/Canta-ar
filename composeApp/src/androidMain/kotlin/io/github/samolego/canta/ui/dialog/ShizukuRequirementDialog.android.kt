package io.github.samolego.canta.ui.dialog

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import io.github.samolego.canta.core.AndroidCantaHandler
import io.github.samolego.canta.core.CantaHandler
import io.github.samolego.canta.core.CantaPlatform
import io.github.samolego.canta.generated.resources.Res
import io.github.samolego.canta.generated.resources.enable_usb_debugging
import io.github.samolego.canta.generated.resources.wireless_adb_title
import io.github.samolego.canta.generated.resources.wireless_adb_description
import io.github.samolego.canta.generated.resources.wireless_adb_host
import io.github.samolego.canta.generated.resources.wireless_adb_pairing_port
import io.github.samolego.canta.generated.resources.wireless_adb_pairing_code
import io.github.samolego.canta.generated.resources.wireless_adb_connect_port
import io.github.samolego.canta.generated.resources.wireless_adb_connect
import io.github.samolego.canta.generated.resources.wireless_adb_connecting
import io.github.samolego.canta.util.LogUtils
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private const val TAG = "ShizukuRequirementDialog"

@Composable
actual fun ShizukuRequirementDialog(
    onClose: (shouldProceed: Boolean) -> Unit,
    handler: CantaHandler,
    platform: CantaPlatform,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var pairingPort by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }
    val isUsbDebuggingEnabled = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
    } catch (e: Exception) {
        false
    }
    ShizukuRequirementDialogContent(
        onClose = onClose,
        handler = handler,
        platform = platform,
        usbRequirementRow = {
            // Prompt the user to enable USB debugging just in case,
            // if they'll need to rescue their device with a PC if
            // something goes wrong (#284).
            RequirementItem(
                text = stringResource(Res.string.enable_usb_debugging),
                isCompleted = isUsbDebuggingEnabled,
                enabled = false,
                onActionClick = {
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    } catch (e: Exception) {
                        LogUtils.e(TAG, "Failed to open development settings", e)
                    }
                },
            )
        },
        wirelessAdbContent = {
            val androidHandler = handler as? AndroidCantaHandler
            if (androidHandler != null) {
                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(Res.string.wireless_adb_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(Res.string.wireless_adb_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text(stringResource(Res.string.wireless_adb_host)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = pairingPort,
                        onValueChange = { pairingPort = it.filter(Char::isDigit).take(5) },
                        label = { Text(stringResource(Res.string.wireless_adb_pairing_port)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = pairingCode,
                        onValueChange = { pairingCode = it.filter(Char::isDigit).take(6) },
                        label = { Text(stringResource(Res.string.wireless_adb_pairing_code)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = connectPort,
                        onValueChange = { connectPort = it.filter(Char::isDigit).take(5) },
                        label = { Text(stringResource(Res.string.wireless_adb_connect_port)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        enabled = !connecting,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch {
                                connecting = true
                                val connected = androidHandler.pairWirelessAdb(
                                    host,
                                    pairingPort.toIntOrNull() ?: -1,
                                    pairingCode,
                                    connectPort.toIntOrNull() ?: -1,
                                )
                                connecting = false
                                if (connected) onClose(true)
                                else platform.showMessage(androidHandler.wirelessAdbError() ?: "Wireless debugging connection failed")
                            }
                        },
                    ) {
                        if (connecting) {
                            CircularProgressIndicator()
                            Text(stringResource(Res.string.wireless_adb_connecting))
                        } else {
                            Text(stringResource(Res.string.wireless_adb_connect))
                        }
                    }
                }
            }
        },
    )
}
