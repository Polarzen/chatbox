package dev.polarzen.chatbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidHeartPacketTest {
    @Test fun unsignedEightAndSixteenBitRatesInLittleEndianOrder() {
        assertEquals(200, androidHeartBpm(byteArrayOf(0, 200.toByte())))
        assertEquals(259, androidHeartBpm(byteArrayOf(1, 3, 1)))
        assertEquals(65535, androidHeartBpm(byteArrayOf(1, -1, -1)))
        assertNull(androidHeartBpm(byteArrayOf(0, 0)))
        assertNull(androidHeartBpm(byteArrayOf(1, 0, 0)))
    }

    @Test fun contactIsRequiredOnlyWhenSensingIsSupported() {
        assertEquals(75, androidHeartBpm(byteArrayOf(2, 75)))
        assertNull(androidHeartBpm(byteArrayOf(4, 75)))
        assertEquals(75, androidHeartBpm(byteArrayOf(6, 75)))
    }

    @Test fun energyAndWideRateMustBeComplete() {
        val packet = byteArrayOf(9, 3, 1, 0, 0)
        for (size in 0 until packet.size) assertNull(androidHeartBpm(packet.copyOf(size)))
        assertEquals(259, androidHeartBpm(packet))
    }

    @Test fun rrFlagRequiresOneOrMoreWholeIntervalsAfterAnyEnergyField() {
        val packet = byteArrayOf(31, 3, 1, 0, 0, 1, 0, 2, 0)
        for (size in 0..6) assertNull(androidHeartBpm(packet.copyOf(size)))
        assertEquals(259, androidHeartBpm(packet.copyOf(7)))
        assertNull(androidHeartBpm(packet.copyOf(8)))
        assertEquals(259, androidHeartBpm(packet))
        assertNull(androidHeartBpm(byteArrayOf(16, 81)))
        assertEquals(81, androidHeartBpm(byteArrayOf(16, 81, 0, 3)))
    }
}
