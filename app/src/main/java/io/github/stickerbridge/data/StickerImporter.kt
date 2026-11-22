package io.github.stickerbridge.data

import android.content.Context
import io.github.stickerbridge.convert.Converted
import io.github.stickerbridge.convert.StickerConverter
import io.github.stickerbridge.telegram.TelegramApi
import io.github.stickerbridge.telegram.TgSticker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

data class ImportResult(val packs: List<StickerPack>, val skipped: Int, val total: Int)

/**
 * Downloads a Telegram sticker set, converts every sticker and saves it as one or more
 * WhatsApp packs. WhatsApp rules: 3–30 stickers per pack, and a pack is either all static
 * or all animated — so big / mixed Telegram sets are split automatically.
 */
class StickerImporter(private val ctx: Context, private val api: TelegramApi) {

    private class Staged(val webp: File, val tray: File, val emoji: String)

    suspend fun import(
        setName: String,
        includeVideo: Boolean,
        progress: suspend (message: String, done: Int, total: Int) -> Unit,
    ): ImportResult = withContext(Dispatchers.IO) {
        progress("Fetching pack info…", 0, 0)
        val set = api.getStickerSet(setName)
        val work = File(ctx.cacheDir, "import_${set.name}").apply { deleteRecursively(); mkdirs() }

        val static = ArrayList<Staged>()
        val animated = ArrayList<Staged>()
        var skipped = 0
        val total = set.stickers.size

        try {
            set.stickers.forEachIndexed { i, sticker ->
                coroutineContext.ensureActive()
                progress("Converting sticker ${i + 1} of $total…", i, total)
                if (sticker.isVideo && !includeVideo) {
                    skipped++
                    return@forEachIndexed
                }
                val staged = try {
                    convertOne(sticker, i, work)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    null
                }
                when {
                    staged == null -> skipped++
                    sticker.isAnimated || sticker.isVideo -> animated += staged
                    else -> static += staged
                }
            }
            progress("Saving packs…", total, total)

            val mixed = static.isNotEmpty() && animated.isNotEmpty()
            val packs = buildPacks(set.name, set.title, static, false, mixed) +
                buildPacks(set.name, set.title, animated, true, mixed)
            if (packs.isEmpty()) {
                throw IllegalStateException(
                    if (set.stickers.all { it.isVideo } && !includeVideo)
                        "This pack only has video stickers. Enable \"video stickers\" and try again."
                    else "None of the stickers could be converted."
                )
            }
            ImportResult(packs, skipped, total)
        } finally {
            work.deleteRecursively()
        }
    }

    private fun convertOne(sticker: TgSticker, index: Int, work: File): Staged? {
        val ext = when {
            sticker.isAnimated -> "tgs"
            sticker.isVideo -> "webm"
            else -> "webp"
        }
        val raw = File(work, "raw_$index.$ext")
        api.downloadFile(sticker.fileId, raw)
        val converted: Converted = when {
            sticker.isAnimated -> StickerConverter.convertTgs(raw)
            sticker.isVideo -> StickerConverter.convertVideo(raw)
            else -> StickerConverter.convertStatic(raw)
        } ?: return null
        raw.delete()
        val webp = File(work, "s_$index.webp").apply { writeBytes(converted.webp) }
        val tray = File(work, "t_$index.png").apply { writeBytes(StickerConverter.trayPng(converted.preview)) }
        converted.preview.recycle()
        return Staged(webp, tray, sticker.emoji ?: DEFAULT_EMOJI)
    }

    private fun buildPacks(
        setName: String,
        title: String,
        items: List<Staged>,
        animated: Boolean,
        mixed: Boolean,
    ): List<StickerPack> {
        val prefix = "tg_${setName}_${if (animated) "a" else "s"}"
        // Remove packs from an earlier import of the same set, remembering their version
        // so WhatsApp notices the content changed.
        val old = PackStore.all(ctx).filter { it.source == setName && it.animated == animated }
        val version = (old.maxOfOrNull { it.version } ?: 0) + 1
        old.forEach { PackStore.delete(ctx, it.id) }
        if (items.isEmpty()) return emptyList()

        val chunks = splitEvenly(items)
        return chunks.mapIndexed { idx, chunk ->
            val id = "$prefix${idx + 1}".take(128)
            val dir = PackStore.dir(ctx, id).apply { deleteRecursively(); mkdirs() }

            // WhatsApp needs at least 3 stickers: repeat the last ones if the set is tiny.
            val filled = chunk.toMutableList()
            while (filled.size < MIN_PER_PACK) filled += chunk[filled.size % chunk.size]

            val stickers = filled.mapIndexed { n, s ->
                val name = "%02d.webp".format(n + 1)
                s.webp.copyTo(File(dir, name), overwrite = true)
                StickerItem(name, listOf(s.emoji))
            }
            chunk.first().tray.copyTo(File(dir, TRAY), overwrite = true)

            var name = title
            if (mixed && animated) name += " (animated)"
            if (chunks.size > 1) name += " ${idx + 1}/${chunks.size}"

            StickerPack(
                id = id,
                name = name.take(128),
                publisher = "StickerBridge",
                tray = TRAY,
                animated = animated,
                version = version,
                source = setName,
                stickers = stickers,
            ).also { PackStore.save(ctx, it) }
        }
    }

    /** e.g. 61 stickers -> 21 + 20 + 20 instead of 30 + 30 + 1. */
    private fun <T> splitEvenly(items: List<T>): List<List<T>> {
        val packs = (items.size + MAX_PER_PACK - 1) / MAX_PER_PACK
        val result = ArrayList<List<T>>()
        var start = 0
        for (p in 0 until packs) {
            val size = items.size / packs + if (p < items.size % packs) 1 else 0
            result += items.subList(start, start + size)
            start += size
        }
        return result
    }

    companion object {
        const val MAX_PER_PACK = 30
        const val MIN_PER_PACK = 3
        const val TRAY = "tray.png"
        const val DEFAULT_EMOJI = "🙂" // 🙂
    }
}
