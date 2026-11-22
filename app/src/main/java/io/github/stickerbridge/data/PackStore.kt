package io.github.stickerbridge.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class StickerItem(val file: String, val emojis: List<String>)

data class StickerPack(
    val id: String,
    val name: String,
    val publisher: String,
    val tray: String,
    val animated: Boolean,
    val version: Int,
    val source: String,
    val stickers: List<StickerItem>,
)

/** Stores packs on disk: filesDir/packs/<id>/{pack.json, tray.png, 01.webp, ...} */
object PackStore {
    private const val META = "pack.json"

    fun root(ctx: Context) = File(ctx.filesDir, "packs").apply { mkdirs() }

    fun dir(ctx: Context, id: String) = File(root(ctx), id)

    @Synchronized
    fun all(ctx: Context): List<StickerPack> =
        root(ctx).listFiles().orEmpty()
            .filter { it.isDirectory }
            .sortedBy { it.lastModified() }
            .mapNotNull { read(File(it, META)) }

    @Synchronized
    fun get(ctx: Context, id: String): StickerPack? =
        if (id.contains('/') || id.contains("..")) null else read(File(dir(ctx, id), META))

    @Synchronized
    fun save(ctx: Context, pack: StickerPack) {
        val d = dir(ctx, pack.id).apply { mkdirs() }
        val json = JSONObject()
            .put("id", pack.id)
            .put("name", pack.name)
            .put("publisher", pack.publisher)
            .put("tray", pack.tray)
            .put("animated", pack.animated)
            .put("version", pack.version)
            .put("source", pack.source)
            .put("stickers", JSONArray().apply {
                pack.stickers.forEach {
                    put(JSONObject().put("file", it.file).put("emojis", JSONArray(it.emojis)))
                }
            })
        val tmp = File(d, "$META.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(File(d, META))
    }

    @Synchronized
    fun delete(ctx: Context, id: String) {
        dir(ctx, id).deleteRecursively()
    }

    private fun read(f: File): StickerPack? = try {
        val o = JSONObject(f.readText())
        val arr = o.getJSONArray("stickers")
        StickerPack(
            id = o.getString("id"),
            name = o.getString("name"),
            publisher = o.getString("publisher"),
            tray = o.getString("tray"),
            animated = o.getBoolean("animated"),
            version = o.optInt("version", 1),
            source = o.optString("source"),
            stickers = (0 until arr.length()).map { i ->
                val s = arr.getJSONObject(i)
                val e = s.getJSONArray("emojis")
                StickerItem(s.getString("file"), (0 until e.length()).map { e.getString(it) })
            },
        )
    } catch (e: Exception) {
        null
    }
}
