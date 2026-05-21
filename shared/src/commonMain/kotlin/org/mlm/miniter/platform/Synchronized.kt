package org.mlm.miniter.platform

expect fun <T> synchronized(lock: Any, block: () -> T): T
