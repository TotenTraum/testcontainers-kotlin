# testcontainers-kotlin (KMP)

A Testcontainers-style library for Kotlin Multiplatform: **JVM, macOS (arm64/x64), Linux (x64/arm64), Windows (mingwX64)**.
Common code talks to the Docker Engine API over a unix socket, a Windows named pipe or TCP;
there are no JVM dependencies (no docker-java). Everything is `suspend`.

```kotlin
GenericContainer("redis:7-alpine") {
    withExposedPorts(6379)
    waitingFor(Wait.forLogMessage(".*Ready to accept connections.*"))
}.use { redis ->
    connect(redis.host, redis.getMappedPort(6379))
}

PostgreSQLContainer { databaseName = "app" }.use { pg ->
    println(pg.jdbcUrl)
    pg.execInContainer("psql", "-U", pg.username, "-c", "select 1")
}
```

## Features
- `GenericContainer`: ports (random/fixed), env, labels, cmd/entrypoint, bind mounts, tmpfs,
  copying files in (tar via `PUT /archive`), privileged mode, `exec`, logs, networks with aliases.
- Wait strategies: `forListeningPort` (host side + `/proc/net/tcp` inside the container),
  `forLogMessage`, `forHttp`, `forHealthcheck`, `allOf`. Startup fails fast if the container exits.
- Automatic image pull.
- Cleanup through Ryuk (`testcontainers/ryuk`), on JVM and Native alike.
- Modules: `PostgreSQLContainer`, `RedisContainer`.

## Configuration (env)
| Variable | Purpose |
|---|---|
| `DOCKER_HOST` | `unix:///...`, `npipe:////./pipe/docker_engine` or `tcp://host:port` (no TLS) |
| `TESTCONTAINERS_RYUK_DISABLED` | turn off Ryuk |
| `TESTCONTAINERS_RYUK_CONTAINER_IMAGE` | Ryuk image |
| `TESTCONTAINERS_HOST_OVERRIDE` | host for mapped ports |
| `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` | socket path to mount into Ryuk |

Without `DOCKER_HOST`, Windows uses `\\.\pipe\docker_engine`; elsewhere well-known sockets are probed (Docker Desktop, rootless, Colima, OrbStack, Rancher, Podman).

## Publishing
Maven coordinates live in `gradle.properties` (`buildInfo.*`), the plugin version in `gradle/libs.versions.toml`.
Modules are published with [vanniktech/gradle-maven-publish-plugin](https://github.com/vanniktech/gradle-maven-publish-plugin):

```bash
./gradlew publishToMavenLocal
```

```bash
./gradlew publishAndReleaseToMavenCentral
```

Maven Central credentials and the signing key are read from `~/.gradle/gradle.properties` and are never stored in
this repository. Publish from macOS: the Apple targets cannot be built elsewhere.

## Not supported yet
TLS for `tcp://`, registry auth, Windows containers (Linux containers via Docker Desktop only), Docker Compose, JS/Wasm targets.

```bash
./gradlew :testcontainers-core:jvmTest :testcontainers-core:macosArm64Test
```

### Windows
The named-pipe transport is Win32 (`CreateFileW`/`ReadFile`/`WriteFile`) on mingwX64 and `RandomAccessFile`
on the JVM. `src/mingwTest` checks it against an in-process fake daemon; on a Windows machine with
Docker Desktop run `./gradlew :testcontainers-core:mingwX64Test`. From macOS/Linux the test binary can be
cross-linked (`linkDebugTestMingwX64`) and run under wine (the named-pipe and unit tests only).

`WindowsContainerTest` (mingwX64 only) runs `mcr.microsoft.com/windows/nanoserver` against a daemon in
Windows-containers mode, e.g. GitHub's `windows-2025` runner; the tag must match the host build
(`TC_WINDOWS_TEST_IMAGE` overrides it). It is skipped on Linux-containers daemons, and the Linux container
tests are skipped on Windows-containers daemons. Ryuk is not started for Windows daemons (it is a Linux image),
so cleanup relies on `stop()` / `use {}`.
