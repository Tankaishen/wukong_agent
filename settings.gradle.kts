pluginManagement {
    repositories {
        // 阿里云镜像（优先使用，加速国内下载）
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/nexus/content/groups/public/")
        maven("https://maven.aliyun.com/nexus/content/repositories/jcenter")
        maven("https://dl.google.com/dl/android/maven2/")

        // JitPack仓库（用于GitHub开源库）
        maven("https://www.jitpack.io")

        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {

        // 阿里云镜像
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/nexus/content/groups/public/")
        maven("https://maven.aliyun.com/nexus/content/repositories/jcenter")
        maven("https://dl.google.com/dl/android/maven2/")

        // JitPack仓库
        maven("https://www.jitpack.io")

        google()
        mavenCentral()
        maven("https://jcenter.bintray.com") // necessary for quickbirdstudios opencv
        flatDir {
            dirs("app/libs")
        }
    }
}

rootProject.name = "WukongRobotJava"
include(":app")