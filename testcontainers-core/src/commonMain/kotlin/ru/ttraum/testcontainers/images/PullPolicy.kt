package ru.ttraum.testcontainers.images

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** What is known about an image already present on the daemon. */
public class LocalImage(
    public val id: String,
    /** When the image was last pulled or tagged locally, or else when it was built. */
    public val lastUpdated: Instant?,
)

/** Decides whether an image is pulled before a container is created from it. */
public fun interface PullPolicy {
    /** [local] is null when the daemon does not have the image. */
    public fun shouldPull(image: String, local: LocalImage?): Boolean

    public companion object {
        /** Pull only when the image is missing locally. */
        public fun defaultPolicy(): PullPolicy = PullPolicy { _, local -> local == null }

        /** Always pull, e.g. for moving tags such as `latest`. */
        public fun alwaysPull(): PullPolicy = PullPolicy { _, _ -> true }

        /** Pull when the image is missing or was last updated more than [maxAge] ago. */
        public fun ageBased(maxAge: Duration): PullPolicy = PullPolicy { _, local ->
            local == null || local.lastUpdated == null || Clock.System.now() - local.lastUpdated > maxAge
        }
    }
}
