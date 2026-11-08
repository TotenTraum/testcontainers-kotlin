package ru.ttraum.testcontainers

import ru.ttraum.testcontainers.internal.SocketTarget

/** Resolved location of the Docker daemon. */
public sealed interface DockerEndpoint {
    public data class Unix(val socketPath: String) : DockerEndpoint
    public data class Tcp(val host: String, val port: Int) : DockerEndpoint
    /** Windows named pipe, e.g. `\\.\pipe\docker_engine`. */
    public data class NamedPipe(val pipePath: String) : DockerEndpoint

    public companion object {
        /** Resolves the daemon endpoint; see [resolveEndpoint] for the order. */
        public fun resolve(): DockerEndpoint = resolveEndpoint().endpoint

        public const val DEFAULT_WINDOWS_PIPE: String = "\\\\.\\pipe\\docker_engine"

        public fun parse(dockerHost: String): DockerEndpoint = when {
            dockerHost.startsWith("unix://") -> Unix(dockerHost.removePrefix("unix://"))
            // npipe:////./pipe/docker_engine  ->  \\.\pipe\docker_engine
            dockerHost.startsWith("npipe://") -> NamedPipe(dockerHost.removePrefix("npipe://").replace('/', '\\'))
            dockerHost.startsWith("tcp://") || dockerHost.startsWith("http://") -> {
                val hostPort = dockerHost.substringAfter("://").substringBefore('/')
                val host = hostPort.substringBeforeLast(':')
                val port = hostPort.substringAfterLast(':', "2375").toInt()
                Tcp(host, port)
            }
            dockerHost.startsWith("ssh://") ->
                throw DockerException("ssh:// Docker hosts are not supported: $dockerHost. Forward the socket (ssh -L) and use unix:// or tcp://")
            else -> throw DockerException("Unsupported Docker host: $dockerHost (supported: unix://, tcp://, npipe://)")
        }
    }
}

internal fun DockerEndpoint.toSocketTarget(): SocketTarget = when (this) {
    is DockerEndpoint.Unix -> SocketTarget.Unix(socketPath)
    is DockerEndpoint.Tcp -> SocketTarget.Tcp(host, port)
    is DockerEndpoint.NamedPipe -> SocketTarget.NamedPipe(pipePath)
}

/** Host on which mapped container ports are reachable. */
internal fun DockerEndpoint.containerHost(): String =
    TestcontainersConfig.get("host.override") ?: when (this) {
        is DockerEndpoint.Unix, is DockerEndpoint.NamedPipe -> "localhost"
        is DockerEndpoint.Tcp -> host
    }

/** Path of the docker socket as seen from *inside* a container (used to mount it into Ryuk). */
internal fun DockerEndpoint.socketPathForMount(): String =
    TestcontainersConfig.get("docker.socket.override") ?: when (this) {
        // Docker Desktop / Colima / OrbStack expose the daemon socket at /var/run/docker.sock inside the VM.
        is DockerEndpoint.Unix -> if (socketPath.contains("/.docker/") || socketPath.contains("/.colima/") ||
            socketPath.contains("/.orbstack/") || socketPath.contains("/.rd/")
        ) "/var/run/docker.sock" else socketPath
        // Docker Desktop (Linux containers) exposes the daemon at /var/run/docker.sock inside its VM.
        is DockerEndpoint.Tcp, is DockerEndpoint.NamedPipe -> "/var/run/docker.sock"
    }

public class DockerException(message: String, public val statusCode: Int? = null, cause: Throwable? = null) :
    RuntimeException(message, cause)
