package app.ft.core

/**
 * A failed startForeground is not a running foreground service.
 * A retry without the optional microphone type is permitted; failure of
 * CONNECTED_DEVICE/MEDIA_PROJECTION cannot be hidden.
 */
internal object ServiceForegroundPolicy {
    fun acceptedType(requested: Int, withoutOptionalMicrophone: Int,
                     tryStart: (Int) -> Boolean): Int? {
        if (tryStart(requested)) return requested
        if (requested != withoutOptionalMicrophone &&
            tryStart(withoutOptionalMicrophone)) return withoutOptionalMicrophone
        return null
    }
}
