package app.ft.carplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayFlatWifiInfoInspectorTest {
    private val probe = CarPlayFlatWifiInfoInspector
    private fun p(id: Int, s: String) =
        Iap2ControlSessionCodec.Parameter(id, (s + "\u0000").toByteArray())
    private fun msg(vararg fields: Iap2ControlSessionCodec.Parameter) =
        Iap2ControlSessionCodec.Message(0x5703, fields.toList())

    @Test fun observedFlatWifiReplyIsOnlyShapeEvidenceNotIdentity() {
        val result = probe.inspect(msg(p(1,"PrivateToyotaSSID"),p(2,"PrivatePassword123")))
        assertEquals(CarPlayFlatWifiInfoInspector.Shape.STRUCTURE_OBSERVED_ONLY,result.shape)
        assertEquals(2,result.fieldCount)
        assertFalse(result.toString().contains("PrivateToyotaSSID"))
        assertFalse(result.toString().contains("PrivatePassword"))
    }

    @Test fun mixedDialectAndUnknownFieldAreNotAccepted() {
        val fields = arrayOf(p(1,"Toyota"),p(2,"abcdefgh"))
        assertEquals(CarPlayFlatWifiInfoInspector.Shape.NOT_FLAT_REPLY,
            probe.inspect(Iap2ControlSessionCodec.Message(0x4301,fields.toList())).shape)
        assertEquals(CarPlayFlatWifiInfoInspector.Shape.UNKNOWN_OR_DUPLICATE_FIELDS,
            probe.inspect(msg(*fields,p(3,"unverified"))).shape)
        assertEquals(CarPlayFlatWifiInfoInspector.Shape.UNKNOWN_OR_DUPLICATE_FIELDS,
            probe.inspect(msg(*fields,p(1,"duplicate"))).shape)
    }

    @Test fun missingAndMalformedCredentialsRemainUnverified() {
        assertEquals(CarPlayFlatWifiInfoInspector.Shape.MISSING_CREDENTIAL_FIELDS,
            probe.inspect(msg(p(1,"Toyota"))).shape)
        assertEquals(CarPlayFlatWifiInfoInspector.Shape.INVALID_FIELD_ENVELOPE,
            probe.inspect(msg(p(1,"Toyota"),p(2,"short"))).shape)
        assertEquals(CarPlayFlatWifiInfoInspector.Shape.INVALID_FIELD_ENVELOPE,
            probe.inspect(msg(Iap2ControlSessionCodec.Parameter(1,
                "Toyota".toByteArray()),p(2,"abcdefgh"))).shape)
    }
}
