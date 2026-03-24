plugins {
    id("org.jetbrains.intellij.platform") version "2.0.0"
    kotlin("jvm") version "1.9.22"
}

group = "org.example"
version = "1.0.0"

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        androidStudio("2023.1.1.28")

        instrumentationTools()
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

tasks {
    buildSearchableOptions {
        enabled = false
    }
}

kotlin {
    jvmToolchain(17)
}