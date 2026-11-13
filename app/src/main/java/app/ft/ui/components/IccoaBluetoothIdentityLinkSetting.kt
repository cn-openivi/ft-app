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
        Text("ICCOA ↔ Bluetooth: привязка звонков", style = MaterialTheme.typography.titleMedium)
        Text(
            "Некоторые ГУ поддерживают привязку ICCOA к классическому Bluetooth. " +
                "По умолчанию выключено. Поддержка ГУ и работа HFP не гарантированы.",
            style = MaterialTheme.typography.bodySmall
        )
        if (receiverId == null) Text(
            "Подключите автомобиль и дождитесь подтверждённой ICCOA-авторизации. " +
                "Привязка сохраняется отдельно для каждого проверенного ГУ.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        Text(
            if (configured) "Адрес телефона сохранён в виде SHA-256 (исходный MAC не хранится)"
            else "MAC телефона ещё не задан",
            style = MaterialTheme.typography.bodyMedium
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Передавать привязку после авторизации ICCOA", Modifier.weight(1f))
            Switch(
                checked = enabled,
                enabled = receiverId != null && configured,
                onCheckedChange = {
                    result = if (receiverId != null && store.setEnabled(receiverId, it)) {
                        enabled = store.enabled(receiverId)
                        "Изменение применится при следующем подключении"
                    } else "Не удалось сохранить настройку"
                }
            )
        }
        OutlinedTextField(
            value = address,
            onValueChange = { address = it; confirmed = false; result = "" },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Bluetooth MAC телефона из списка устройств ГУ") },
            placeholder = { Text("AA:BB:CC:DD:EE:FF") },
            singleLine = true,
            isError = address.isNotEmpty() && !valid
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
            Text(
                "Подтверждаю: адрес именно моего телефона, показанный ГУ; не адрес автомобиля",
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
                        result = "SHA-256 сохранён. Передачу привязки включите отдельно."
                    } else result = "Недопустимый адрес или сбой сохранения"
                }
            ) { Text("Сохранить") }
            TextButton(enabled = receiverId != null, onClick = {
                if (receiverId != null && store.forget(receiverId)) {
                    address = ""
                    configured = false
                    enabled = false
                    confirmed = false
                    result = "Локальная настройка удалена; запись на ГУ не изменялась"
                } else result = "Не удалось удалить настройку"
            }) { Text("Забыть") }
        }
        if (result.isNotBlank()) Text(result, style = MaterialTheme.typography.bodySmall)
    }
}
