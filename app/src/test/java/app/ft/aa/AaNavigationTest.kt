package app.ft.aa

import app.ft.core.ProtoReader
import app.ft.core.ProtoWriter
import org.junit.Assert.*
import org.junit.Test

class AaNavigationTest {
    private fun notification(type: Int, road: String = "Lenina") = ProtoWriter()
        .message(1, ProtoWriter().message(1, ProtoWriter().enum(1, type))
            .message(4, ProtoWriter().string(1, road))).toByteArray()

    private fun position(value: String, unit: Int = 1, seconds: Int = 25) =
        ProtoWriter().message(1, ProtoWriter()
            .message(1, ProtoWriter().string(2, value).enum(3, unit))
            .int64(2, seconds.toLong())).toByteArray()

    @Test fun discoveryAdvertisesStructuredTurnByTurnWithoutImagePromises() {
        val discovery = ProtoReader(AaMessages.serviceDiscoveryResponse(800, 480, 30, 160, false, "FT"))
        val service = discovery.messages(1).single { it.int(1) == AaProtocol.CH_NAVIGATION }
        val config = service.message(8)!!
        assertEquals(1000, config.int(1))
        assertEquals(1, config.int(2)) // TURN_BY_TURN, not NEXT_TURN_IMAGE
        assertFalse(config.has(3)) // no invented NavigationImageOptions
    }

    @Test fun modernNotificationWithSeparateDistanceAndMeters() {
        val nav = AaNavigation()
        assertNull(nav.accept(AaProtocol.NAV_NOTIFICATION, notification(8))?.distanceMeters)
        val info = nav.accept(AaProtocol.NAV_NEXT_TURN_DISTANCE, position("300"))!!
        assertTrue(info.active)
        assertEquals(8, info.maneuver)
        assertEquals("Lenina", info.road)
        assertEquals(300, info.distanceMeters)
        assertEquals(25, info.timeSeconds)
    }

    @Test fun realWorldWireFixtureHasDisplayTextAndUnitNoRawMeters() {
        // Documented decrypted AA 0x8007: {1:{1:{2:"0",3:6}}}
        val wire = byteArrayOf(0x0a,0x07,0x0a,0x05,0x12,0x01,0x30,0x18,0x06)
        assertArrayEquals(wire, position("0", 6, 0)
            .let { // remove optional step time=0 to reproduce exact captured structure
                ProtoWriter().message(1, ProtoWriter().message(1,
                    ProtoWriter().string(2, "0").enum(3, 6))).toByteArray()
            })
        val nav = AaNavigation()
        assertEquals(0, nav.accept(AaProtocol.NAV_NEXT_TURN_DISTANCE, wire)?.distanceMeters)
    }

    @Test fun convertsDocumentedDisplayUnitsAndRejectsAmbiguity() {
        val nav = AaNavigation()
        assertEquals(1200, nav.accept(AaProtocol.NAV_NEXT_TURN_DISTANCE, position("1.2",2))?.distanceMeters)
        assertEquals(1200, nav.accept(AaProtocol.NAV_NEXT_TURN_DISTANCE, position("1,2",3))?.distanceMeters)
        assertEquals(305, nav.accept(AaProtocol.NAV_NEXT_TURN_DISTANCE, position("1000",6))?.distanceMeters)
        assertEquals(914, nav.accept(AaProtocol.NAV_NEXT_TURN_DISTANCE, position("1000",7))?.distanceMeters)
        assertNull(nav.accept(AaProtocol.NAV_NEXT_TURN_DISTANCE, position("1,000",1)))
        assertNull(nav.accept(AaProtocol.NAV_NEXT_TURN_DISTANCE, position("3",0)))
        assertNull(nav.accept(AaProtocol.NAV_NEXT_TURN_DISTANCE, position("-5",1)))
    }

    @Test fun legacyImageMessageMatchesFieldNumbersAndIsNotParsedAsOldFlatMessage() {
        val nav = AaNavigation()
        val legacy = ProtoWriter().string(1,"Old road").enum(2,8).enum(3,2)
            .bytes(4, byteArrayOf(1,2,3)).int32(5,300).enum(6,1).toByteArray()
        val result = nav.accept(AaProtocol.NAV_LEGACY_TURN_IMAGE, legacy)!!
        assertEquals(8,result.maneuver)
        assertEquals("Old road",result.road)
        assertEquals(300,result.distanceMeters)
        assertNull(nav.accept(AaProtocol.NAV_LEGACY_TURN_IMAGE,
            ProtoWriter().enum(2,8).enum(3,9).toByteArray()))
    }

    @Test fun legacyFlatTurnDoesNotGuessDistanceMeters() {
        val nav = AaNavigation()
        val legacy = ProtoWriter().enum(1,8).int32(2,950).int32(3,950).enum(4,2).toByteArray()
        val result = nav.accept(AaProtocol.NAV_LEGACY_FLAT_TURN, legacy)!!
        assertEquals(8,result.maneuver)
        assertNull(result.distanceMeters)
        assertNull(nav.accept(AaProtocol.NAV_LEGACY_FLAT_TURN,
            ProtoWriter().int32(2,950).toByteArray()))
        assertEquals(0x8005,AaProtocol.NAV_LEGACY_FLAT_TURN)
    }

    @Test fun modernFlowRejectsLegacyAndClearsPreviousDistanceOnNewManeuver() {
        val nav = AaNavigation()
        nav.accept(AaProtocol.NAV_NOTIFICATION,notification(8))
        nav.accept(AaProtocol.NAV_NEXT_TURN_DISTANCE,position("300"))
        assertEquals(300,nav.accept(AaProtocol.NAV_NOTIFICATION,notification(8))?.distanceMeters)
        val changed=nav.accept(AaProtocol.NAV_NOTIFICATION,notification(7,"New road"))!!
        assertNull(changed.distanceMeters)
        assertNull(nav.accept(AaProtocol.NAV_LEGACY_FLAT_TURN,ProtoWriter().enum(1,8).toByteArray()))
        assertNull(nav.accept(AaProtocol.NAV_LEGACY_TURN_IMAGE,ProtoWriter().enum(2,8).toByteArray()))
    }

    @Test fun stateTransitionsAndDisconnectCannotInventARoute() {
        val nav = AaNavigation()
        nav.accept(AaProtocol.NAV_NOTIFICATION,notification(8))
        assertFalse(nav.accept(AaProtocol.NAV_STATUS,ProtoWriter().enum(1,2).toByteArray())!!.active)
        assertNull(nav.accept(AaProtocol.NAV_NEXT_TURN_DISTANCE,position("300")))
        assertNull(nav.accept(AaProtocol.NAV_NOTIFICATION,notification(8)))
        nav.accept(AaProtocol.NAV_STATUS,ProtoWriter().enum(1,1).toByteArray())
        assertTrue(nav.accept(AaProtocol.NAV_NOTIFICATION,notification(7))!!.active)
        val rerouting=nav.accept(AaProtocol.NAV_STATUS,ProtoWriter().enum(1,3).toByteArray())!!
        assertTrue(rerouting.active)
        assertEquals(0,rerouting.maneuver)
        assertNull(rerouting.distanceMeters)
        assertFalse(nav.reset().active)
    }

    @Test fun missingUnitsMalformedMessagesAndUnknownManeuversAreRejected() {
        val nav = AaNavigation()
        assertNull(nav.accept(AaProtocol.NAV_STATUS, byteArrayOf()))
        assertNull(nav.accept(AaProtocol.NAV_NOTIFICATION, byteArrayOf(10, 20, 10)))
        assertNull(nav.accept(AaProtocol.NAV_NOTIFICATION, notification(52)))
        val noUnit=ProtoWriter().message(1,ProtoWriter()
            .message(1,ProtoWriter().int32(1,300))).toByteArray()
        assertNull(nav.accept(AaProtocol.NAV_NEXT_TURN_DISTANCE,noUnit))
        assertNull(nav.accept(AaProtocol.NAV_NEXT_TURN_DISTANCE,position("-1",1)))
    }
}
