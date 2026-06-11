import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.7.1"
}

group = "ua.at.tsvetkov"
version = "1.0-SNAPSHOT"

// Определяем путь к Android Studio. 
// System.getProperty("idea.home.path") работает, если мы запускаем Gradle из-под самой IDE.
val localIdePath: String = System.getProperty("idea.home.path") ?: "C:/Program Files/Android/Android Studio"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Использование local(file(...)) в версии 2.x привязывает и сборку, и запуск к этой папке.
        local(file(localIdePath))
        
        testFramework(TestFrameworkType.Platform)
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.android")
    }
    
    // Явно добавляем JUnit для тестов, чтобы не зависеть от версии внутри IDE
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        name = "TAO LogExt"
        ideaVersion {
            // AI-261... соответствует 2026.1
            sinceBuild = "261"
            untilBuild = "271.*"
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
        // Увеличиваем память для запускаемой Android Studio
        maxHeapSize = "4g"
        
        // Отключаем мастер первого запуска и проверку обновлений
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
