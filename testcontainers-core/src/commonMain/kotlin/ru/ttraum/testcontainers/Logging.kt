package ru.ttraum.testcontainers

public enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** Where the library's own messages go (image pulls, container starts, warnings). */
public fun interface TestcontainersLogger {
    public fun log(level: LogLevel, message: String)

    public companion object {
        /**
         * Prints messages at or above `log.level` (`TESTCONTAINERS_LOG_LEVEL`, default `WARN`) to stdout,
         * prefixed with `[testcontainers]`.
         */
        public val Default: TestcontainersLogger = TestcontainersLogger { level, message ->
            val min = TestcontainersConfig.get("log.level")?.uppercase()?.let { name -> LogLevel.entries.firstOrNull { it.name == name } }
                ?: LogLevel.WARN
            if (level >= min) println("[testcontainers] $level $message")
        }

        public val None: TestcontainersLogger = TestcontainersLogger { _, _ -> }
    }
}

internal fun logInfo(message: String) = Docker.logger.log(LogLevel.INFO, message)
internal fun logWarn(message: String) = Docker.logger.log(LogLevel.WARN, message)
internal fun logDebug(message: String) = Docker.logger.log(LogLevel.DEBUG, message)
