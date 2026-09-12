package org.mlm.miniter.platform

import io.github.mlmgames.settings.core.SettingsRepository
import io.github.mlmgames.settings.core.datastore.createSettingsDataStore
import org.mlm.miniter.settings.AppSettings
import org.mlm.miniter.settings.AppSettingsSchema
import kotlin.concurrent.Volatile

object SettingsProvider {
    @Volatile
    private var repository: SettingsRepository<AppSettings>? = null
    private val lock = Any()

    fun get(): SettingsRepository<AppSettings> {
        repository?.let { return it }
        synchronized(lock) {
            repository?.let { return it }
            val dataStore = createSettingsDataStore("miniter_settings")
            val repo = SettingsRepository(dataStore, AppSettingsSchema)
            repository = repo
            return repo
        }
    }
}
