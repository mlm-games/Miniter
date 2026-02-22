package org.mlm.miniter.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import okio.Path.Companion.toPath
import java.io.File

fun createDataStore(name: String = "miniter_settings"): DataStore<Preferences> {
    return PreferenceDataStoreFactory.createWithPath(
        produceFile = {
            val dir = getAppDataDir(name)
            dir.mkdirs()
            File(dir, "$name.preferences_pb").absolutePath.toPath()
        }
    )
}

private fun getAppDataDir(appName: String): File {
    val os = System.getProperty("os.name").lowercase()

    return when {
        os.contains("win") -> {
            val localAppData = System.getenv("LOCALAPPDATA")
                ?: System.getenv("APPDATA")
                ?: "${System.getProperty("user.home")}\\AppData\\Local"
            File(localAppData, appName)
        }
        os.contains("mac") || os.contains("darwin") -> {
            val home = System.getProperty("user.home")
            File(home, "Library/Application Support/$appName")
        }
        else -> {
            val dataHome = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
                ?: "${System.getProperty("user.home")}/.local/share"
            File(dataHome, appName)
        }
    }
}
