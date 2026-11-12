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

/** Explicit HU-observed phone MAC only; never silently use the car or Wi-Fi MAC. */
@Composable
fun IccoaBluetoothIdentityLinkSetting() {
    val context = LocalContext.current
    val store = remember(context) { IccoaBluetoothIdentityLinkStore(context) }
    var configured by remember { mutableStateOf(store.configured()) }
    var enabled by remember { mutableStateOf(store.enabled()) }
    var address by remember { mutableStateOf("") }
    var confirmed by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    val valid = IccoaBluetoothMacInfo.hashHuObservedPhoneMac(address) != null

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text("ICCOA ↔ Bluetooth звонки (эксперимент)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Toyota CarLink Control/22 связывает телефон ICCOA с MAC обычного Bluetooth. " +
                "Функция выключена по умолчанию. Не гарантирует сохранение HFP.",
            style = MaterialTheme.typography.bodySmall
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
                enabled = configured,
                onCheckedChange = {
                    result = if (store.setEnabled(it)) {
                        enabled = store.enabled()
                        "Изменение применится при следующем подключении"
                    } else "Не удалось сохранить настройку"
                }
            )
        }
        OutlinedTextField(
            value = address,
            onValueChange = { address = it; confirmed = false; result = "" },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("MAC Samsung из списка устройств Toyota") },
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
                enabled = confirmed && valid,
                onClick = {
                    if (store.saveHuObservedPhoneMac(address)) {
                        address = ""
                        confirmed = false
                        configured = store.configured()
                        enabled = store.enabled()
                        result = "SHA-256 сохранён. Включите эксперимент отдельно."
                    } else result = "Недопустимый адрес или сбой сохранения"
                }
            ) { Text("Сохранить") }
            TextButton(onClick = {
                if (store.forget()) {
                    address = ""
                    configured = false
                    enabled = false
                    confirmed = false
                    result = "Привязка отключена и удалена"
                } else result = "Не удалось удалить настройку"
            }) { Text("Забыть") }
        }
        if (result.isNotBlank()) Text(result, style = MaterialTheme.typography.bodySmall)
    }
}
