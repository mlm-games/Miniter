package org.mlm.miniter.platform

import io.github.mlmgames.settings.core.SettingsRepository
import io.github.mlmgames.settings.core.datastore.createSettingsDataStore
import org.mlm.miniter.settings.AppSettings
import org.mlm.miniter.settings.AppSettingsSchema

object SettingsProvider {
   @Volatile
   private var repository: SettingsRepository<AppSettings>? = null

   fun get(): SettingsRepository<AppSettings> {
       return repository ?: synchronized(this) {
           repository ?: createSettingsDataStore("miniter_settings")
               .let { SettingsRepository(it, AppSettingsSchema) }
               .also { repository = it }
       }
   }
}
