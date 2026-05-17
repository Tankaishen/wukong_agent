// Top-level build file for wukong_agent project
buildscript {
    val kotlinVersion = "2.0.21"
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/nexus/content/groups/public/")
        maven("https://maven.aliyun.com/nexus/content/repositories/jcenter")
        maven("https://dl.google.com/dl/android/maven2/")
        maven("https://www.jitpack.io")
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    }
}

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.kapt") version "2.0.21" apply false
}

extra.apply {
    set("minSdk", 26)
    set("targetSdk", 30)
    set("compileSdk", 34)
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}
