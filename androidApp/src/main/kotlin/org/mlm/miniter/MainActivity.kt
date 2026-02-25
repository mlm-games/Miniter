package org.mlm.miniter

import android.Manifest
import android.content.pm.PackageManager
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
import org.mlm.miniter.settings.AppSettings

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AndroidContext.init(applicationContext)

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
    }

    private fun requestStoragePermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
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
}
