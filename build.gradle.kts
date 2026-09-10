import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
}

group = "com.musicunlock"
version = "1.2.0"

val ffmpegVersion = "7.1-1.5.11"
val ffmpegPlatform = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    when {
        os.contains("mac") && (arch == "aarch64" || arch == "arm64") -> "macosx-arm64"
        os.contains("mac") -> "macosx-x86_64"
        os.contains("win") -> "windows-x86_64"
        arch == "aarch64" || arch == "arm64" -> "linux-arm64"
        else -> "linux-x86_64"
    }
}

// 版本号唯一来源：本文件的 version。生成 BuildInfo.kt 供运行时比较（启动检查更新）。
val generateBuildInfo by tasks.registering {
    val versionValue = version.toString()
    val outputDir = layout.buildDirectory.dir("generated/buildinfo")
    inputs.property("version", versionValue)
    inputs.property("ffmpegVersion", ffmpegVersion)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().asFile.resolve("musicunlock/BuildInfo.kt")
        file.parentFile.mkdirs()
        file.writeText(
            buildString {
                appendLine("package musicunlock")
                appendLine()
                appendLine("/** 构建信息，由 Gradle 从 build.gradle.kts 的 version 生成。 */")
                appendLine("object BuildInfo {")
                appendLine("    const val VERSION: String = \"$versionValue\"")
                appendLine("    const val FFMPEG_VERSION: String = \"$ffmpegVersion\"")
                appendLine("}")
            },
        )
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    implementation("com.google.code.gson:gson:2.13.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("net.jthink:jaudiotagger:3.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    runtimeOnly("org.bytedeco:ffmpeg:$ffmpegVersion:$ffmpegPlatform") {
        isTransitive = false
    }

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(17)
    sourceSets.getByName("main").kotlin.srcDir(generateBuildInfo)
}

tasks.test {
    useJUnit()
}

compose.desktop {
    application {
        mainClass = "musicunlock.MainKt"

        nativeDistributions {
            // 运行时需要 java.net.http（网易云/QQ 音乐接口与浏览器登录的 CDP 通信）
            modules("java.net.http")
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "MusicUnlock"
            packageVersion = "1.2.0"
            description = "MusicUnlock - convert encrypted music files to open audio formats"
            vendor = "MusicUnlock"
            licenseFile.set(project.file("LICENSE"))

            windows {
                menuGroup = "MusicUnlock"
                upgradeUuid = "3b62df34-8c17-4b20-9a05-2c1c8b41f3a6"
            }
            macOS {
                bundleID = "com.musicunlock.app"
            }
            linux {
                appCategory = "AudioVideo"
            }
        }
    }
}
