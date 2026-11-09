package ru.ttraum.testcontainers

import kotlinx.coroutines.CoroutineScope

/** `runBlocking` (JVM and native; there are no JS targets). Do not call blocking APIs from a coroutine. */
internal expect fun <T> runBlockingCompat(block: suspend CoroutineScope.() -> T): T
