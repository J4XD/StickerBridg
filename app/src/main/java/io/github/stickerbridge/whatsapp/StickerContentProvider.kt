package io.github.stickerbridge.whatsapp

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.github.stickerbridge.BuildConfig
import io.github.stickerbridge.data.PackStore
import io.github.stickerbridge.data.StickerPack
import java.io.File
import java.io.FileNotFoundException

/**
 * The bridge WhatsApp reads from. Implements the contract of WhatsApp's official
 * third-party sticker API (github.com/WhatsApp/stickers).
 */
class StickerContentProvider : ContentProvider() {

    companion object {
        val AUTHORITY: String = BuildConfig.APPLICATION_ID + ".stickercontentprovider"

        private const val METADATA = "metadata"
        private const val STICKERS = "stickers"
        private const val STICKERS_ASSET = "stickers_asset"

        private const val MATCH_ALL = 1
        private const val MATCH_ONE = 2
        private const val MATCH_STICKERS = 3
        private const val MATCH_ASSET = 4

        private val PACK_COLUMNS = arrayOf(
            "sticker_pack_identifier",
            "sticker_pack_name",
            "sticker_pack_publisher",
            "sticker_pack_icon",
            "android_play_store_link",
            "ios_app_download_link",
            "sticker_pack_publisher_email",
            "sticker_pack_publisher_website",
            "sticker_pack_privacy_policy_website",
            "sticker_pack_license_agreement_website",
            "image_data_version",
            "whatsapp_will_not_cache_stickers",
            "animated_sticker_pack",
        )

        private val STICKER_COLUMNS = arrayOf(
            "sticker_file_name",
            "sticker_emoji",
            "sticker_accessibility_text",
        )
    }

    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(AUTHORITY, METADATA, MATCH_ALL)
        addURI(AUTHORITY, "$METADATA/*", MATCH_ONE)
        addURI(AUTHORITY, "$STICKERS/*", MATCH_STICKERS)
        addURI(AUTHORITY, "$STICKERS_ASSET/*/*", MATCH_ASSET)
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor {
        val ctx = requireNotNull(context)
        val cursor = when (matcher.match(uri)) {
            MATCH_ALL -> packCursor(PackStore.all(ctx))
            MATCH_ONE -> packCursor(listOfNotNull(PackStore.get(ctx, uri.lastPathSegment.orEmpty())))
            MATCH_STICKERS -> {
                val c = MatrixCursor(STICKER_COLUMNS)
                PackStore.get(ctx, uri.lastPathSegment.orEmpty())?.stickers?.forEach {
                    c.addRow(arrayOf<Any?>(it.file, it.emojis.joinToString(","), null))
                }
                c
            }
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
        cursor.setNotificationUri(ctx.contentResolver, uri)
        return cursor
    }

    private fun packCursor(packs: List<StickerPack>): Cursor {
        val c = MatrixCursor(PACK_COLUMNS)
        packs.forEach { p ->
            c.addRow(
                arrayOf<Any?>(
                    p.id, p.name, p.publisher, p.tray,
                    "", "", "", "", "", "",
                    p.version.toString(),
                    0,
                    if (p.animated) 1 else 0,
                )
            )
        }
        return c
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        if (matcher.match(uri) != MATCH_ASSET) throw FileNotFoundException(uri.toString())
        val segments = uri.pathSegments
        val id = segments[segments.size - 2]
        val fileName = segments.last()
        val ctx = requireNotNull(context)
        val pack = PackStore.get(ctx, id) ?: throw FileNotFoundException("No pack $id")
        if (fileName != pack.tray && pack.stickers.none { it.file == fileName }) {
            throw FileNotFoundException("No file $fileName in $id")
        }
        val file = File(PackStore.dir(ctx, id), fileName)
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, file.length())
    }

    override fun getType(uri: Uri): String = when (matcher.match(uri)) {
        MATCH_ALL -> "vnd.android.cursor.dir/vnd.$AUTHORITY.$METADATA"
        MATCH_ONE -> "vnd.android.cursor.item/vnd.$AUTHORITY.$METADATA"
        MATCH_STICKERS -> "vnd.android.cursor.dir/vnd.$AUTHORITY.$STICKERS"
        MATCH_ASSET -> if (uri.lastPathSegment.orEmpty().endsWith(".png")) "image/png" else "image/webp"
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()
}
