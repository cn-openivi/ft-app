package app.ft.ui.car

import androidx.compose.foundation.layout.Arrangement
import app.ft.ui.components.InkText
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ft.R
import app.ft.core.CarInfo
import app.ft.core.VehicleLocation
import app.ft.core.VehicleTelemetry
import app.ft.FTApp
import app.ft.core.CarProtocol
import app.ft.core.GpsSourceMode
import app.ft.dr.DrNavigationTelemetry
import app.ft.core.MotionSource
import app.ft.core.MotionReading
import app.ft.core.VehicleMotionTelemetry
import androidx.compose.foundation.layout.Column
import kotlinx.coroutines.delay
import app.ft.ui.theme.ownDirection
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CarWidgets(style: CarStyle, modifier: Modifier = Modifier, preview: Boolean = false) {
    // Explicit owner-requested OEM instrumentation is always on the ICCOA
    // launcher, even when other optional widgets are disabled.
    val showInertial = !preview
    val motionSource = when (FTApp.instance.prefs.carProtocol) {
        CarProtocol.ICCOA -> MotionSource.ICCOA
        CarProtocol.CARLIFE -> MotionSource.BAIDU
    }
    if (style.widgets.isEmpty() && !showInertial) return
    val inertial by VehicleMotionTelemetry.current.collectAsState()
    val vehicle by VehicleTelemetry.current.collectAsState()
    val phoneGps by CarInfo.phoneGps.collectAsState()
    val verifiedDr by DrNavigationTelemetry.current.collectAsState()
    val selector = remember {
        CarGpsSourceSelector(onTransition = { before, after, at ->
            app.ft.core.DiagLog.i("VehicleGPS", "UI_SOURCE_CHANGE from=$before to=$after atMs=$at")
        })
    }
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) { delay(1_000); value = System.currentTimeMillis() }
    }
    // Observe new ICCOA *position frames*, never GearInfo as proof of a GPS fix.
    val displayNow = CarWidgetReadings.displayNow(now, vehicle, phoneGps)
    val reading = selector.select(vehicle, phoneGps, displayNow,
        manualPhoneMode = FTApp.instance.prefs.gpsSourceMode == GpsSourceMode.PHONE,
        drFix = verifiedDr)
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        style.widgets.forEach { key ->
            when (key) {
                CarStyle.WIDGET_WEATHER -> WeatherChip(style.fahrenheit, preview)
                CarStyle.WIDGET_BATTERY -> BatteryChip(preview)
                CarStyle.WIDGET_SPEED -> SpeedChip(style.mph, preview, reading)
                // Strict user-selected GPS source. Never switch on packet loss.
                CarStyle.WIDGET_COORDINATES -> CoordinatesChip(reading)
                CarStyle.WIDGET_COURSE -> CourseChip(reading)
                CarStyle.WIDGET_CALENDAR -> EventChip(style.clock24, preview)
            }
        }
        if (showInertial) {
            val sensors = inertial[motionSource]
            InertialChip("Гироскоп", CarIcons.Gyro, motionSource, sensors?.gyro, now)
            InertialChip("Акселерометр", CarIcons.Acceleration, motionSource,
                sensors?.acceleration, now)
        }
    }
}

/**
 * Displays original OEM numeric vectors, not inferred degrees/second or m/s².
 * Missing, invalid, and held readings are visibly distinct.
 */
@Composable
private fun InertialChip(
    title: String,
    icon: ImageVector,
    source: MotionSource,
    reading: MotionReading?,
    nowMs: Long
) {
    val fresh = reading?.fresh(nowMs) == true
    val state = when {
        reading?.vector == null -> "Нет данных"
        fresh -> "LIVE"
        else -> "HOLD ${((reading.ageMs(nowMs) ?: 0L) / 1_000L)}с"
    }
    val hz = reading?.approximateHz?.takeIf { it.isFinite() && it > 0.0 }
        ?.let { String.format(Locale.US, "%.1f Гц", it) }
    val invalid = reading?.rejected?.takeIf { it > 0 }?.let { " · ошибок $it" } ?: ""
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = LocalCarCard.current,
        contentColor = Color.White
    ) {
        Column(Modifier.widthIn(max = 320.dp).padding(horizontal = 12.dp, vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(7.dp))
                Text(title, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(7.dp))
                Text("${source.name} · $state", style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f))
            }
            val data = reading?.vector
            val axes = if (data == null) "X —   Y —   Z —"
                else String.format(Locale.US, "X %.2f   Y %.2f   Z %.2f",
                    data.x, data.y, data.z)
            Text(axes, style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("RAW · ед./оси не проверены" +
                (hz?.let { " · $it" } ?: "") + invalid,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = Color.White.copy(alpha = 0.62f))
        }
    }
}

@Composable
private fun WeatherChip(fahrenheit: Boolean, preview: Boolean) {
    val weather by CarInfo.weather.collectAsState()
    val reading = weather ?: if (preview) CarInfo.Weather(22.0, 0, 0L) else return
    val degrees = if (fahrenheit) reading.celsius * 9.0 / 5.0 + 32.0 else reading.celsius
    val unit = if (fahrenheit) "F" else "C"
    Chip(weatherIcon(reading.code), "${degrees.roundToInt()}°$unit", stringResource(CarInfo.label(reading.code)))
}

@Composable
private fun BatteryChip(preview: Boolean) {
    val live by CarInfo.battery.collectAsState()
    val charging by CarInfo.charging.collectAsState()
    val level = if (live < 0 && preview) 80 else live
    if (level < 0) return
    Chip(CarIcons.Battery, "$level%", if (charging) stringResource(R.string.widget_charging) else null)
}

@Composable
private fun SpeedChip(mph: Boolean, preview: Boolean, reading: CarGpsSourceSelector.Reading) {
    val speed = CarWidgetReadings.speedKmh(reading, preview)
    val shown = speed?.let { if (mph) it * 0.621371f else it }
    val label = shown?.roundToInt()?.toString() ?: "—"
    Chip(CarIcons.Speed, label,
        "${stringResource(if (mph) R.string.widget_mph else R.string.widget_kmh)} · ${CarWidgetReadings.sourceLabel(reading)}")
}

@Composable
private fun CoordinatesChip(reading: CarGpsSourceSelector.Reading) {
    val latitude = reading.latitude
    val longitude = reading.longitude
    val value = if (latitude != null && longitude != null)
        String.format(Locale.US, "%.5f, %.5f", latitude, longitude) else "—"
    Chip(CarIcons.Map, value, CarWidgetReadings.sourceLabel(reading), wide = true)
}

@Composable
private fun CourseChip(reading: CarGpsSourceSelector.Reading) {
    val bearing = CarWidgetReadings.courseDegrees(reading)
    Chip(CarIcons.Compass, bearing?.let { "${it.roundToInt()}°" } ?: "—",
        CarWidgetReadings.sourceLabel(reading))
}

@Composable
private fun EventChip(clock24: Boolean, preview: Boolean) {
    val event by CarInfo.event.collectAsState()
    val next = event ?: if (preview) CarInfo.Event(stringResource(R.string.widget_sample_event), sampleStart(), false) else null
    if (next == null) {
        Chip(CarIcons.Calendar, stringResource(R.string.widget_no_event), null)
        return
    }
    val am = stringResource(R.string.car_am)
    val pm = stringResource(R.string.car_pm)
    val when_ = if (next.allDay) null else time(next.start, clock24, am, pm)
    Chip(CarIcons.Calendar, next.title, when_, wide = true)
}

private fun time(millis: Long, clock24: Boolean, am: String, pm: String): String {
    val calendar = Calendar.getInstance().apply { timeInMillis = millis }
    val hour = calendar.get(Calendar.HOUR_OF_DAY)
    val minute = calendar.get(Calendar.MINUTE)
    if (clock24) return String.format(Locale.US, "%02d:%02d", hour, minute)
    val half = if (hour < 12) am else pm
    return String.format(Locale.US, "%d:%02d %s", if (hour % 12 == 0) 12 else hour % 12, minute, half)
}

private fun weatherIcon(code: Int): ImageVector = when (code) {
    0, 1 -> CarIcons.Sunny
    45, 48, 2, 3 -> CarIcons.Cloud
    71, 73, 75, 77, 85, 86 -> CarIcons.Snow
    else -> CarIcons.Rain
}

@Composable
private fun Chip(icon: ImageVector, value: String, detail: String?, wide: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = LocalCarCard.current,
        contentColor = Color.White
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = if (wide) 320.dp else 220.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            if (wide) {
                Text(
                    value,
                    style = MaterialTheme.typography.titleSmall.ownDirection(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            } else {
                InkText(value, MaterialTheme.typography.titleSmall.ownDirection())
            }
            if (detail != null) {
                Spacer(Modifier.width(6.dp))
                InkText(detail, MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.65f))
            }
        }
    }
}

private fun sampleStart(): Long = Calendar.getInstance().apply {
    add(Calendar.HOUR_OF_DAY, 1)
    set(Calendar.MINUTE, 30)
}.timeInMillis
