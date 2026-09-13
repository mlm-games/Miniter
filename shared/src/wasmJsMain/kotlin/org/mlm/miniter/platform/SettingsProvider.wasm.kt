package org.mlm.miniter.platform

import io.github.mlmgames.settings.core.SettingsRepository
import io.github.mlmgames.settings.core.datastore.createSettingsDataStore
import org.mlm.miniter.settings.AppSettings
import org.mlm.miniter.settings.AppSettingsSchema

// NOTE: no @Volatile / lock needed. The common
// `synchronized` actual here is a no-op passthrough (not inline), so avoid
// non-local returns inside its lambda.
object SettingsProvider {
    private var repository: SettingsRepository<AppSettings>? = null

    fun get(): SettingsRepository<AppSettings> {
        repository?.let { return it }
        val dataStore = createSettingsDataStore("miniter_settings")
        val repo = SettingsRepository(dataStore, AppSettingsSchema)
        repository = repo
        return repo
    }
}
