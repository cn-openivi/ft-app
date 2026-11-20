package app.ft.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.ft.FTApp
import app.ft.core.WheelCalibrationSession
import app.ft.dr.WheelCalibrationRuntime
import app.ft.ui.car.CarStyle
import app.ft.ui.car.CarStyles
import java.util.Locale

/**
 * User starts once; no manual segment acceptance, buttons on a moving HU or
 * separate confirmation. The HU status is an optional compact launcher widget.
 */
@Composable
internal fun WheelCalibrationSettings() {
    val prefs = FTApp.instance.prefs
    val context = LocalContext.current
    val state by WheelCalibrationRuntime.status.collectAsState()
    var selectedKm by remember { mutableIntStateOf(prefs.wheelCalibrationKm) }
    val busy = state.phase in setOf(
        WheelCalibrationSession.Phase.WAITING,
        WheelCalibrationSession.Phase.MEASURING,
        WheelCalibrationSession.Phase.SAVING
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Калибровка колёс", style = MaterialTheme.typography.titleMedium)
        Text(
            "Для точной калибровки нужен прямой открытый участок дороги без резких " +
                "поворотов и с хорошим приёмом GPS. Лучше всего подходит загородное шоссе. " +
                "Нажмите один раз перед поездкой: FT сам найдёт подходящий участок, " +
                "завершит измерение и сохранит результат.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(2, 5).forEach { km ->
                TextButton(
                    enabled = !busy,
                    onClick = { selectedKm = km; prefs.wheelCalibrationKm = km }
                ) {
                    Text(if (selectedKm == km) "● $km км" else "○ $km км")
                }
            }
        }
        val enabled = CarStyle.WIDGET_CALIBRATION in CarStyle.parseWidgets(prefs.carWidgets)
        BoolSetting(
            label = "Показывать калибровку на ГУ",
            initial = enabled,
            detail = "Компактный индикатор рядом с GPS, скоростью и погодой"
        ) { show ->
            val widgets = CarStyle.parseWidgets(prefs.carWidgets).toMutableList()
            widgets.remove(CarStyle.WIDGET_CALIBRATION)
            if (show) widgets.add(CarStyle.WIDGET_CALIBRATION)
            prefs.carWidgets = widgets.joinToString(",")
            CarStyles.reload()
        }
        val current = state.measuredMetres.coerceIn(0, state.targetMetres)
        Text(
            when (state.phase) {
                WheelCalibrationSession.Phase.IDLE -> "Готово к запуску"
                WheelCalibrationSession.Phase.WAITING -> state.reason
                WheelCalibrationSession.Phase.MEASURING ->
                    "Измерено $current / ${state.targetMetres} м"
                WheelCalibrationSession.Phase.SAVING -> "Измерение завершено. Сохраняем…"
                WheelCalibrationSession.Phase.COMPLETE ->
                    "Готово. Коэффициент " + String.format(Locale.US, "%.5f", state.multiplier)
                WheelCalibrationSession.Phase.TIMED_OUT -> state.reason
                WheelCalibrationSession.Phase.UNAVAILABLE -> state.reason
            },
            style = MaterialTheme.typography.bodyMedium
        )
        Button(enabled = !busy, onClick = {
            WheelCalibrationRuntime.start(context, selectedKm)
        }) {
            Text(if (state.phase == WheelCalibrationSession.Phase.COMPLETE)
                "Калибровать заново" else "Начать калибровку")
        }
        if (busy) {
            TextButton(onClick = { WheelCalibrationRuntime.stop() }) {
                Text("Отменить")
            }
        }
        Text(
            "Время поиска — не более 20 минут. Если подходящей прямой или точных " +
                "данных GPS нет, прежний коэффициент сохраняется без изменений. " +
                "Калибровка не меняет штатный одометр и спидометр.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
