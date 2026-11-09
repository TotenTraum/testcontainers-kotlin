package ru.ttraum.testcontainers

import ru.ttraum.testcontainers.internal.NetworkCreateRequest
import kotlin.uuid.Uuid

/** A user-defined bridge network shared by several containers. Created lazily on first use. */
public class Network(
    public val name: String = "tc-${Uuid.random().toHexString().take(12)}",
) {
    public var id: String? = null
        private set

    internal suspend fun ensureCreated(): String {
        id?.let { return it }
        ResourceReaper.ensureStarted()
        val created = Docker.client.createNetwork(NetworkCreateRequest(name = name, labels = Docker.defaultLabels))
        id = created
        return created
    }

    public suspend fun remove() {
        id?.let { Docker.client.removeNetwork(it) }
        id = null
    }

    /** Blocking [remove], for code outside coroutines. */
    public fun removeBlocking(): Unit = runBlockingCompat { remove() }
}
