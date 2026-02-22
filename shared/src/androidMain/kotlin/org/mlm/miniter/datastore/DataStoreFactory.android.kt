package org.mlm.miniter.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory

fun createDataStore(context: Context, name: String = "miniter_settings"): DataStore<Preferences> {
    return PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile("$name.preferences_pb") }
    )
}
