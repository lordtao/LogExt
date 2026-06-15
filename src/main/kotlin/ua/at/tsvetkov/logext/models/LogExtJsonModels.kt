package ua.at.tsvetkov.logext.models

/**
 * Модели данных для парсинга .logcat файлов (JSON формат).
 */
data class LogExtFile(
    val metadata: LogMetadata,
    val logcatMessages: List<LogMessage>
)

data class LogMetadata(
    val filter: String = "",
    val projectApplicationIds: List<String> = emptyList()
)

data class LogMessage(
    val header: LogHeader,
    val message: String
)

data class LogHeader(
    val logLevel: String,
    val pid: Int,
    val tid: Int,
    val applicationId: String?,
    val processName: String,
    val tag: String,
    val timestamp: LogTimestamp
)

data class LogTimestamp(
    val seconds: Long,
    val nanos: Int
)
