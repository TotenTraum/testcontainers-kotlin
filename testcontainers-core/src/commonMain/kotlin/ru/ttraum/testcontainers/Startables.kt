package ru.ttraum.testcontainers

import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Starts containers concurrently (each also starts its [GenericContainer.dependsOn] dependencies first;
 * a container shared by several of them is started once).
 */
public suspend fun startAll(vararg containers: GenericContainer): Unit = startAll(containers.toList())

public suspend fun startAll(containers: Collection<GenericContainer>) {
    coroutineScope { containers.map { async { it.start() } }.awaitAll() }
}

/** Stops containers concurrently. */
public suspend fun stopAll(vararg containers: GenericContainer) {
    coroutineScope { containers.map { async { it.stop() } }.awaitAll() }
}

/** Blocking [startAll], for code outside coroutines. */
public fun startAllBlocking(vararg containers: GenericContainer): Unit = runBlockingCompat { startAll(containers.toList()) }

/** Blocking [stopAll]. */
public fun stopAllBlocking(vararg containers: GenericContainer): Unit = runBlockingCompat { stopAll(*containers) }
