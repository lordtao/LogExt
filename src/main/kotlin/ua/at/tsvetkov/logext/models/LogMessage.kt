package ua.at.tsvetkov.logext.models

/**
 * Распарсенное сообщение лога.
 */
data class LogMessage(
    val originalText: String,
    val pid: Int,
    val tid: Int,
    val level: String,
    val tag: String,
    val message: String,
    val timestamp: String
)
