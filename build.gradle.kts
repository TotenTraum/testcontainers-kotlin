plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

allprojects {
    group = "ru.ttraum"
    version = "0.1.0-SNAPSHOT"
}
