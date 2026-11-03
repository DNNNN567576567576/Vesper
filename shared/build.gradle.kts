plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

kotlin {
    android {
        namespace = "app.vesper.shared"
        compileSdk = 37
        minSdk = 26
        androidResources { enable = true } // without it CMP 1.12.2 packs no composeResources into the APK assets
    }
    jvm("desktop")
    jvmToolchain(17)

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.compose.runtime:runtime:1.12.2")
            api("org.jetbrains.compose.components:components-resources:1.12.2")
            api("androidx.room:room-runtime:2.8.5")
        }
    }
}

// Exported schemas 1..9 live here; MigrationTest in :app reads them as debug assets. Existing files must never change.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspAndroid", "androidx.room:room-compiler:2.8.5")
    add("kspDesktop", "androidx.room:room-compiler:2.8.5")
}

compose.resources {
    publicResClass = true // :app reads Res until the UI moves here (A6)
    packageOfResClass = "app.vesper.res"
}
