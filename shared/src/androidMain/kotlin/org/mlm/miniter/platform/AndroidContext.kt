package org.mlm.miniter.platform

import android.content.Context

object AndroidContext {
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun get(): Context {
        return appContext
            ?: throw IllegalStateException(
                "AndroidContext not initialized. Call AndroidContext.init(context) in MainActivity.onCreate()"
            )
    }
}
