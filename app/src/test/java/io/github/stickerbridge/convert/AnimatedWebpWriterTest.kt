package io.github.stickerbridge.convert

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Builds animated WebPs from still frames; set -Dwebp.dir to a folder of f{N}_{kind}.webp frames. */
class AnimatedWebpWriterTest {
    @Test
    fun buildsAnimatedWebp() {
        val dir = System.getProperty("webp.dir")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        for (kind in listOf("lossy", "lossless", "opaque")) {
            val w = AnimatedWebpWriter(512, 512)
            for (i in 0 until 4) w.addFrame(File(dir, "f${i}_$kind.webp").readBytes(), 50)
            val out = w.build()
            assertEquals("RIFF", String(out, 0, 4))
            assertEquals(out.size - 8, (out[4].toInt() and 0xFF) or ((out[5].toInt() and 0xFF) shl 8) or
                ((out[6].toInt() and 0xFF) shl 16) or ((out[7].toInt() and 0xFF) shl 24))
            File(dir, "anim_$kind.webp").writeBytes(out)
        }
    }
}
