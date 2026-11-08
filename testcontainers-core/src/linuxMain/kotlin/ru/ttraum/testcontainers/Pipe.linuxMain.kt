@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ru.ttraum.testcontainers

import kotlinx.cinterop.CPointer
import platform.posix.FILE

internal actual fun openPipe(command: String): CPointer<FILE>? = platform.posix.popen(command, "r")

internal actual fun closePipe(pipe: CPointer<FILE>): Int = platform.posix.pclose(pipe)
