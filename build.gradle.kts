import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.7.1"
}

group = "ua.at.tsvetkov"
version = "1.0.5"

val localIdePath: String = System.getProperty("idea.home.path")
    ?: System.getenv("IDEA_HOME")
    ?: "C:/Program Files/Android/Android Studio"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local(file(localIdePath))
        
        testFramework(TestFrameworkType.Platform)
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.android")
    }

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        name = "TAO LogExt"
        ideaVersion {
            // AI-251... соответствует 2025.1
            sinceBuild = "251"
            untilBuild = "271.*"
        }

        changeNotes = """
            Initial version
        """.trimIndent()
    }

    signing {
        certificateChainFile = file("certificate_chain.crt")
        privateKeyFile = file("private_key.pem")
        password = project.findProperty("pluginSigningPassword")?.toString()
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    runIde {
        maxHeapSize = "4g"

        systemProperty("ide.show.tips.on.startup.default", "false")
        systemProperty("com.android.setupwizard.mode", "DISABLED")
        systemProperty("disable.android.first.run", "true")
        systemProperty("android.sdk.is.not.required", "true")
        systemProperty("idea.no.launcher", "true")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
