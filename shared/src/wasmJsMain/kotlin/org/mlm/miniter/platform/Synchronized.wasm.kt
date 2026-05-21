package org.mlm.miniter.platform

actual fun <T> synchronized(lock: Any, block: () -> T): T = block()
