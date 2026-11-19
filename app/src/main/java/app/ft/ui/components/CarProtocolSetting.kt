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
    // Three selectable UI choices, but only two production-ready backends.
    // CarPlay experiment does not replace the running Baidu/ICCOA preference.
    val protocols = listOf(CarProtocol.CARLIFE, CarProtocol.ICCOA, CarProtocol.CARPLAY)
    PickSetting(
        title = stringResource(R.string.car_protocol),
        options = listOf(
            Option("Baidu CarLife"),
            Option(stringResource(R.string.iccoa_title), stringResource(R.string.iccoa_experimental)),
            Option("Apple CarPlay (тест)", "Только диагностика BT/iAP2; Baidu/ICCOA продолжает работать", enabled = true)
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
