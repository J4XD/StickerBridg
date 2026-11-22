package io.github.stickerbridge.telegram

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class TgSticker(
    val fileId: String,
    val emoji: String?,
    val isAnimated: Boolean,
    val isVideo: Boolean,
)

data class TgStickerSet(
    val name: String,
    val title: String,
    val stickers: List<TgSticker>,
)

class TelegramException(message: String) : IOException(message)

/**
 * Minimal client for the official Telegram Bot API.
 * Only needs a bot token (free, from @BotFather) to read any public sticker pack.
 */
class TelegramApi(private val token: String) {

    private val base = "https://api.telegram.org"

    fun getStickerSet(name: String): TgStickerSet {
        val result = call("getStickerSet", mapOf("name" to name))
        val arr = result.getJSONArray("stickers")
        val stickers = (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            TgSticker(
                fileId = s.getString("file_id"),
                emoji = s.optString("emoji").takeIf { it.isNotBlank() },
                isAnimated = s.optBoolean("is_animated"),
                isVideo = s.optBoolean("is_video"),
            )
        }
        return TgStickerSet(
            name = result.getString("name"),
            title = result.optString("title", name),
            stickers = stickers,
        )
    }

    fun downloadFile(fileId: String, dest: File) {
        val path = call("getFile", mapOf("file_id" to fileId)).getString("file_path")
        val conn = open("$base/file/bot$token/$path")
        try {
            if (conn.responseCode !in 200..299) {
                throw TelegramException("Download failed (HTTP ${conn.responseCode})")
            }
            conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
    }

    private fun call(method: String, params: Map<String, String>): JSONObject {
        val query = params.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        val conn = open("$base/bot$token/$method?$query")
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                throw TelegramException("Telegram error (HTTP $code)")
            }
            if (!json.optBoolean("ok")) {
                val desc = json.optString("description", "HTTP $code")
                throw TelegramException(
                    when {
                        code == 401 || desc.contains("Unauthorized", true) ->
                            "Bot token is invalid. Create one with @BotFather."
                        desc.contains("STICKERSET_INVALID", true) ->
                            "Sticker pack not found. Check the link."
                        else -> desc
                    }
                )
            }
            return json.getJSONObject("result")
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
        }

    companion object {
        private val linkRegex =
            Regex("""(?:t(?:elegram)?\.me/(?:addstickers|addemoji)/|tg://(?:addstickers|addemoji)\?set=)([A-Za-z0-9_]+)""")
        private val nameRegex = Regex("^[A-Za-z0-9_]+$")

        /** Accepts t.me/addstickers/NAME, tg://addstickers?set=NAME or just NAME. */
        fun parseSetName(input: String): String? {
            val s = input.trim()
            linkRegex.find(s)?.let { return it.groupValues[1] }
            return s.takeIf { nameRegex.matches(it) }
        }
    }
}
