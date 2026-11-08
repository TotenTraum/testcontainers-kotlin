package ru.ttraum.testcontainers

internal expect fun getEnv(name: String): String?

internal expect val isWindows: Boolean
