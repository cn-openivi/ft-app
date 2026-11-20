package app.ft.carplay

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
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
    var trialState by remember { mutableStateOf(CarPlayTrialProgress.State()) }
    var hfpStatus by remember { mutableStateOf("HFP: ещё не проверялся") }
    val hfpContinuity = remember { CarPlayHfpContinuityLedger() }
    var diagnosticGeneration by remember { mutableStateOf(0L) }
    var hfpReadSerial by remember { mutableStateOf(0L) }
    var iapProbeSerial by remember { mutableStateOf(0L) }
    var notice by remember { mutableStateOf("CarPlay: тестовый выбор. Настоящее подключение пока не подтверждено.") }
    var observed by remember { mutableStateOf<List<CarPlayBluetoothSdpProbe.Observation>>(emptyList()) }
    val bluetoothRuntimePermission = CarPlayTrialPermissionPolicy.runtimePermission(Build.VERSION.SDK_INT)
    var hasBluetoothConnect by remember(context) {
        mutableStateOf(bluetoothRuntimePermission == null ||
            context.checkSelfPermission(bluetoothRuntimePermission) ==
                PackageManager.PERMISSION_GRANTED)
    }
    val requestBluetooth = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasBluetoothConnect = granted
        if (granted) {
            val inventory = readPairedDevices(context)
            candidates = inventory.second
            notice = inventory.first
        } else {
            candidates = emptyList()
            selected = null
            notice = "Bluetooth запрещён. Разрешите доступ к уже сопряжённой Toyota."
        }
    }

    DisposableEffect(probe, incoming) { onDispose { probe.close(); incoming.close() } }

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
    ) {
        OutlinedButton(onClick = {
            probe.close()
            incoming.close()
            diagnosticGeneration++
            iapProbeSerial++
            hfpContinuity.beginProbe(diagnosticGeneration)
            incomingEvents = emptyList()
            trialState = CarPlayTrialProgress.State()
            hfpStatus = "HFP: ещё не проверялся"
            selected = null
            observed = emptyList()
            visible = true
            val permission = bluetoothRuntimePermission
            hasBluetoothConnect = permission == null ||
                context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            if (!hasBluetoothConnect && permission != null) {
                candidates = emptyList()
                notice = "Для чтения штатной BT-пары требуется разрешение Android."
                requestBluetooth.launch(permission)
            } else {
                val inventory = readPairedDevices(context)
                candidates = inventory.second
                notice = inventory.first
            }
        }) {
            Text("FT200: Bluetooth / SDP Toyota (диагностика)")
        }
        if (visible) {
            Text(notice)
            if (!hasBluetoothConnect) {
                Text("Bluetooth разрешён только для существующей пары: без поиска и сопряжения.")
                OutlinedButton(onClick = {
                    bluetoothRuntimePermission?.let { requestBluetooth.launch(it) }
                }) { Text("Разрешить Bluetooth для проверки Toyota") }
            }
            Text("Выберите свою Toyota из уже сопряжённых устройств. Новая пара НЕ создаётся.")
            candidates.forEach { item ->
                OutlinedButton(onClick = {
                    observed = emptyList()
                    diagnosticGeneration++
                    iapProbeSerial++
                    hfpContinuity.beginProbe(diagnosticGeneration)
                    selected = item
                    hfpStatus = "HFP: ещё не проверялся"
                    incoming.close()
                    incomingEvents = emptyList()
                    trialState = CarPlayTrialProgress.State()
                    notice = "Проверяем выбранную BT-пару: ${item.name} (${item.shortAddress})"
                    val run = diagnosticGeneration
                    val currentToyota = item.address
                    probe.start(item.address) { observation ->
                        // A broadcast from the previously selected Toyota must
                        // never repaint SDP results for another active pair.
                        if (!visible || run != diagnosticGeneration ||
                            selected?.address != currentToyota) return@start
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
                    val run = diagnosticGeneration
                    val currentToyota = item.address
                    val readId = ++hfpReadSerial
                    if (!hfpContinuity.beginRead(run, readId)) return@OutlinedButton
                    CarPlayHfpProfileObserver.readOnce(context, currentToyota) { result ->
                        context.mainExecutor.execute {
                            val gate = hfpContinuity.observe(
                                run, readId, result.bondPolicy, SystemClock.elapsedRealtime())
                            // Old result from the same Toyota is stale too if a
                            // newer HFP request has already been issued.
                            if (gate == CarPlayHfpContinuityLedger.State.STALE_PROBE_IGNORED ||
                                run != diagnosticGeneration || readId != hfpReadSerial ||
                                selected?.address != currentToyota || !visible) return@execute
                            hfpStatus = "HFP: $gate / ${result.error}; " +
                                "сотовая речь НЕ ПРОВЕРЕНА"
                            DiagLog.i("FT200.HFP",
                                "continuity=$gate profile=${result.bondPolicy} error=${result.error} " +
                                    "mic=UNVERIFIED farEndVoice=UNVERIFIED")
                        }
                    }
                }) { Text("Проверить штатный HFP профиль Toyota") }
                Text(hfpStatus)
                OutlinedButton(onClick = {
                    incoming.close()
                    incomingEvents = emptyList()
                    val run = diagnosticGeneration
                    val currentToyota = item.address
                    val serial = ++iapProbeSerial
                    // Register an actual phone-side RFCOMM SDP service on Samsung.
                    // This is a physical, owner-triggered diagnostic, not a CarPlay session.
                    val result = incoming.listen(item.address) { event ->
                        if (!visible || run != diagnosticGeneration ||
                            serial != iapProbeSerial ||
                            selected?.address != currentToyota) return@listen
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
                    trialState = CarPlayTrialProgress.start()
                    val run = diagnosticGeneration
                    val currentToyota = item.address
                    val serial = ++iapProbeSerial
                    // One physical existing Toyota BT bond. HFP is read-only,
                    // independently of the actual inbound iAP2 negotiation.
                    hfpStatus = "HFP: проверяем штатный профиль"
                    val readId = ++hfpReadSerial
                    if (hfpContinuity.beginRead(run, readId)) {
                        CarPlayHfpProfileObserver.readOnce(context, currentToyota) { result ->
                            context.mainExecutor.execute {
                                val gate = hfpContinuity.observe(
                                    run, readId, result.bondPolicy, SystemClock.elapsedRealtime())
                                if (gate != CarPlayHfpContinuityLedger.State.STALE_PROBE_IGNORED &&
                                    visible && run == diagnosticGeneration &&
                                    readId == hfpReadSerial &&
                                    selected?.address == currentToyota) {
                                    hfpStatus = "HFP: $gate / ${result.error}; " +
                                        "реальные звонки ещё не проверены"
                                    DiagLog.i("FT200.HFP",
                                        "trialProfile=$gate nativeCarrierVoice=UNVERIFIED")
                                }
                            }
                        }
                    }
                    // Real owner-triggered RFCOMM listener and, after observed
                    // LINK ACK and CONTROL v2, a genuine 0x1D00 request.
                    val result = incoming.listen(item.address, requestIdentification = true) { event ->
                        context.mainExecutor.execute {
                            if (!visible || run != diagnosticGeneration ||
                                serial != iapProbeSerial ||
                                selected?.address != currentToyota) return@execute
                            incomingEvents = (incomingEvents + event).takeLast(14)
                            trialState = CarPlayTrialProgress.observe(trialState, event.status)
                            val msg = event.messageId?.let { "0x" + it.toString(16) } ?: "none"
                            DiagLog.i("FT200.IAP2",
                                "trial=${event.status} controlId=$msg fields=${event.parameterCount} " +
                                    "MFi=UNVERIFIED AirPlay=NOT_STARTED calls=UNVERIFIED")
                        }
                    }
                    if (result != CarPlayIncomingIapProbe.Observation.SDP_LISTENER_READY) {
                        incomingEvents = listOf(CarPlayIncomingIapProbe.Event(result))
                        trialState = CarPlayTrialProgress.observe(trialState, result)
                    }
                }) {
                    Text("Пробный запуск CarPlay: iAP2 + HFP (30 с)")
                }
                OutlinedButton(onClick = {
                    iapProbeSerial++
                    trialState = CarPlayTrialProgress.observe(trialState,
                        CarPlayIncomingIapProbe.Observation.CANCELLED)
                    incoming.close()
                    // Keep observed evidence visible for the owner's Copy
                    // Report action even after the physical trial is stopped.
                }) { Text("Остановить проверку iAP2") }
                Text("Результат пробного запуска: ${carPlayTrialStatus(trialState)}")
                Text("MFi: не подтверждён · AirPlay: не запущен · CarPlay ACTIVE: нет")
                OutlinedButton(onClick = {
                    // Explicit owner action. Only enum/status/count evidence is
                    // exported: NO MAC, SSID, PSK, phone, certificate or raw bytes.
                    val text = CarPlayTrialReport.format(
                        trialState, hfpContinuity.state(SystemClock.elapsedRealtime()), incomingEvents)
                    val copied = runCatching {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                            ?: return@runCatching false
                        clipboard.setPrimaryClip(ClipData.newPlainText("FT200 CarPlay test", text))
                        true
                    }.getOrDefault(false)
                    notice = if (copied) "Протокол без личных данных скопирован"
                        else "Не удалось скопировать протокол"
                }) { Text("Скопировать результат CarPlay-теста") }
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
                diagnosticGeneration++
                iapProbeSerial++
                hfpContinuity.beginProbe(diagnosticGeneration)
                probe.close()
                incoming.close()
                incomingEvents = emptyList()
                trialState = CarPlayTrialProgress.State()
                hfpStatus = "HFP: ещё не проверялся"
                observed = emptyList()
                candidates = emptyList()
                selected = null
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

private fun carPlayTrialStatus(s: CarPlayTrialProgress.State): String {
    val stage = when (s.phase) {
        CarPlayTrialProgress.Phase.NOT_STARTED -> "не запускали"
        CarPlayTrialProgress.Phase.WAITING_FOR_TOYOTA -> "ожидаем Toyota по Bluetooth"
        CarPlayTrialProgress.Phase.TOYOTA_RFCOMM_CONNECTED -> "Toyota подключилась к RFCOMM"
        CarPlayTrialProgress.Phase.IAP2_LINK_NEGOTIATED -> "iAP2 LINK согласован"
        CarPlayTrialProgress.Phase.IDENTIFICATION_REQUESTED -> "iAP2 запрос идентификации отправлен"
        CarPlayTrialProgress.Phase.IDENTIFICATION_OBSERVED -> "ответ идентификации получен"
        CarPlayTrialProgress.Phase.WIFI_DIALECT_OBSERVED -> "обнаружен вариант Wi-Fi обмена"
        CarPlayTrialProgress.Phase.MFI_TRUST_REQUIRED -> "необходимо подтвердить MFi"
        CarPlayTrialProgress.Phase.FINISHED_WITHOUT_CARPLAY -> "проверка завершилась без CarPlay"
    }
    val failure = when (s.blocker) {
        CarPlayTrialProgress.Blocker.NONE -> ""
        CarPlayTrialProgress.Blocker.BLUETOOTH_PERMISSION_OR_ADAPTER -> " · проверьте Bluetooth/разрешение"
        CarPlayTrialProgress.Blocker.NO_EXISTING_TOYOTA_BOND -> " · нет нужной BT-пары"
        CarPlayTrialProgress.Blocker.NO_TOYOTA_RFCOMM -> " · Toyota не открыла RFCOMM"
        CarPlayTrialProgress.Blocker.IAP2_LINK_FAILED -> " · нет согласованного iAP2 LINK"
        CarPlayTrialProgress.Blocker.MFI_CERTIFICATE_UNTRUSTED -> " · сертификат не доверен"
        CarPlayTrialProgress.Blocker.MFI_SIGNATURE_UNVERIFIED -> " · подпись не проверена"
        CarPlayTrialProgress.Blocker.IO_OR_TIMEOUT -> " · тайм-аут или ошибка ввода/вывода"
        CarPlayTrialProgress.Blocker.STOPPED_BY_OWNER -> " · остановлено"
    }
    return stage + failure
}
