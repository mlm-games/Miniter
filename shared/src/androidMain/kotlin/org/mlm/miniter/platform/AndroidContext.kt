package org.mlm.miniter.platform

import android.content.Context

object AndroidContext {
    @Volatile
    private var appContext: Context? = null
    private val lock = Any()

    fun init(context: Context) {
        synchronized(lock) {
            appContext = context.applicationContext
        }
    }

    fun get(): Context {
        return appContext
            ?: throw IllegalStateException(
                "AndroidContext not initialized. Call AndroidContext.init(context) in MainActivity.onCreate()"
            )
    }
}
