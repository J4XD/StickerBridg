package io.github.stickerbridge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import io.github.stickerbridge.data.PackStore
import io.github.stickerbridge.data.StickerImporter
import io.github.stickerbridge.data.StickerPack
import io.github.stickerbridge.telegram.TelegramApi
import io.github.stickerbridge.whatsapp.StickerContentProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var tokenInput: TextInputEditText
    private lateinit var linkInput: TextInputEditText
    private lateinit var videoCheck: CheckBox
    private lateinit var importButton: Button
    private lateinit var statusText: TextView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var packsContainer: LinearLayout
    private lateinit var emptyText: TextView

    private var importJob: Job? = null

    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private val addToWhatsApp =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            when (result.resultCode) {
                Activity.RESULT_OK -> toast(getString(R.string.added_ok))
                else -> result.data?.getStringExtra("validation_error")?.let {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.whatsapp_rejected)
                        .setMessage(it)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        tokenInput = findViewById(R.id.tokenInput)
        linkInput = findViewById(R.id.linkInput)
        videoCheck = findViewById(R.id.videoCheck)
        importButton = findViewById(R.id.importButton)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        packsContainer = findViewById(R.id.packsContainer)
        emptyText = findViewById(R.id.emptyText)

        tokenInput.setText(prefs.getString("token", ""))
        videoCheck.isChecked = prefs.getBoolean("video", false)
        importButton.setOnClickListener { startImport() }
        findViewById<View>(R.id.helpButton).setOnClickListener { showHelp() }

        handleIncoming(intent)
        refreshPacks()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /** Lets users "Share" a pack link from Telegram straight into the app. */
    private fun handleIncoming(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        TelegramApi.parseSetName(text)?.let { linkInput.setText("https://t.me/addstickers/$it") }
    }

    private fun startImport() {
        if (importJob?.isActive == true) return
        val token = tokenInput.text?.toString()?.trim().orEmpty()
        val setName = TelegramApi.parseSetName(linkInput.text?.toString().orEmpty())
        if (token.isEmpty()) {
            tokenInput.error = getString(R.string.err_token)
            return
        }
        if (setName == null) {
            linkInput.error = getString(R.string.err_link)
            return
        }
        prefs.edit().putString("token", token).putBoolean("video", videoCheck.isChecked).apply()

        setBusy(true)
        importJob = lifecycleScope.launch {
            try {
                val result = StickerImporter(applicationContext, TelegramApi(token))
                    .import(setName, videoCheck.isChecked) { msg, done, total ->
                        withContext(Dispatchers.Main) {
                            statusText.text = msg
                            if (total > 0) {
                                progressBar.isIndeterminate = false
                                progressBar.max = total
                                progressBar.setProgressCompat(done, true)
                            } else {
                                progressBar.isIndeterminate = true
                            }
                        }
                    }
                val stickers = result.total - result.skipped
                statusText.text = buildString {
                    append(getString(R.string.import_done, stickers, result.packs.size))
                    if (result.skipped > 0) append("\n").append(getString(R.string.import_skipped, result.skipped))
                }
                linkInput.setText("")
                refreshPacks()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                statusText.text = getString(R.string.import_failed, e.message ?: e.javaClass.simpleName)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        importButton.isEnabled = !busy
        progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) {
            progressBar.isIndeterminate = true
            statusText.visibility = View.VISIBLE
        }
    }

    private fun refreshPacks() {
        lifecycleScope.launch {
            val packs = withContext(Dispatchers.IO) { PackStore.all(applicationContext) }
            packsContainer.removeAllViews()
            emptyText.visibility = if (packs.isEmpty()) View.VISIBLE else View.GONE
            val inflater = LayoutInflater.from(this@MainActivity)
            packs.reversed().forEach { pack ->
                val row = inflater.inflate(R.layout.item_pack, packsContainer, false)
                row.findViewById<TextView>(R.id.packName).text = pack.name
                row.findViewById<TextView>(R.id.packInfo).text = getString(
                    if (pack.animated) R.string.pack_info_animated else R.string.pack_info_static,
                    pack.stickers.size,
                )
                val tray = File(PackStore.dir(applicationContext, pack.id), pack.tray)
                BitmapFactory.decodeFile(tray.path)?.let {
                    row.findViewById<ImageView>(R.id.packTray).setImageBitmap(it)
                }
                row.findViewById<Button>(R.id.addButton).setOnClickListener { addPack(pack) }
                row.findViewById<ImageButton>(R.id.deleteButton).setOnClickListener { confirmDelete(pack) }
                packsContainer.addView(row)
            }
        }
    }

    private fun addPack(pack: StickerPack) {
        val installed = listOf(WHATSAPP, WHATSAPP_BUSINESS).filter { isInstalled(it) }
        when (installed.size) {
            0 -> toast(getString(R.string.no_whatsapp))
            1 -> launchWhatsApp(pack, installed[0])
            else -> AlertDialog.Builder(this)
                .setTitle(R.string.choose_whatsapp)
                .setItems(arrayOf("WhatsApp", "WhatsApp Business")) { _, which ->
                    launchWhatsApp(pack, installed[which])
                }
                .show()
        }
    }

    private fun launchWhatsApp(pack: StickerPack, pkg: String) {
        val intent = Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK")
            .putExtra("sticker_pack_id", pack.id)
            .putExtra("sticker_pack_authority", StickerContentProvider.AUTHORITY)
            .putExtra("sticker_pack_name", pack.name)
            .setPackage(pkg)
        try {
            addToWhatsApp.launch(intent)
        } catch (e: Exception) {
            toast(getString(R.string.whatsapp_open_failed))
        }
    }

    private fun confirmDelete(pack: StickerPack) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_confirm, pack.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { PackStore.delete(applicationContext, pack.id) }
                    refreshPacks()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.help_title)
            .setMessage(R.string.help_text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val WHATSAPP = "com.whatsapp"
        private const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"
    }
}
