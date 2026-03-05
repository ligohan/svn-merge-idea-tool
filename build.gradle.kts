plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.11.0"
}

group = "com.svnmerge.helper"
version = "1.0.5"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        val localIdePath = "/Applications/IntelliJ IDEA.app"
        if (file(localIdePath).exists()) {
            local(localIdePath)
        } else {
            intellijIdea("2024.1.7")
        }
        pluginVerifier()
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

intellijPlatform {
    // 当前插件未提供 Settings/Configurable，关闭 searchable options 生成可避免无意义的 IDE 启动与联网等待。
    buildSearchableOptions = false

    pluginConfiguration {
        id = "com.svnmerge.helper"
        name = "SVN Merge Helper"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "241"
        }
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}
