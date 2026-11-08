package ru.ttraum.testcontainers.modules

import ru.ttraum.testcontainers.GenericContainer
import ru.ttraum.testcontainers.wait.Wait

public class RedisContainer(
    image: String = "redis:7-alpine",
    configure: RedisContainer.() -> Unit = {},
) : GenericContainer(image) {
    init {
        withExposedPorts(PORT)
        waitingFor(Wait.forLogMessage(".*Ready to accept connections.*"))
        configure()
    }

    public val port: Int get() = getMappedPort(PORT)

    public val redisUrl: String get() = "redis://$host:$port"

    public companion object {
        public const val PORT: Int = 6379
    }
}
