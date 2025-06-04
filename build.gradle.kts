plugins {
    kotlin("jvm") version "2.2.0-RC"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "net.refined"
version = "1.1"
description = "NetRedefined - High Performance Minecraft Networking Optimization Plugin"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.dmulloy2.net/repository/public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21-R0.1-SNAPSHOT")
    compileOnly("com.comphenix.protocol:ProtocolLib:5.1.0")

    implementation(kotlin("stdlib"))
}

tasks {
    compileKotlin {
        kotlinOptions.jvmTarget = "21"
    }
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    shadowJar {
        archiveClassifier.set("")
        relocate("com.comphenix.protocol", "net.redefined.libs.protocol") // optional, if shading
        minimize()
    }

    build {
        dependsOn(shadowJar)
    }
}
