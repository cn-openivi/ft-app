package app.ft.iccoa

import app.ft.core.BluetoothCallsState
import app.ft.core.BluetoothCallsStatus
import app.ft.core.BluetoothMediaState
import java.util.Locale

/**
 * FT186: local Classic Bluetooth role correlation for an authenticated ICCOA receiver.
 *
 * The SHA-256 fingerprint verifies the ICCOA receiver, NOT its Classic Bluetooth MAC.
 * A saved car Classic MAC is only a local candidate. Matching live HFP/A2DP peer
 * addresses proves those Android profile connections target the SAME Classic device;
 * it does not prove Toyota accepted ICCOA CONTROL method 22 or merged its device UI.
 */
internal enum class IccoaCarBluetoothMatch {
    PROJECTION_UNVERIFIED, CAR_ADDRESS_UNKNOWN, NO_PROFILE, HFP_ONLY, MEDIA_ONLY, HFP_AND_MEDIA
}

internal data class IccoaCarBluetoothRoles(
    val match: IccoaCarBluetoothMatch,
    val callsToCar: Boolean = false,
    val mediaToCar: Boolean = false,
    val otherCallsDevice: Boolean = false,
    val otherMediaDevice: Boolean = false
)

internal object IccoaCarBluetoothRoleResolver {
    private val receiverPattern = Regex("[0-9a-f]{64}")
    private val addressPattern = Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")
    private val invalidAddresses = setOf("00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "02:00:00:00:00:00")

    fun resolve(
        verifiedReceiverId: String?,
        configuredCarClassicAddress: String?,
        calls: BluetoothCallsState,
        media: BluetoothMediaState
    ): IccoaCarBluetoothRoles {
        if (verifiedReceiverId?.matches(receiverPattern) != true)
            return IccoaCarBluetoothRoles(IccoaCarBluetoothMatch.PROJECTION_UNVERIFIED)

        val address = configuredCarClassicAddress?.uppercase(Locale.ROOT)
        if (address == null || !addressPattern.matches(address) || address in invalidAddresses)
            return IccoaCarBluetoothRoles(IccoaCarBluetoothMatch.CAR_ADDRESS_UNKNOWN)

        val callsActive = calls.status == BluetoothCallsStatus.CONNECTED
        val mediaActive = media.status == BluetoothCallsStatus.CONNECTED
        val hfp = callsActive && calls.peers.any { it.address.equals(address, ignoreCase = true) }
        val a2dp = mediaActive && media.peers.any { it.address.equals(address, ignoreCase = true) }
        return IccoaCarBluetoothRoles(
            match = when {
                hfp && a2dp -> IccoaCarBluetoothMatch.HFP_AND_MEDIA
                hfp -> IccoaCarBluetoothMatch.HFP_ONLY
                a2dp -> IccoaCarBluetoothMatch.MEDIA_ONLY
                else -> IccoaCarBluetoothMatch.NO_PROFILE
            },
            callsToCar = hfp,
            mediaToCar = a2dp,
            otherCallsDevice = callsActive && calls.peers.none { it.address.equals(address, ignoreCase = true) },
            otherMediaDevice = mediaActive && media.peers.none { it.address.equals(address, ignoreCase = true) }
        )
    }
}
