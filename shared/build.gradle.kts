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
        // Robolectric pixel-hash tests of the shared UI; :app's testDebugUnitTest (the assemble gate) runs them too.
        withHostTest { isIncludeAndroidResources = true }
    }
    jvm("desktop")
    jvmToolchain(17)

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.compose.runtime:runtime:1.12.2")
            api("org.jetbrains.compose.foundation:foundation:1.12.2")
            // Newest stable JB material3; only MaterialTheme/Scaffold/Text are used. Android resolves to the app's newer androidx material3.
            api("org.jetbrains.compose.material3:material3:1.9.0")
            api("org.jetbrains.compose.components:components-resources:1.12.2")
            // Same lifecycle line CMP 1.12.2 ui itself depends on.
            api("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.9.6")
            api("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.9.6")
            api("androidx.room:room-runtime:2.8.5")
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.9.3") // BackHandler actual
        }
        getByName("androidHostTest").dependencies {
            implementation("junit:junit:4.13.2")
            implementation("org.robolectric:robolectric:4.14.1")
            implementation("androidx.test:core:1.6.1")
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
    packageOfResClass = "app.vesper.res"
}
