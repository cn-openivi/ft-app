package app.ft.supercast.adb

internal object WirelessPairingCode {
    fun isValid(code: String) = code.length == 6 && code.all { it in '0'..'9' }
}
