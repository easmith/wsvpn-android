package com.doridian.wsvpn.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class PacketFragmenter(private val maxPayloadSize: Int) {

    private val nextPacketId = AtomicInteger(0)

    companion object {
        const val UNFRAGMENTED_HEADER: Byte = 0x80.toByte()
        const val LAST_FRAGMENT_FLAG: Int = 0x80
        const val FRAGMENT_INDEX_MASK: Int = 0x7F
        const val FRAGMENT_HEADER_SIZE = 5 // 1 byte flags + 4 bytes packet ID
    }

    fun fragment(packet: ByteArray): List<ByteArray> {
        // Check if packet fits in single fragment
        if (packet.size + 1 <= maxPayloadSize) {
            val buf = ByteArray(1 + packet.size)
            buf[0] = UNFRAGMENTED_HEADER
            System.arraycopy(packet, 0, buf, 1, packet.size)
            return listOf(buf)
        }

        val dataPerFragment = maxPayloadSize - FRAGMENT_HEADER_SIZE
        val packetId = nextPacketId.getAndIncrement()
        val fragments = mutableListOf<ByteArray>()
        var offset = 0
        var fragmentIndex = 0

        while (offset < packet.size) {
            val remaining = packet.size - offset
            val chunkSize = minOf(remaining, dataPerFragment)
            val isLast = offset + chunkSize >= packet.size

            val fragment = ByteArray(FRAGMENT_HEADER_SIZE + chunkSize)
            fragment[0] = (fragmentIndex and FRAGMENT_INDEX_MASK or (if (isLast) LAST_FRAGMENT_FLAG else 0)).toByte()

            ByteBuffer.wrap(fragment, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(packetId)
            System.arraycopy(packet, offset, fragment, FRAGMENT_HEADER_SIZE, chunkSize)

            fragments.add(fragment)
            offset += chunkSize
            fragmentIndex++
        }

        return fragments
    }
}

class PacketDefragmenter {

    private data class FragmentKey(val packetId: Int)

    private class PartialPacket {
        val fragments = ConcurrentHashMap<Int, ByteArray>()
        var lastFragmentIndex: Int = -1
        val createdAt: Long = System.currentTimeMillis()
    }

    private val partials = ConcurrentHashMap<FragmentKey, PartialPacket>()

    companion object {
        const val FRAGMENT_TIMEOUT_MS = 30_000L
    }

    fun processMessage(data: ByteArray): ByteArray? {
        if (data.isEmpty()) return null

        val firstByte = data[0].toInt() and 0xFF

        // Unfragmented packet
        if (firstByte == 0x80 && data.size > 1) {
            // Check: if this is truly unfragmented (index=0, last=true), return payload
            // But we need to distinguish from "last fragment with index 0"
            // Unfragmented: 0x80 prefix, no packet ID
            // Actually: 0x80 = index 0 | LAST flag. For unfragmented, there's no 4-byte packet ID.
            // The protocol says unfragmented = [0x80][PAYLOAD]
            // Fragmented = [flags][packetId 4 bytes][PAYLOAD]
            // We distinguish by: unfragmented has no packet ID header
            // But both start with 0x80... The difference is that unfragmented is [0x80][payload]
            // and fragmented last-first is [0x80][4-byte-id][payload]
            // We can't distinguish these without context.
            // Looking at the Go code more carefully:
            // If fragmentation is enabled, ALL packets use the fragment format.
            // Unfragmented = single fragment with index=0 and last=true = 0x80 + 4-byte packet ID + payload
            // So actually there's no special "unfragmented without ID" case when fragmentation is on.

            // When fragmentation is OFF, raw packets are sent without any header.
            // When fragmentation is ON, even single packets have the 5-byte header.

            // Let's handle it properly:
            return processFragment(data)
        }

        return processFragment(data)
    }

    private fun processFragment(data: ByteArray): ByteArray? {
        if (data.size < PacketFragmenter.FRAGMENT_HEADER_SIZE) return null

        val firstByte = data[0].toInt() and 0xFF
        val fragmentIndex = firstByte and PacketFragmenter.FRAGMENT_INDEX_MASK
        val isLast = (firstByte and PacketFragmenter.LAST_FRAGMENT_FLAG) != 0

        val packetId = ByteBuffer.wrap(data, 1, 4).order(ByteOrder.BIG_ENDIAN).int
        val payload = data.copyOfRange(PacketFragmenter.FRAGMENT_HEADER_SIZE, data.size)

        val key = FragmentKey(packetId)

        // Single fragment packet (index 0 + last flag)
        if (fragmentIndex == 0 && isLast) {
            return payload
        }

        val partial = partials.getOrPut(key) { PartialPacket() }
        partial.fragments[fragmentIndex] = payload
        if (isLast) {
            partial.lastFragmentIndex = fragmentIndex
        }

        // Check if complete
        if (partial.lastFragmentIndex >= 0 && partial.fragments.size == partial.lastFragmentIndex + 1) {
            partials.remove(key)
            // Reassemble
            var totalSize = 0
            for (i in 0..partial.lastFragmentIndex) {
                totalSize += (partial.fragments[i]?.size ?: return null)
            }
            val result = ByteArray(totalSize)
            var offset = 0
            for (i in 0..partial.lastFragmentIndex) {
                val frag = partial.fragments[i] ?: return null
                System.arraycopy(frag, 0, result, offset, frag.size)
                offset += frag.size
            }
            return result
        }

        // Cleanup old partials
        cleanupStale()

        return null
    }

    fun processRawPacket(data: ByteArray): ByteArray = data

    private fun cleanupStale() {
        val now = System.currentTimeMillis()
        partials.entries.removeAll { now - it.value.createdAt > FRAGMENT_TIMEOUT_MS }
    }
}
