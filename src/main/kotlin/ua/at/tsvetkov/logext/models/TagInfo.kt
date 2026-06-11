package ua.at.tsvetkov.logext.models

/**
 * Модель данных тега лога.
 */
data class TagInfo(
    val name: String,
    var isSelected: Boolean = true,
    var isPresentInCurrentLog: Boolean = true,
    var isApplicationTag: Boolean = false
)
