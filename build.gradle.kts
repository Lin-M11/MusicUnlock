import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
}

group = "com.musicunlock"
version = "1.0.0"



dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    implementation("com.google.code.gson:gson:2.13.1")
    implementation("net.jthink:jaudiotagger:3.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnit()
}

compose.desktop {
    application {
        mainClass = "musicunlock.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "MusicUnlock"
            packageVersion = "1.0.0"
            description = "多平台加密音乐格式转换工具"
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
