package ua.at.tsvetkov.logext.models

/**
 * Модель данных тега лога.
 */
data class TagInfo(
    val name: String,
    var isSelected: Boolean = false,
    var isPresentInCurrentLog: Boolean = true
)
