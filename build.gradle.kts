plugins {
    kotlin("jvm") version "2.2.0-RC"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("xyz.jpenilla.run-paper") version "2.3.1"
}

group = "net.netrefined"
version = "1.0"
description = "NetRedefined - Ultimate Networking Optimizer for Minecraft"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.dmulloy2.net/repository/public/")
    maven("https://jitpack.io") // Optional: For other advanced libs
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21-R0.1-SNAPSHOT")
    compileOnly("com.comphenix.protocol:ProtocolLib:5.3.0")
    compileOnly("io.netty:netty-all:4.2.1.Final")
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
}

tasks.jar {
    archiveBaseName.set("NetRedefined")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
}

tasks.shadowJar {
    archiveClassifier.set("all")
    relocate("kotlin", "net.netrefined.libs.kotlin") // Prevent classpath conflicts
    minimize()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}