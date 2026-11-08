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
- `GenericContainer`: ports (random/fixed), env, labels, cmd/entrypoint, hostname, bind mounts, tmpfs,
  privileged mode, logs, networks with aliases.
- Files: copy into a container (before or after start) and out of it (`copyFileFromContainer`,
  `copyFilesFromContainer`), binary-safe.
- `execInContainer`: user, env, working directory, stdin (closed afterwards, so the command sees EOF),
  optional timeout; waits for the real exit code however long the command runs.
- Wait strategies: `forListeningPort` (host side + `/proc/net/tcp` inside the container),
  `forLogMessage`, `forHttp`, `forHealthcheck`, `allOf`. Startup fails fast if the container exits;
  a timeout says what it waited for and the last reason it was not ready, followed by the container logs.
- Images: pull policies (`defaultPolicy`, `alwaysPull`, `ageBased`) with retries of transient failures,
  registry credentials from the docker CLI config (`auths`, `credHelpers`, `credsStore`, `DOCKER_AUTH_CONFIG`),
  name substitution for registry mirrors (`hub.image.name.prefix`), images built from a Dockerfile (`ImageFromDockerfile`).
- Cleanup through Ryuk (`testcontainers/ryuk`), on JVM and Native alike.
- Modules: `PostgreSQLContainer`, `RedisContainer`.

## Configuration
Settings are read like in testcontainers-java: the environment variable `TESTCONTAINERS_<KEY>`
(`ryuk.disabled` → `TESTCONTAINERS_RYUK_DISABLED`), then `~/.testcontainers.properties`.
`TestcontainersConfig.set(key, value)` overrides both from code.

| Key | Purpose |
|---|---|
| `tc.host` (or `TC_HOST`) | Testcontainers Desktop / Cloud endpoint, used first |
| `docker.host` | daemon, after `DOCKER_HOST` |
| `hub.image.name.prefix` | registry mirror for Docker Hub images, e.g. `registry.corp/mirror/` |
| `pull.retries` | retries of transient pull failures (default 2) |
| `ryuk.disabled`, `ryuk.container.image`, `ryuk.container.privileged` | resource reaper |
| `host.override` | host for mapped ports |
| `docker.socket.override` | socket path to mount into Ryuk |

The daemon is found in this order: `tc.host`, `DOCKER_HOST`, `docker.host`, the docker CLI context
(`DOCKER_CONTEXT`, else `currentContext` in `~/.docker/config.json`; `DOCKER_CONFIG` moves that directory),
then on Windows `\\.\pipe\docker_engine`, elsewhere well-known sockets (Docker Desktop, rootless, Colima,
OrbStack, Rancher, Podman). `Docker.resolvedEndpoint.source` says which one was used.

Registry credentials come from `DOCKER_AUTH_CONFIG` (the JSON of a `config.json`) or `~/.docker/config.json`;
helpers are the `docker-credential-<name>` programs on `PATH`. `Docker.registryCredentials` and
`Docker.imageNameSubstitutor` replace either from code.

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
TLS for `tcp://` (detected and reported, not supported), `.dockerignore` in builds, Windows containers (Linux containers via Docker Desktop only), Docker Compose, JS/Wasm targets.

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
