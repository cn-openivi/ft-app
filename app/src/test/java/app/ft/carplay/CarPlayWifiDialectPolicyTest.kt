package app.ft.carplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayWifiDialectPolicyTest {
    private val p = CarPlayWifiDialectPolicy

    private fun ids(vararg ids: Int): ByteArray {
        val bytes = ArrayList<Byte>()
        for (id in ids) {
            bytes.add((id ushr 8).toByte())
            bytes.add(id.toByte())
        }
        return bytes.toByteArray()
    }
    private fun identification(
        sent: ByteArray? = ids(0x4301),
        received: ByteArray? = ids(0x4300),
        extra: List<Iap2ControlSessionCodec.Parameter> = emptyList()
    ): Iap2ControlSessionCodec.Message {
        val fields = mutableListOf<Iap2ControlSessionCodec.Parameter>()
        if (sent != null) fields.add(Iap2ControlSessionCodec.Parameter(6, sent))
        if (received != null) fields.add(Iap2ControlSessionCodec.Parameter(7, received))
        fields.addAll(extra)
        return Iap2ControlSessionCodec.Message(0x1D01, fields)
    }

    @Test fun observedGroupedPairIsStillOnlyADialectHint() {
        val result = p.inspect(identification(
            sent = ids(0x1D02, 0x4301), received = ids(0x4300, 0xAA01)))
        assertEquals(CarPlayWifiDialectPolicy.Status.GROUPED_4300_4301_HINT, result.status)
        assertEquals(0x4300, result.phoneRequestMessageId)
        assertEquals(0x4301, result.expectedAccessoryReplyId)
        assertFalse(result.toString().contains("0x4300"))
    }

    @Test fun alternativeFlatWifiPairMustNeverBeParsedAsGrouped() {
        val result = p.inspect(identification(ids(0x5703), ids(0x5702)))
        assertEquals(CarPlayWifiDialectPolicy.Status.FLAT_5702_5703_HINT, result.status)
        assertEquals(0x5702, result.phoneRequestMessageId)
        assertEquals(0x5703, result.expectedAccessoryReplyId)
    }

    @Test fun ambiguousBothDialectsCannotAutoSelectOrSend() {
        val result = p.inspect(identification(
            ids(0x4301,0x5703), ids(0x4300,0x5702)))
        assertEquals(CarPlayWifiDialectPolicy.Status.AMBIGUOUS_BOTH_DIALECTS, result.status)
        assertNull(result.phoneRequestMessageId)
        assertNull(result.expectedAccessoryReplyId)
    }

    @Test fun incompleteAndUnrelatedCapabilitiesStayBlocked() {
        assertEquals(CarPlayWifiDialectPolicy.Status.NO_COMPLETE_ADVERTISED_PAIR,
            p.inspect(identification(ids(0x4301), ids(0x5702))).status)
        assertEquals(CarPlayWifiDialectPolicy.Status.MESSAGE_LISTS_MISSING,
            p.inspect(identification(received = null)).status)
        assertEquals(CarPlayWifiDialectPolicy.Status.NOT_IDENTIFICATION,
            p.inspect(Iap2ControlSessionCodec.Message(0x4301, emptyList())).status)
    }

    @Test fun malformedDuplicateOrWrongByteOrderNeverPromotes() {
        for (bad in listOf(byteArrayOf(), byteArrayOf(0x43),
            ids(0x4300,0x4300), ByteArray(258))) {
            assertEquals(CarPlayWifiDialectPolicy.Status.MALFORMED_OR_DUPLICATED_LISTS,
                p.inspect(identification(received = bad)).status)
        }
        assertEquals(CarPlayWifiDialectPolicy.Status.MALFORMED_OR_DUPLICATED_LISTS,
            p.inspect(identification(extra = listOf(
                Iap2ControlSessionCodec.Parameter(7, ids(0x5702))))).status)
        assertEquals(CarPlayWifiDialectPolicy.Status.NO_COMPLETE_ADVERTISED_PAIR,
            p.inspect(identification(sent = ids(0x0143),
                received = ids(0x0043))).status)
    }
}
