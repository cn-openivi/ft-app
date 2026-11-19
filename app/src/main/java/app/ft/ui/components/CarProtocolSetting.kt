package app.ft.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.ft.R
import app.ft.core.CarConnection
import app.ft.core.CarProtocol
import app.ft.carplay.CarPlaySdpDiagnostic

@Composable
internal fun CarProtocolSetting(): CarProtocol {
    val context = LocalContext.current
    val protocol = rememberCarProtocol()
    // All three OEM transport choices are visible. CarPlay selection is only a
    // diagnostic preference until real HU authentication/handshake is proven.
    val protocols = listOf(CarProtocol.CARLIFE, CarProtocol.ICCOA, CarProtocol.CARPLAY)
    PickSetting(
        title = stringResource(R.string.car_protocol),
        options = listOf(
            Option("Baidu CarLife"),
            Option(stringResource(R.string.iccoa_title), stringResource(R.string.iccoa_experimental)),
            Option("Apple CarPlay (тест)", "Эксперимент: BT/iAP2; подключение ещё не готово", enabled = true)
        ),
        chosen = protocols.indexOf(protocol),
        about = stringResource(R.string.car_protocol_about),
        onPick = { index ->
            protocols.getOrNull(index)
                ?.let { CarConnection.selectProtocol(context, it) }
        }
    )
    // Explicitly owner-triggered diagnostics; never claim CarPlay ACTIVE.
    CarPlaySdpDiagnostic()
    return protocol
}
