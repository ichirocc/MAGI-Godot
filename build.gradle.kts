// [Gradle 9移行] AGP 9.0+ は Kotlin サポートを内蔵する（org.jetbrains.kotlin.android 適用は不要）。
// [Kotlin 2.3.21] AGP 9.1.1 同梱の既定KGP(2.2.10)より新しい Kotlin を使うため、公式手順どおり
//   buildscript classpath で KGP をオーバーライド（Compose Compiler も Kotlin 版数に追従させる）。
buildscript {
    // pluginManagement.repositories(settings.gradle.kts) は buildscript{} には継承されないため明示。
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
    }
}

plugins {
    id("com.android.application") version "9.1.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
