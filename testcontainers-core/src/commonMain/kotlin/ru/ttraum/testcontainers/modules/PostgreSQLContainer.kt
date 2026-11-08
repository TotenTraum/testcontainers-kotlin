package ru.ttraum.testcontainers.modules

import ru.ttraum.testcontainers.GenericContainer
import ru.ttraum.testcontainers.wait.Wait

/** PostgreSQL with sensible defaults. Waits for the server to be ready to accept connections. */
public class PostgreSQLContainer(
    image: String = "postgres:16-alpine",
    configure: PostgreSQLContainer.() -> Unit = {},
) : GenericContainer(image) {
    public var databaseName: String = "test"
    public var username: String = "test"
    public var password: String = "test"

    init {
        withExposedPorts(PORT)
        // Postgres restarts once after init scripts, hence the message appears twice.
        waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", times = 2))
        configure()
    }

    override fun beforeStart() {
        withEnv("POSTGRES_DB", databaseName)
        withEnv("POSTGRES_USER", username)
        withEnv("POSTGRES_PASSWORD", password)
    }

    public val port: Int get() = getMappedPort(PORT)

    public val jdbcUrl: String get() = "jdbc:postgresql://$host:$port/$databaseName"

    public val connectionString: String get() = "postgresql://$username:$password@$host:$port/$databaseName"

    public companion object {
        public const val PORT: Int = 5432
    }
}
