package org.mlm.miniter.platform

expect object SupportedFormats {
    val videoExtensions: List<String>
    val audioExtensions: List<String>
    val imageExtensions: List<String>
    val subtitleExtensions: List<String>
    val formatHelpMessage: String
}
