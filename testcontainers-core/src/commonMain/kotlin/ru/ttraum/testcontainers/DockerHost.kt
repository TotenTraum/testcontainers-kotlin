package ru.ttraum.testcontainers

import ru.ttraum.testcontainers.internal.SocketTarget
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** Resolved location of the Docker daemon. */
public sealed interface DockerEndpoint {
    public data class Unix(val socketPath: String) : DockerEndpoint
    public data class Tcp(val host: String, val port: Int) : DockerEndpoint
    /** Windows named pipe, e.g. `\\.\pipe\docker_engine`. */
    public data class NamedPipe(val pipePath: String) : DockerEndpoint

    public companion object {
        /**
         * Resolution order:
         * 1. `DOCKER_HOST` (`unix://...` or `tcp://host:port`)
         * 2. on Windows: Docker Desktop's named pipe `\\.\pipe\docker_engine`
         * 3. well-known unix sockets (Docker Desktop, rootless, Colima, OrbStack, Podman).
         */
        public fun resolve(): DockerEndpoint {
            getEnv("DOCKER_HOST")?.takeIf { it.isNotBlank() }?.let { return parse(it) }
            if (isWindows) return NamedPipe(DEFAULT_WINDOWS_PIPE)

            val home = getEnv("HOME")
            val runtimeDir = getEnv("XDG_RUNTIME_DIR")
            val candidates = buildList {
                add("/var/run/docker.sock")
                if (home != null) {
                    add("$home/.docker/run/docker.sock")
                    add("$home/.docker/desktop/docker.sock")
                    add("$home/.colima/default/docker.sock")
                    add("$home/.orbstack/run/docker.sock")
                    add("$home/.rd/docker.sock")
                }
                if (runtimeDir != null) {
                    add("$runtimeDir/docker.sock")
                    add("$runtimeDir/podman/podman.sock")
                }
            }
            val found = candidates.firstOrNull { SystemFileSystem.exists(Path(it)) }
                ?: throw DockerException(
                    "Could not find a Docker daemon. Set DOCKER_HOST or start Docker. Tried: $candidates",
                )
            return Unix(found)
        }

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
            else -> throw DockerException("Unsupported DOCKER_HOST: $dockerHost (supported: unix://, tcp://, npipe://)")
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
    getEnv("TESTCONTAINERS_HOST_OVERRIDE")?.takeIf { it.isNotBlank() } ?: when (this) {
        is DockerEndpoint.Unix, is DockerEndpoint.NamedPipe -> "localhost"
        is DockerEndpoint.Tcp -> host
    }

/** Path of the docker socket as seen from *inside* a container (used to mount it into Ryuk). */
internal fun DockerEndpoint.socketPathForMount(): String =
    getEnv("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE")?.takeIf { it.isNotBlank() } ?: when (this) {
        // Docker Desktop / Colima / OrbStack expose the daemon socket at /var/run/docker.sock inside the VM.
        is DockerEndpoint.Unix -> if (socketPath.contains("/.docker/") || socketPath.contains("/.colima/") ||
            socketPath.contains("/.orbstack/") || socketPath.contains("/.rd/")
        ) "/var/run/docker.sock" else socketPath
        // Docker Desktop (Linux containers) exposes the daemon at /var/run/docker.sock inside its VM.
        is DockerEndpoint.Tcp, is DockerEndpoint.NamedPipe -> "/var/run/docker.sock"
    }

public class DockerException(message: String, public val statusCode: Int? = null, cause: Throwable? = null) :
    RuntimeException(message, cause)
