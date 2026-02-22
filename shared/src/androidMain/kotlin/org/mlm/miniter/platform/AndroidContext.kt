package org.mlm.miniter.platform

import android.content.Context
import java.lang.ref.WeakReference

object AndroidContext {
    private var contextRef: WeakReference<Context>? = null

    fun init(context: Context) {
        contextRef = WeakReference(context.applicationContext)
    }

    fun get(): Context {
        return contextRef?.get()
            ?: throw IllegalStateException(
                "AndroidContext not initialized. Call AndroidContext.init(context) in Application.onCreate()"
            )
    }
}
