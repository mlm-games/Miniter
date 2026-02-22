package org.mlm.miniter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.vinceglb.filekit.FileKit
import io.github.mlmgames.settings.core.SettingsRepository
import io.github.mlmgames.settings.core.datastore.createSettingsDataStore
import io.github.vinceglb.filekit.dialogs.init
import org.mlm.miniter.di.initKoin
import org.mlm.miniter.settings.AppSettings
import org.mlm.miniter.settings.AppSettingsSchema

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        FileKit.init(this)
        enableEdgeToEdge()

        val settingsRepository: SettingsRepository<AppSettings> =
            SettingsProvider.get(applicationContext)
        initKoin(settingsRepository)
        
        setContent {
            App()
        }
    }
}
