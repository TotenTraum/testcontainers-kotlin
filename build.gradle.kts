import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.publishPlugin) apply false
}

subprojects {
    group = providers.gradleProperty("buildInfo.group").get()
    version = providers.gradleProperty("buildInfo.version").get()

    // Maven Central publishing for every module that applies the publish plugin.
    //   ./gradlew publishToMavenLocal                  — local check, no credentials needed
    //   ./gradlew publishAndReleaseToMavenCentral      — needs credentials in ~/.gradle/gradle.properties
    // Apple targets build only on macOS, so publish from a Mac to get every target in one release.
    plugins.withId("com.vanniktech.maven.publish") {
        val projectUrl = providers.gradleProperty("buildInfo.url").get()

        extensions.configure<MavenPublishBaseExtension> {
            coordinates(group.toString(), project.name, version.toString())

            pom {
                name.set(project.name)
                description.set(providers.provider { project.description })
                url.set(projectUrl)

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                scm {
                    url.set(projectUrl)
                    connection.set("scm:git:git://${projectUrl.removePrefix("https://")}.git")
                    developerConnection.set("scm:git:ssh://git@${projectUrl.removePrefix("https://")}.git")
                }

                developers {
                    developer {
                        id.set("ttraum")
                        name.set("Azim Usmanov")
                        url.set("https://github.com/ttraum/")
                    }
                }
            }

            plugins.withId("org.jetbrains.kotlin.multiplatform") {
                configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
            }

            publishToMavenCentral()

            // Sign only when a key is configured, so publishToMavenLocal works on any machine.
            // Maven Central rejects unsigned artifacts, so a release still requires the key.
            val hasSigningKey = providers.gradleProperty("signingInMemoryKey").isPresent ||
                providers.gradleProperty("signing.keyId").isPresent
            if (hasSigningKey) signAllPublications()
        }
    }
}
