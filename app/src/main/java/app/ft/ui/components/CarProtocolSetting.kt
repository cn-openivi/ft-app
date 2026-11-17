package app.ft.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.ft.R
import app.ft.core.CarConnection
import app.ft.core.CarProtocol

@Composable
internal fun CarProtocolSetting(): CarProtocol {
    val context = LocalContext.current
    val protocol = rememberCarProtocol()
    // Keep the original two transports selectable and list the third one explicitly disabled.
    // A restored CarPlay preference must never appear as "Baidu" in the selector.
    val protocols = listOf(CarProtocol.CARLIFE, CarProtocol.ICCOA, CarProtocol.CARPLAY)
    PickSetting(
        title = stringResource(R.string.car_protocol),
        options = listOf(
            Option("Baidu CarLife"),
            Option(stringResource(R.string.iccoa_title), stringResource(R.string.iccoa_experimental)),
            Option("Apple CarPlay", "FT200: connection not implemented yet", enabled = false)
        ),
        chosen = protocols.indexOf(protocol),
        about = stringResource(R.string.car_protocol_about),
        onPick = { index ->
            protocols.getOrNull(index)
                ?.takeIf { it.connectionReady }
                ?.let { CarConnection.selectProtocol(context, it) }
        }
    )
    return protocol
}
