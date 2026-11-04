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

compose.desktop {
    application {
        mainClass = "app.vesper.desktop.MainKt"
    }
}
