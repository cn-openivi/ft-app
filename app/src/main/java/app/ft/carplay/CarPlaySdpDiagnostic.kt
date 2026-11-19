package app.ft.carplay

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.ft.core.DiagLog

/**
 * Opt-in phone-side real Toyota SDP diagnostic shown independently of CarPlay feature flag.
 * The owner selects ONE already-bonded device; app never guesses or pairs for them.
 * Diagnostics DO NOT enable projection or claim iAP2/MFi/HFP availability.
 */
@Composable
internal fun CarPlaySdpDiagnostic() {
    val context = LocalContext.current
    val probe = remember(context) { CarPlayBluetoothSdpProbe(context) }
    val incoming = remember(context) { CarPlayIncomingIapProbe(context) }
    var visible by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<PairedDevice>>(emptyList()) }
    var selected by remember { mutableStateOf<PairedDevice?>(null) }
    var incomingEvents by remember { mutableStateOf<List<CarPlayIncomingIapProbe.Event>>(emptyList()) }
    var hfpStatus by remember { mutableStateOf("HFP: ещё не проверялся") }
    var notice by remember { mutableStateOf("CarPlay: тестовый выбор. Настоящее подключение пока не подтверждено.") }
    var observed by remember { mutableStateOf<List<CarPlayBluetoothSdpProbe.Observation>>(emptyList()) }

    DisposableEffect(probe, incoming) { onDispose { probe.close(); incoming.close() } }

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
    ) {
        OutlinedButton(onClick = {
            probe.close()
            incoming.close()
            incomingEvents = emptyList()
            hfpStatus = "HFP: ещё не проверялся"
            selected = null
            observed = emptyList()
            val inventory = readPairedDevices(context)
            candidates = inventory.second
            notice = inventory.first
            visible = true
        }) {
            Text("FT200: Bluetooth / SDP Toyota (диагностика)")
        }
        if (visible) {
            Text(notice)
            Text("Выберите свою Toyota из уже сопряжённых устройств. Новая пара НЕ создаётся.")
            candidates.forEach { item ->
                OutlinedButton(onClick = {
                    observed = emptyList()
                    selected = item
                    hfpStatus = "HFP: ещё не проверялся"
                    incoming.close()
                    incomingEvents = emptyList()
                    notice = "Проверяем выбранную BT-пару: ${item.name} (${item.shortAddress})"
                    probe.start(item.address) { observation ->
                        // No MAC, SSID, passphrase or raw packet bytes in durable logs.
                        DiagLog.i("FT200.SDP", "result=${observation.result} uuids=${observation.remoteUuidStrings.size} freshEvent=${observation.completedFreshSdp}; iAP2=UNVERIFIED")
                        observed = (observed + observation).takeLast(8)
                    }
                }) {
                    Text("${item.name} · ${item.shortAddress}")
                }
            }
            selected?.let { item ->
                OutlinedButton(onClick = {
                    hfpStatus = "HFP: запрошено состояние штатного профиля"
                    CarPlayHfpProfileObserver.readOnce(context, item.address) { result ->
                        context.mainExecutor.execute {
                            // Deliberately show only reason enums, never full
                            // remote MAC, phone local address or call metadata.
                            hfpStatus = "HFP: ${result.bondPolicy} / ${result.error}"
                            DiagLog.i("FT200.HFP",
                                "profile=${result.bondPolicy} error=${result.error} " +
                                    "mic=UNVERIFIED farEndVoice=UNVERIFIED")
                        }
                    }
                }) { Text("Проверить штатный HFP профиль Toyota") }
                Text(hfpStatus)
                OutlinedButton(onClick = {
                    incoming.close()
                    incomingEvents = emptyList()
                    // Register an actual phone-side RFCOMM SDP service on Samsung.
                    // This is a physical, owner-triggered diagnostic, not a CarPlay session.
                    val result = incoming.listen(item.address) { event ->
                        incomingEvents = (incomingEvents + event).takeLast(12)
                        val message = event.messageId?.let { "0x" + it.toString(16) } ?: "none"
                        DiagLog.i("FT200.IAP2", "incoming=${event.status} linkFrames=${event.validLinkFrames} controlId=$message tlvCount=${event.parameterCount} MFi=UNVERIFIED CarPlay=UNVERIFIED")
                    }
                    DiagLog.i("FT200.IAP2", "listenStart=$result CarPlay=UNVERIFIED")
                    if (result != CarPlayIncomingIapProbe.Observation.SDP_LISTENER_READY) {
                        incomingEvents = listOf(CarPlayIncomingIapProbe.Event(result))
                    }
                }) { Text("Проверить входящий iAP2 от Toyota (до 30 с)") }
                OutlinedButton(onClick = {
                    incoming.close()
                    incomingEvents = emptyList()
                    // Separate owner-triggered, explicit experimental action.
                    // Never included in auto-connect or the passive SDP probe.
                    val result = incoming.listen(
                        item.address,
                        requestIdentification = true
                    ) { event ->
                        incomingEvents = (incomingEvents + event).takeLast(14)
                        val msg = event.messageId?.let { "0x" + it.toString(16) } ?: "none"
                        DiagLog.i("FT200.IAP2",
                            "activeProbe=${event.status} controlId=$msg tlvCount=${event.parameterCount} " +
                                "MFi=UNVERIFIED CarPlay=UNVERIFIED")
                    }
                    DiagLog.i("FT200.IAP2", "identificationProbeStart=$result CarPlay=UNVERIFIED")
                    if (result != CarPlayIncomingIapProbe.Observation.SDP_LISTENER_READY) {
                        incomingEvents = listOf(CarPlayIncomingIapProbe.Event(result))
                    }
                }) {
                    Text("Эксперимент: iAP2 IdentificationRequest 0x1D00")
                }
                OutlinedButton(onClick = {
                    incoming.close()
                }) { Text("Остановить проверку iAP2") }
                incomingEvents.forEach { e ->
                    val msg = e.messageId?.let { " · CONTROL=0x" + it.toString(16) + " TLV=${e.parameterCount}" }.orEmpty()
                    Text("Toyota→Samsung: ${e.status} · LINK frames: ${e.validLinkFrames}$msg")
                }
                Text("Активная проверка отправляет только 0x1D00 после реального LINK ACK, если согласована CONTROL v2. Она не проводит MFi/Identify или запуск CarPlay.")
                Text("Даже LINK_NEGOTIATED или CONTROL 0x4301 подтверждают лишь полученные байты. MFi, Identify, Wi-Fi, AirPlay и звонки остаются НЕПОДТВЕРЖДЁННЫМИ.")
            }
            observed.forEach { item ->
                Text("${item.result}: ${item.remoteUuidStrings.size} service UUID(s); fresh SDP signal=${item.completedFreshSdp}")
                if (item.remoteUuidStrings.isNotEmpty()) {
                    Text(item.remoteUuidStrings.joinToString(", "))
                }
            }
            Text("UUID/SDP — только наблюдение. Проверки iAP2, MFi, Wi-Fi, HFP и CarPlay ACTIVE ещё не пройдены.")
            Button(onClick = {
                probe.close()
                observed = emptyList()
                candidates = emptyList()
                visible = false
            }) { Text("Закрыть BT-диагностику") }
        }
    }
}

private data class PairedDevice(
    val name: String,
    val address: String
) {
    val shortAddress: String get() = "…:${address.takeLast(5)}"
}

@SuppressLint("MissingPermission")
private fun readPairedDevices(context: Context): Pair<String, List<PairedDevice>> {
    val permission = if (Build.VERSION.SDK_INT >= 31)
        Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
    if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
        return "Нет разрешения Bluetooth. Выдайте его в настройках Android для FT." to emptyList()
    }
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        ?: return "Bluetooth-адаптер телефона отсутствует." to emptyList()
    if (!adapter.isEnabled) return "Bluetooth на телефоне выключен." to emptyList()
    return try {
        val known = adapter.bondedDevices
            .map { PairedDevice(it.name ?: "Без имени", it.address) }
            .sortedBy { it.name.lowercase() }
        (if (known.isEmpty()) "Нет сопряжённых устройств." else "Найдено пар: ${known.size}") to known
    } catch (_: SecurityException) {
        "Android запретил чтение Bluetooth-пар." to emptyList()
    } catch (_: RuntimeException) {
        "Bluetooth-стек не вернул список пар." to emptyList()
    }
}
