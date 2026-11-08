package ru.ttraum.testcontainers

internal actual fun getEnv(name: String): String? = System.getenv(name)

internal actual val isWindows: Boolean =
    System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
