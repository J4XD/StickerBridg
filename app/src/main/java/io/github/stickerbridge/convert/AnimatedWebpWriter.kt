package io.github.stickerbridge.convert

import java.io.ByteArrayOutputStream

/**
 * Builds an animated WebP file in pure Kotlin.
 *
 * Android can only encode *still* WebP images (Bitmap.compress). An animated WebP is just a
 * RIFF container holding one ANMF chunk per frame, and each ANMF chunk wraps the same
 * VP8/VP8L (+ALPH) bitstream chunks a still WebP has. So we encode every frame as a still
 * WebP, lift its image chunks out and re-wrap them here. Spec:
 * https://developers.google.com/speed/webp/docs/riff_container
 */
class AnimatedWebpWriter(private val width: Int, private val height: Int) {

    private val frames = ByteArrayOutputStream()
    private var frameCount = 0

    val estimatedSize: Int get() = frames.size() + 64

    /** @param stillWebp a complete still WebP of exactly width x height pixels. */
    fun addFrame(stillWebp: ByteArray, durationMs: Int) {
        val image = extractImageChunks(stillWebp)
        val payload = ByteArrayOutputStream()
        payload.write24(0) // X offset / 2
        payload.write24(0) // Y offset / 2
        payload.write24(width - 1)
        payload.write24(height - 1)
        payload.write24(durationMs.coerceIn(8, 0xFFFFFF))
        payload.write(0x02) // flags: do not blend (each frame replaces canvas), no dispose
        payload.write(image)
        frames.writeChunk("ANMF", payload.toByteArray())
        frameCount++
    }

    fun build(): ByteArray {
        require(frameCount > 0) { "No frames" }
        val body = ByteArrayOutputStream()
        body.write("WEBP".toByteArray(Charsets.US_ASCII))

        val vp8x = ByteArrayOutputStream()
        vp8x.write(0x10 or 0x02) // alpha + animation flags
        vp8x.write(0); vp8x.write(0); vp8x.write(0)
        vp8x.write24(width - 1)
        vp8x.write24(height - 1)
        body.writeChunk("VP8X", vp8x.toByteArray())

        val anim = ByteArrayOutputStream()
        anim.write32(0) // background colour: transparent
        anim.write(0); anim.write(0) // loop count: 0 = forever
        body.writeChunk("ANIM", anim.toByteArray())

        frames.writeTo(body)

        val out = ByteArrayOutputStream(body.size() + 8)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write32(body.size())
        body.writeTo(out)
        return out.toByteArray()
    }

    companion object {
        /** Returns the ALPH / VP8 / VP8L chunks (with headers + padding) of a still WebP. */
        fun extractImageChunks(webp: ByteArray): ByteArray {
            require(
                webp.size > 20 &&
                    String(webp, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(webp, 8, 4, Charsets.US_ASCII) == "WEBP"
            ) { "Not a WebP image" }
            val out = ByteArrayOutputStream()
            var pos = 12
            while (pos + 8 <= webp.size) {
                val fourcc = String(webp, pos, 4, Charsets.US_ASCII)
                val size = readLe32(webp, pos + 4)
                val available = minOf(size, webp.size - pos - 8)
                if (fourcc == "ALPH" || fourcc == "VP8 " || fourcc == "VP8L") {
                    out.write(webp, pos, 8 + available)
                    if (available % 2 == 1) out.write(0)
                }
                pos += 8 + size + (size and 1)
            }
            require(out.size() > 0) { "WebP has no image data" }
            return out.toByteArray()
        }

        private fun readLe32(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or
                ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or
                ((b[o + 3].toInt() and 0xFF) shl 24)

        private fun ByteArrayOutputStream.write24(v: Int) {
            write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF)
        }

        private fun ByteArrayOutputStream.write32(v: Int) {
            write24(v); write((v shr 24) and 0xFF)
        }

        private fun ByteArrayOutputStream.writeChunk(fourcc: String, data: ByteArray) {
            write(fourcc.toByteArray(Charsets.US_ASCII))
            write32(data.size)
            write(data)
            if (data.size % 2 == 1) write(0)
        }
    }
}
