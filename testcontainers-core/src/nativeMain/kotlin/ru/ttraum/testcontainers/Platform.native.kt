@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package ru.ttraum.testcontainers

import kotlinx.cinterop.toKString
import kotlin.native.OsFamily
import kotlin.native.Platform

internal actual fun getEnv(name: String): String? = platform.posix.getenv(name)?.toKString()

internal actual val isWindows: Boolean = Platform.osFamily == OsFamily.WINDOWS
