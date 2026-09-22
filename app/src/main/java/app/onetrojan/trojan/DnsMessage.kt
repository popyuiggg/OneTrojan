package app.onetrojan.trojan

internal object DnsMessage {
    private const val HEADER_SIZE = 12
    private const val TYPE_AAAA = 28
    private const val CLASS_IN = 1

    /**
     * Returns a successful DNS response with the original question and no answers when [query]
     * is a single Internet-class AAAA question. Other messages return null and are forwarded.
     */
    fun emptyIpv6Answer(query: ByteArray): ByteArray? {
        if (query.size < HEADER_SIZE || unsignedShort(query, 4) != 1) return null

        val questionEnd = questionEnd(query) ?: return null
        if (questionEnd + 4 > query.size) return null
        if (unsignedShort(query, questionEnd) != TYPE_AAAA) return null
        if (unsignedShort(query, questionEnd + 2) != CLASS_IN) return null

        val response = query.copyOf(questionEnd + 4)
        val requestFlags = unsignedShort(query, 2)
        val responseFlags = 0x8000 or // QR: response
            (requestFlags and 0x7900) or // opcode and recursion desired
            0x0080 // recursion available
        putUnsignedShort(response, 2, responseFlags)
        putUnsignedShort(response, 4, 1)
        putUnsignedShort(response, 6, 0)
        putUnsignedShort(response, 8, 0)
        putUnsignedShort(response, 10, 0)
        return response
    }

    private fun questionEnd(message: ByteArray): Int? {
        var cursor = HEADER_SIZE
        var labels = 0
        while (cursor < message.size) {
            val length = message[cursor].toInt() and 0xff
            when {
                length == 0 -> return cursor + 1
                length and 0xc0 == 0xc0 -> {
                    if (cursor + 1 >= message.size) return null
                    return cursor + 2
                }
                length > 63 || cursor + 1 + length > message.size -> return null
                else -> {
                    cursor += 1 + length
                    if (++labels > 127) return null
                }
            }
        }
        return null
    }

    private fun unsignedShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun putUnsignedShort(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }
}
