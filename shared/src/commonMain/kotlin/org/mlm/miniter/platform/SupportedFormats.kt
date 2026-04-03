package org.mlm.miniter.platform

expect object SupportedFormats {
    val videoExtensions: List<String>
    val audioExtensions: List<String>
    val formatHelpMessage: String
}
