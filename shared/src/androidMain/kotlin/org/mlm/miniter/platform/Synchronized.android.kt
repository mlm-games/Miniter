package org.mlm.miniter.platform

import kotlin.synchronized as builtinSynchronized

actual fun <T> synchronized(lock: Any, block: () -> T): T = builtinSynchronized(lock, block)
