package com.starwake.core.audio

/**
 * The exact length of an OGG Vorbis file from its headers: the sample rate of the identification
 * header and the granule position (= the stream's last sample) of the last page. Decoders do not all
 * honour the end trim of the last page, so the loaders cut what a decoder returns to [frames]; a loop
 * then keeps its exact period. Pure Kotlin; allocates nothing beyond the caller's arrays.
 */
object OggInfo {
    /** Sample rate from the first bytes of the file, 0 when not found. */
    fun rate(head: ByteArray): Int {
        for (i in 0 until head.size - 16) {
            if (head[i] == 1.toByte() && head[i + 1] == 'v'.code.toByte() && head[i + 2] == 'o'.code.toByte() &&
                head[i + 3] == 'r'.code.toByte() && head[i + 4] == 'b'.code.toByte() && head[i + 5] == 'i'.code.toByte() &&
                head[i + 6] == 's'.code.toByte()
            ) {
                // packet type 1, "vorbis", version (4 bytes), channels (1), sample rate (4, little endian)
                return le32(head, i + 12)
            }
        }
        return 0
    }

    /** Granule position of the last "OggS" page in the last bytes of the file, -1 when not found. */
    fun lastGranule(tail: ByteArray): Long {
        for (i in tail.size - 14 downTo 0) {
            if (tail[i] == 'O'.code.toByte() && tail[i + 1] == 'g'.code.toByte() && tail[i + 2] == 'g'.code.toByte() && tail[i + 3] == 'S'.code.toByte()) {
                return (le32(tail, i + 6).toLong() and 0xFFFFFFFFL) or (le32(tail, i + 10).toLong() shl 32)
            }
        }
        return -1L
    }

    /** Frames (samples per channel) of a whole file held in [bytes], -1 when unknown. */
    fun frames(bytes: ByteArray): Long {
        val head = if (bytes.size > 4096) bytes.copyOf(4096) else bytes
        val tail = if (bytes.size > 65536) bytes.copyOfRange(bytes.size - 65536, bytes.size) else bytes
        return if (rate(head) > 0) lastGranule(tail) else -1L
    }

    /** Seconds of a file from its first and last bytes, 0 when unknown. */
    fun seconds(head: ByteArray, tail: ByteArray): Float {
        val r = rate(head)
        val g = lastGranule(tail)
        return if (r > 0 && g > 0) (g.toDouble() / r).toFloat() else 0f
    }

    /** [pcm] cut to [frames] when the decoder returned more (an end trim it did not honour). */
    fun trim(pcm: Pcm, frames: Long): Pcm {
        if (frames <= 0 || pcm.frames <= frames) return pcm
        return Pcm(pcm.data.copyOf((frames * pcm.channels).toInt()), pcm.channels, pcm.rate)
    }

    private fun le32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)
}
