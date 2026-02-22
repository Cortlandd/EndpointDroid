plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "com.cortlandwalker"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        // Target Android Studio platform (Otter 2025.2.3 => AI-252.*)
        //androidStudio("2025.2.3")
        local("/Applications/Android Studio.app/Contents")

        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // If you use Android-specific APIs, you may need the Android plugin:
        bundledPlugin("org.jetbrains.android")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            // Android Studio Otter is based on 252.*
            sinceBuild = "252"
            untilBuild = "252.*"
        }

        changeNotes = """
            Initial version
        """.trimIndent()
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    runIde {
        //ideDir = file("/Applications/Android Studio.app/Contents")
        // Update memory
        jvmArgs("-Xmx2048m", "-Xms512m")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
