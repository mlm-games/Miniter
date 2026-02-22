package org.mlm.miniter.platform

expect object PlatformFileSystem {
    suspend fun readText(path: String): String
    suspend fun writeText(path: String, content: String)
    fun exists(path: String): Boolean
    fun delete(path: String): Boolean
    fun getParentDirectory(path: String): String
    fun combinePath(parent: String, child: String): String
    fun getAppDataDirectory(appName: String): String
}
