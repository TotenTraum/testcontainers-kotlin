package ru.ttraum.testcontainers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

internal actual fun <T> runBlockingCompat(block: suspend CoroutineScope.() -> T): T = runBlocking(block = block)
