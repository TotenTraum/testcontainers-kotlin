package ru.ttraum.testcontainers.images

import ru.ttraum.testcontainers.TestcontainersConfig

/** Registry host of an image reference: `docker.io` for Docker Hub images (`redis`, `bitnami/redis`). */
public fun registryOf(image: String): String {
    val first = image.substringBefore('/', missingDelimiterValue = "")
    return if (first.isNotEmpty() && (first.contains('.') || first.contains(':') || first == "localhost")) first else "docker.io"
}

/**
 * Rewrites image names before they are pulled or run, e.g. to go through a registry mirror.
 * Applied to every image the library pulls, Ryuk included; images built from a Dockerfile are left alone.
 */
public fun interface ImageNameSubstitutor {
    public fun substitute(image: String): String

    public companion object {
        /**
         * Prefixes Docker Hub images with `hub.image.name.prefix` (or `TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX`):
         * with `registry.corp/mirror/`, `redis:7` becomes `registry.corp/mirror/redis:7`.
         * Images from other registries are kept as they are.
         */
        public val Default: ImageNameSubstitutor = ImageNameSubstitutor { image ->
            val prefix = TestcontainersConfig.get("hub.image.name.prefix")
            if (prefix == null || registryOf(image) != "docker.io" || image.startsWith("docker.io/")) {
                image
            } else {
                prefix.trimEnd('/') + "/" + image
            }
        }
    }
}
