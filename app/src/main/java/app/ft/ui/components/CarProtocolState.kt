package app.ft.ui.components

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.ft.FTApp
import app.ft.core.CarProtocol
import app.ft.core.CarPlayExperimentalSelectionPolicy

@Composable
fun rememberCarProtocol(): CarProtocol {
    val prefs = FTApp.instance.prefs
    var protocol by remember {
        mutableStateOf(CarPlayExperimentalSelectionPolicy.display(
            prefs.carProtocol, prefs.carPlayExperimentalSelected
        ))
    }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == "carProtocol" || key == "carPlayExperimentalSelected")
                protocol = CarPlayExperimentalSelectionPolicy.display(
                    prefs.carProtocol, prefs.carPlayExperimentalSelected
                )
        }
        prefs.registerListener(listener)
        protocol = CarPlayExperimentalSelectionPolicy.display(
            prefs.carProtocol, prefs.carPlayExperimentalSelected
        )
        onDispose { prefs.unregisterListener(listener) }
    }
    return protocol
}
