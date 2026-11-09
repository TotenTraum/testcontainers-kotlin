package ru.ttraum.testcontainers

import java.io.File

/**
 * A classpath resource (file or, when not packed in a jar, directory). Resolved with the thread's context
 * class loader, like testcontainers-java.
 */
public fun MountableFile.Companion.forClasspathResource(resource: String, mode: Int? = null): MountableFile {
    val loader = Thread.currentThread().contextClassLoader ?: MountableFile::class.java.classLoader
    val name = resource.removePrefix("/")
    val url = loader.getResource(name) ?: throw DockerException("Classpath resource not found: $resource")
    return if (url.protocol == "file") {
        forHostPath(File(url.toURI()).path, mode)
    } else {
        val bytes = url.openStream().use { it.readBytes() }
        forBytes(bytes, mode)
    }
}

