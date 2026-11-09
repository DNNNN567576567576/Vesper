import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2") // Dispatchers.Main
    implementation("androidx.sqlite:sqlite-bundled:2.7.1")
}

// The gitignored Google client goes into the jar at build time; a missing file is skipped, which leaves sync disabled.
tasks.processResources { from("oauth-client.json") }

compose.desktop {
    application {
        mainClass = "app.vesper.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Vesper"
            packageVersion = "2.4.1"
            vendor = "Vesper"
            modules("java.sql", "java.instrument", "jdk.unsupported") // Room/sqlite and Skiko need these in the trimmed runtime
            windows {
                perUserInstall = true
                menu = true
                shortcut = true
                upgradeUuid = "814ef292-9e72-4b69-bac4-05d939ed0929"
                iconFile.set(project.file("packaging/vesper.ico"))
            }
        }
    }
}
