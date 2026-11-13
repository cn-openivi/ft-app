package app.ft.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import app.ft.R
import app.ft.iccoa.IccoaBluetoothIdentityLinkStore
import app.ft.iccoa.IccoaBluetoothMacInfo
import app.ft.iccoa.IccoaService
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Explicit HU-observed phone MAC only; never silently use the car or Wi-Fi MAC. */
@Composable
fun IccoaBluetoothIdentityLinkSetting() {
    val context = LocalContext.current
    val store = remember(context) { IccoaBluetoothIdentityLinkStore(context) }
    val session by IccoaService.state.collectAsStateWithLifecycle()
    val receiverId = session.verifiedReceiverId
    var configured by remember(receiverId) { mutableStateOf(
        receiverId?.let { store.configured(it) } == true) }
    var enabled by remember(receiverId) { mutableStateOf(
        receiverId?.let { store.enabled(it) } == true) }
    var address by remember { mutableStateOf("") }
    var confirmed by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    val valid = IccoaBluetoothMacInfo.hashHuObservedPhoneMac(address) != null

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.iccoa_bt_link_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.iccoa_bt_link_about),
            style = MaterialTheme.typography.bodySmall
        )
        if (receiverId == null) Text(
            stringResource(R.string.iccoa_bt_link_requires_auth),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        Text(
            if (configured) stringResource(R.string.iccoa_bt_link_configured)
            else stringResource(R.string.iccoa_bt_link_missing),
            style = MaterialTheme.typography.bodyMedium
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.iccoa_bt_link_enable), Modifier.weight(1f))
            Switch(
                checked = enabled,
                enabled = receiverId != null && configured,
                onCheckedChange = {
                    result = if (receiverId != null && store.setEnabled(receiverId, it)) {
                        enabled = store.enabled(receiverId)
                        context.getString(R.string.iccoa_bt_link_next_connect)
                    } else context.getString(R.string.iccoa_bt_link_save_error)
                }
            )
        }
        OutlinedTextField(
            value = address,
            onValueChange = { address = it; confirmed = false; result = "" },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.iccoa_bt_link_mac_label)) },
            placeholder = { Text("AA:BB:CC:DD:EE:FF") },
            singleLine = true,
            isError = address.isNotEmpty() && !valid
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
            Text(
                stringResource(R.string.iccoa_bt_link_confirm),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                enabled = receiverId != null && confirmed && valid,
                onClick = {
                    if (receiverId != null && store.saveHuObservedPhoneMac(receiverId, address)) {
                        address = ""
                        confirmed = false
                        configured = store.configured(receiverId)
                        enabled = store.enabled(receiverId)
                        result = context.getString(R.string.iccoa_bt_link_saved)
                    } else result = context.getString(R.string.iccoa_bt_link_invalid)
                }
            ) { Text(stringResource(R.string.iccoa_bt_link_save)) }
            TextButton(enabled = receiverId != null, onClick = {
                if (receiverId != null && store.forget(receiverId)) {
                    address = ""
                    configured = false
                    enabled = false
                    confirmed = false
                    result = context.getString(R.string.iccoa_bt_link_forgotten)
                } else result = context.getString(R.string.iccoa_bt_link_forget_error)
            }) { Text(stringResource(R.string.iccoa_bt_link_forget)) }
        }
        if (result.isNotBlank()) Text(result, style = MaterialTheme.typography.bodySmall)
    }
}
