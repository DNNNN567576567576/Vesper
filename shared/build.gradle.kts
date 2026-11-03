plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
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
        }
    }
}

compose.resources {
    publicResClass = true // :app reads Res until the UI moves here (A6)
    packageOfResClass = "app.vesper.res"
}
