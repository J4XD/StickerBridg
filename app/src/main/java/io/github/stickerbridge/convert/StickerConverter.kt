package io.github.stickerbridge.convert

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.os.Build
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

/** A converted sticker: WhatsApp-ready WebP bytes plus a small preview for the tray icon. */
class Converted(val webp: ByteArray, val preview: Bitmap)

/**
 * Converts Telegram stickers into files that pass WhatsApp's sticker validation:
 *  - 512x512 WebP
 *  - static  <= 100 KB
 *  - animated <= 500 KB, frame duration >= 8 ms, total <= 10 s
 *  - tray icon 96x96 PNG <= 50 KB
 */
object StickerConverter {
    const val SIZE = 512
    const val TRAY_SIZE = 96
    private const val STATIC_MAX = 100 * 1024
    private const val ANIMATED_MAX = 500 * 1024
    private const val MAX_DURATION_MS = 10_000L

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // ---------- static (.webp / .png) ----------

    fun convertStatic(src: File): Converted? {
        val decoded = BitmapFactory.decodeFile(src.path) ?: return null
        val canvasBmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        drawFitted(decoded, Canvas(canvasBmp))
        decoded.recycle()
        try {
            for (q in intArrayOf(95, 85, 75, 60, 45, 30, 15)) {
                val bytes = compressWebp(canvasBmp, q)
                if (bytes.size <= STATIC_MAX) return Converted(bytes, preview(canvasBmp))
            }
            return null
        } finally {
            canvasBmp.recycle()
        }
    }

    // ---------- animated (.tgs Lottie) ----------

    fun convertTgs(src: File): Converted? = encodeAnimated(LottieSource(src))

    // ---------- video (.webm) — experimental ----------

    fun convertVideo(src: File): Converted? {
        val source = VideoSource(src)
        return try {
            if (source.isEmpty) null else encodeAnimated(source)
        } finally {
            source.release()
        }
    }

    // ---------- tray icon ----------

    fun trayPng(preview: Bitmap): ByteArray {
        val bmp = if (preview.width == TRAY_SIZE && preview.height == TRAY_SIZE) preview
        else Bitmap.createScaledBitmap(preview, TRAY_SIZE, TRAY_SIZE, true)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    // ---------- internals ----------

    private interface FrameSource {
        val durationMs: Long
        fun render(timeMs: Long, canvas: Canvas)
    }

    private class LottieSource(file: File) : FrameSource {
        private val drawable = LottieDrawable()
        override val durationMs: Long

        init {
            // .tgs = gzip-compressed Lottie JSON
            val result = LottieCompositionFactory.fromJsonInputStreamSync(
                GZIPInputStream(FileInputStream(file)), null
            )
            val composition = result.value
                ?: throw IOException("Invalid animated sticker", result.exception)
            drawable.setComposition(composition)
            @Suppress("DEPRECATION")
            drawable.enableMergePathsForKitKatAndAbove(true)
            drawable.setBounds(0, 0, SIZE, SIZE)
            durationMs = composition.duration.toLong()
        }

        override fun render(timeMs: Long, canvas: Canvas) {
            drawable.progress = if (durationMs <= 0) 0f
            else (timeMs.toFloat() / durationMs).coerceIn(0f, 1f)
            drawable.draw(canvas)
        }
    }

    /**
     * Telegram video stickers are VP9 WebM with an alpha channel. Android's decoders drop the
     * alpha, so the background may come out black. That's why this is marked experimental.
     */
    private class VideoSource(file: File) : FrameSource {
        private val stepMs = 1000L / 15
        private val frames = ArrayList<Bitmap>()
        override val durationMs: Long
        val isEmpty get() = frames.isEmpty()

        init {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(file.path)
                durationMs = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 3000L).coerceAtMost(MAX_DURATION_MS)
                var t = 0L
                while (t < durationMs) {
                    mmr.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST)?.let { f ->
                        val scale = minOf(SIZE.toFloat() / f.width, SIZE.toFloat() / f.height)
                        val w = (f.width * scale).toInt().coerceAtLeast(1)
                        val h = (f.height * scale).toInt().coerceAtLeast(1)
                        val scaled = Bitmap.createScaledBitmap(f, w, h, true)
                        if (scaled !== f) f.recycle()
                        frames.add(scaled)
                    }
                    t += stepMs
                }
            } finally {
                mmr.release()
            }
        }

        override fun render(timeMs: Long, canvas: Canvas) {
            if (frames.isEmpty()) return
            val idx = (timeMs / stepMs).toInt().coerceIn(0, frames.size - 1)
            drawFitted(frames[idx], canvas)
        }

        fun release() = frames.forEach { it.recycle() }
    }

    /** Tries progressively lower fps / quality until the file fits WhatsApp's 500 KB limit. */
    private fun encodeAnimated(source: FrameSource): Converted? {
        val duration = source.durationMs.coerceIn(100L, MAX_DURATION_MS)
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        var preview: Bitmap? = null
        val attempts = listOf(25 to 80, 20 to 70, 20 to 50, 15 to 45, 12 to 35, 10 to 25, 8 to 15)
        try {
            for ((fps, quality) in attempts) {
                val frameMs = 1000 / fps
                val count = (duration / frameMs).toInt().coerceIn(1, (MAX_DURATION_MS / frameMs).toInt())
                val writer = AnimatedWebpWriter(SIZE, SIZE)
                var tooBig = false
                for (i in 0 until count) {
                    bmp.eraseColor(Color.TRANSPARENT)
                    source.render(i.toLong() * frameMs, canvas)
                    if (preview == null && i == count / 2) preview = preview(bmp)
                    writer.addFrame(compressWebp(bmp, quality), frameMs)
                    if (writer.estimatedSize > ANIMATED_MAX) {
                        tooBig = true
                        break
                    }
                }
                if (tooBig) continue
                val out = writer.build()
                if (out.size <= ANIMATED_MAX) {
                    return Converted(out, preview ?: preview(bmp))
                }
            }
            return null
        } finally {
            bmp.recycle()
        }
    }

    private fun drawFitted(src: Bitmap, canvas: Canvas) {
        val scale = minOf(SIZE.toFloat() / src.width, SIZE.toFloat() / src.height)
        val w = src.width * scale
        val h = src.height * scale
        val left = (SIZE - w) / 2f
        val top = (SIZE - h) / 2f
        canvas.drawBitmap(src, null, RectF(left, top, left + w, top + h), paint)
    }

    private fun preview(bmp: Bitmap): Bitmap =
        Bitmap.createScaledBitmap(bmp, TRAY_SIZE, TRAY_SIZE, true)

    private fun compressWebp(bmp: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
        else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        bmp.compress(format, quality.coerceAtMost(99), out)
        return out.toByteArray()
    }
}
