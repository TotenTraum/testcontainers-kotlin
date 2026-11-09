package ru.ttraum.testcontainers.internal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal class ContainerCreateRequest(
    @SerialName("Image") val image: String,
    @SerialName("Cmd") val cmd: List<String>? = null,
    @SerialName("Entrypoint") val entrypoint: List<String>? = null,
    @SerialName("Env") val env: List<String>? = null,
    @SerialName("Labels") val labels: Map<String, String>? = null,
    @SerialName("ExposedPorts") val exposedPorts: Map<String, JsonObject>? = null,
    @SerialName("WorkingDir") val workingDir: String? = null,
    @SerialName("User") val user: String? = null,
    @SerialName("Hostname") val hostname: String? = null,
    @SerialName("HostConfig") val hostConfig: HostConfig? = null,
    @SerialName("NetworkingConfig") val networkingConfig: NetworkingConfig? = null,
)

@Serializable
internal class HostConfig(
    @SerialName("PortBindings") val portBindings: Map<String, List<PortBinding>>? = null,
    @SerialName("Binds") val binds: List<String>? = null,
    @SerialName("Privileged") val privileged: Boolean? = null,
    @SerialName("AutoRemove") val autoRemove: Boolean? = null,
    @SerialName("NetworkMode") val networkMode: String? = null,
    @SerialName("Tmpfs") val tmpfs: Map<String, String>? = null,
    @SerialName("Memory") val memory: Long? = null,
    @SerialName("MemorySwap") val memorySwap: Long? = null,
    @SerialName("NanoCpus") val nanoCpus: Long? = null,
    @SerialName("ShmSize") val shmSize: Long? = null,
    @SerialName("Ulimits") val ulimits: List<Ulimit>? = null,
    @SerialName("CapAdd") val capAdd: List<String>? = null,
    @SerialName("CapDrop") val capDrop: List<String>? = null,
    @SerialName("ExtraHosts") val extraHosts: List<String>? = null,
    @SerialName("Dns") val dns: List<String>? = null,
    @SerialName("VolumesFrom") val volumesFrom: List<String>? = null,
    @SerialName("ReadonlyRootfs") val readonlyRootfs: Boolean? = null,
    @SerialName("Init") val init: Boolean? = null,
)

@Serializable
internal class PortBinding(
    @SerialName("HostIp") val hostIp: String? = null,
    @SerialName("HostPort") val hostPort: String? = null,
)

@Serializable
internal class NetworkingConfig(
    @SerialName("EndpointsConfig") val endpointsConfig: Map<String, EndpointConfig>,
)

@Serializable
internal class EndpointConfig(
    @SerialName("Aliases") val aliases: List<String>? = null,
)

@Serializable
internal class IdResponse(@SerialName("Id") val id: String)

@Serializable
internal class ContainerInspect(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("State") val state: ContainerState? = null,
    @SerialName("NetworkSettings") val networkSettings: NetworkSettings? = null,
)

@Serializable
internal class ContainerState(
    @SerialName("Status") val status: String? = null,
    @SerialName("Running") val running: Boolean = false,
    @SerialName("ExitCode") val exitCode: Int? = null,
    @SerialName("Error") val error: String? = null,
    @SerialName("Health") val health: Health? = null,
)

@Serializable
internal class Health(@SerialName("Status") val status: String? = null)

@Serializable
internal class NetworkSettings(
    @SerialName("Ports") val ports: Map<String, List<PortBinding>?>? = null,
    @SerialName("Networks") val networks: Map<String, NetworkEndpoint>? = null,
)

@Serializable
internal class NetworkEndpoint(
    @SerialName("IPAddress") val ipAddress: String? = null,
    @SerialName("Aliases") val aliases: List<String>? = null,
)

@Serializable
internal class ExecCreateRequest(
    @SerialName("Cmd") val cmd: List<String>,
    @SerialName("AttachStdin") val attachStdin: Boolean = false,
    @SerialName("AttachStdout") val attachStdout: Boolean = true,
    @SerialName("AttachStderr") val attachStderr: Boolean = true,
    @SerialName("Env") val env: List<String>? = null,
    @SerialName("User") val user: String? = null,
    @SerialName("WorkingDir") val workingDir: String? = null,
)

@Serializable
internal class ExecStartRequest(
    @SerialName("Detach") val detach: Boolean = false,
    @SerialName("Tty") val tty: Boolean = false,
)

@Serializable
internal class ExecInspect(
    @SerialName("Running") val running: Boolean = false,
    @SerialName("ExitCode") val exitCode: Int? = null,
)

@Serializable
internal class NetworkCreateRequest(
    @SerialName("Name") val name: String,
    @SerialName("Driver") val driver: String = "bridge",
    @SerialName("CheckDuplicate") val checkDuplicate: Boolean = true,
    @SerialName("Labels") val labels: Map<String, String>? = null,
)

@Serializable
internal class ErrorResponse(@SerialName("message") val message: String? = null)

@Serializable
internal class VersionResponse(
    @SerialName("Os") val os: String = "linux",
    @SerialName("Version") val version: String? = null,
)

@Serializable
internal class ImageInspect(
    @SerialName("Id") val id: String,
    @SerialName("Os") val os: String? = null,
    @SerialName("Architecture") val architecture: String? = null,
    @SerialName("Variant") val variant: String? = null,
    @SerialName("Created") val created: String? = null,
    @SerialName("Metadata") val metadata: ImageMetadata? = null,
)

@Serializable
internal class ImageMetadata(@SerialName("LastTagTime") val lastTagTime: String? = null)

@Serializable
internal class WaitResponse(@SerialName("StatusCode") val statusCode: Int)

@Serializable
internal class ContainerSummary(@SerialName("Id") val id: String)

@Serializable
internal class VolumeCreateRequest(
    @SerialName("Name") val name: String,
    @SerialName("Labels") val labels: Map<String, String>,
)

@Serializable
internal class Ulimit(
    @SerialName("Name") val name: String,
    @SerialName("Soft") val soft: Long,
    @SerialName("Hard") val hard: Long,
)
