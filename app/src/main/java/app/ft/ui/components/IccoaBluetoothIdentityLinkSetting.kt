package app.ft.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import app.ft.iccoa.IccoaBtIdentityDelivery
import app.ft.iccoa.IccoaService
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Optional technical fallback when Android masks its own Classic phone MAC. No opt-in toggle. */
@Composable
fun IccoaBluetoothIdentityLinkSetting() {
    val context = LocalContext.current
    val store = remember(context) { IccoaBluetoothIdentityLinkStore(context) }
    val session by IccoaService.state.collectAsStateWithLifecycle()
    val receiverId = session.verifiedReceiverId
    var configured by remember(receiverId) { mutableStateOf(
        receiverId?.let { store.configured(it) } == true) }
    var address by remember { mutableStateOf("") }
    var showManualFallback by remember { mutableStateOf(false) }
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
        Text(stringResource(when (session.btIdentityDelivery) {
            IccoaBtIdentityDelivery.NOT_ATTEMPTED -> R.string.ft186_bt_id_waiting
            IccoaBtIdentityDelivery.QUEUED -> R.string.ft186_bt_id_queued
            IccoaBtIdentityDelivery.SEND_FAILED -> R.string.ft186_bt_id_failed
            IccoaBtIdentityDelivery.IDENTITY_UNAVAILABLE -> R.string.ft186_bt_id_unavailable
        }), style = MaterialTheme.typography.bodySmall)
        // Automatic receiver-scoped ICCOA identity mapping already runs in IccoaService.
        // Ordinary HFP pairing is managed by Android and never requires the user
        // to type a MAC address. The system may still ask once for pairing approval.
        BluetoothCallsSetting(projectionConnected = session.connected)
        TextButton(onClick = { showManualFallback = !showManualFallback }) {
            Text(stringResource(if (showManualFallback)
                R.string.iccoa_bt_link_hide_manual else R.string.iccoa_bt_link_show_manual))
        }
        if (showManualFallback) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it; result = "" },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.iccoa_bt_link_mac_label)) },
                placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                singleLine = true,
                isError = address.isNotEmpty() && !valid
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    enabled = receiverId != null && valid,
                    onClick = {
                        if (receiverId != null && store.saveHuObservedPhoneMac(receiverId, address)) {
                            address = ""
                            configured = store.configured(receiverId)
                            result = context.getString(R.string.iccoa_bt_link_saved)
                        } else result = context.getString(R.string.iccoa_bt_link_invalid)
                    }
                ) { Text(stringResource(R.string.iccoa_bt_link_save)) }
                TextButton(enabled = receiverId != null, onClick = {
                    if (receiverId != null && store.forget(receiverId)) {
                        address = ""
                        configured = false
                        result = context.getString(R.string.iccoa_bt_link_forgotten)
                    } else result = context.getString(R.string.iccoa_bt_link_forget_error)
                }) { Text(stringResource(R.string.iccoa_bt_link_forget)) }
            }
        }
        if (result.isNotBlank()) Text(result, style = MaterialTheme.typography.bodySmall)
    }
}
