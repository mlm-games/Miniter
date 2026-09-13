package org.mlm.miniter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.github.vinceglb.filekit.FileKit
import io.github.mlmgames.settings.core.SettingsRepository
import io.github.vinceglb.filekit.dialogs.init
import org.koin.core.context.GlobalContext
import org.mlm.miniter.di.initKoin
import org.mlm.miniter.platform.AndroidContext
import org.mlm.miniter.platform.MicPermissionRegistry
import org.mlm.miniter.platform.PendingMediaOpen
import org.mlm.miniter.platform.PendingMediaOpens
import org.mlm.miniter.platform.mediaOpenDisplayName
import org.mlm.miniter.settings.AppSettings

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    private var pendingMicResult: ((Boolean) -> Unit)? = null
    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingMicResult?.invoke(granted)
        pendingMicResult = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AndroidContext.init(applicationContext)

        MicPermissionRegistry.register { onResult ->
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                onResult(true)
            } else {
                pendingMicResult = onResult
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        FileKit.init(this)
        enableEdgeToEdge()

        requestStoragePermissions()

        val settingsRepository: SettingsRepository<AppSettings> =
            SettingsProvider.get(applicationContext)

        if (GlobalContext.getOrNull() == null) {
            initKoin(settingsRepository)
        }

        setContent {
            App()
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uris = extractVideoUris(intent)
        if (uris.isEmpty()) return

        val items = uris.map { uri ->
            persistReadPermission(uri)
            PendingMediaOpen(uri.toString(), mediaOpenDisplayName(uri.toString()))
        }
        PendingMediaOpens.submit(items)
    }

    @Suppress("DEPRECATION")
    private fun extractVideoUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val result = linkedSetOf<Uri>()

        when (intent.action) {
            Intent.ACTION_VIEW -> {
                intent.data?.let { result += it }
            }
            Intent.ACTION_SEND -> {
                parcelableExtra<Uri>(intent, Intent.EXTRA_STREAM)?.let { result += it }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                parcelableArrayListExtra<Uri>(intent, Intent.EXTRA_STREAM)?.forEach { result += it }
            }
        }

        // Some senders use clipData instead of EXTRA_STREAM.
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { result += it }
            }
        }

        return result.toList()
    }

    private fun persistReadPermission(uri: Uri) {
        if (uri.scheme != "content") return
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun requestStoragePermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_IMAGES,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
            )
        } else {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            )
        }
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private inline fun <reified T : android.os.Parcelable> parcelableExtra(
        intent: Intent,
        key: String,
    ): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(key) as? T
        }
    }

    private inline fun <reified T : android.os.Parcelable> parcelableArrayListExtra(
        intent: Intent,
        key: String,
    ): ArrayList<T>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(key, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(key)
        }
    }
}
