import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.7.1"
}

group = "net.jasny"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Configure IntelliJ Platform Gradle Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        // Use Webstorm as the target platform so that the Java compiler.
        create("WS", "2025.2.5")
        bundledPlugin("JavaScript")
        bundledPlugin("NodeJS")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
        }

        changeNotes = """
            Initial 1.0.0 release
        """.trimIndent()
    }

    pluginVerification {
        ides {
            create("IU", "2025.2")
            create("WS", "2025.2")
        }
    }

    // Configure plugin signing and publishing to JetBrains Marketplace
    signing {
        certificateChainFile.set(
            layout.projectDirectory.file(
                providers.gradleProperty("marketplace.certChain").get()
            )
        )
        privateKeyFile.set(
            layout.projectDirectory.file(
                providers.gradleProperty("marketplace.privateKey").get()
            )
        )
    }

    publishing {
        // Marketplace token: set INTELLIJ_PUBLISH_TOKEN in your environment or gradle.properties
        token.set(providers.environmentVariable("INTELLIJ_PUBLISH_TOKEN"))
        // Default release channel; adjust as needed, e.g., "beta" or multiple channels
        channels.set(listOf("default"))
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    named<RunIdeTask>("runIde") {
        jvmArgs("-Didea.log.debug.categories=net.jasny.plop")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
