package ru.ttraum.testcontainers

import kotlin.time.Duration

/** How [GenericContainer.start] decides that the container process itself started fine (before the wait strategy). */
public sealed interface StartupCheckStrategy {
    /** The container must be running; exiting during startup fails it (the default). */
    public data object IsRunning : StartupCheckStrategy

    /**
     * For containers that do a job and exit: start succeeds when the container has exited with code 0.
     * Ports are not mapped and the wait strategy is skipped; read the output with `logs()`.
     */
    public data object OneShot : StartupCheckStrategy

    /** The container must keep running for at least [duration] after it was started. */
    public data class MinimumDuration(val duration: Duration) : StartupCheckStrategy

    public companion object {
        public fun isRunning(): StartupCheckStrategy = IsRunning
        public fun oneShot(): StartupCheckStrategy = OneShot
        public fun minimumDuration(duration: Duration): StartupCheckStrategy = MinimumDuration(duration)
    }
}
