package app.ft.core

import org.junit.Assert.*
import org.junit.Test

class ServiceForegroundPolicyTest {
    @Test fun fullForegroundPermissionStartsWithoutFallback() {
        val calls = mutableListOf<Int>()
        val accepted = ServiceForegroundPolicy.acceptedType(7, 3) { calls += it; true }
        assertEquals(7, accepted)
        assertEquals(listOf(7), calls)
    }

    @Test fun optionalMicrophoneFailureMayFallbackToConnectedDevice() {
        val calls = mutableListOf<Int>()
        val accepted = ServiceForegroundPolicy.acceptedType(7, 3) {
            calls += it; it == 3
        }
        assertEquals(3, accepted)
        assertEquals(listOf(7, 3), calls)
    }

    @Test fun deniedBaseForegroundCannotClaimServiceRunning() {
        val calls = mutableListOf<Int>()
        val accepted = ServiceForegroundPolicy.acceptedType(7, 3) { calls += it; false }
        assertNull(accepted)
        assertEquals(listOf(7, 3), calls)
    }

    @Test fun sameBaseTypeNeverRepeatedOnDenial() {
        val calls = mutableListOf<Int>()
        assertNull(ServiceForegroundPolicy.acceptedType(2, 2) { calls += it; false })
        assertEquals(listOf(2), calls)
    }
}
