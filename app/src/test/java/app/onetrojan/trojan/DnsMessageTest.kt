package app.onetrojan.trojan

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DnsMessageTest {
    @Test
    fun `aaaa question gets successful empty response`() {
        val query = query(type = 28, includeOptRecord = true)

        val response = requireNotNull(DnsMessage.emptyIpv6Answer(query))

        assertEquals(0x8180, unsignedShort(response, 2))
        assertEquals(1, unsignedShort(response, 4))
        assertEquals(0, unsignedShort(response, 6))
        assertEquals(0, unsignedShort(response, 8))
        assertEquals(0, unsignedShort(response, 10))
        assertArrayEquals(query.copyOf(response.size).apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            fill(0, 6, 12)
        }, response)
    }

    @Test
    fun `ipv4 question is forwarded`() {
        assertNull(DnsMessage.emptyIpv6Answer(query(type = 1)))
    }

    @Test
    fun `malformed question is forwarded`() {
        assertNull(DnsMessage.emptyIpv6Answer(ByteArray(12)))
    }

    private fun query(type: Int, includeOptRecord: Boolean = false): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeShort(0x1234)
                output.writeShort(0x0100)
                output.writeShort(1)
                output.writeShort(0)
                output.writeShort(0)
                output.writeShort(if (includeOptRecord) 1 else 0)
                for (label in listOf("play", "google", "com")) {
                    output.writeByte(label.length)
                    output.writeBytes(label)
                }
                output.writeByte(0)
                output.writeShort(type)
                output.writeShort(1)
                if (includeOptRecord) {
                    output.writeByte(0)
                    output.writeShort(41)
                    output.writeShort(1232)
                    output.writeInt(0)
                    output.writeShort(0)
                }
            }
        }.toByteArray()

    private fun unsignedShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
}
