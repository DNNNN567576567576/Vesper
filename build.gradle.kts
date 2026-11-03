plugins {
    id("com.android.application") version "9.5.0" apply false
    // AGP 9 compiles Kotlin itself (built-in Kotlin); declaring KGP here only pins the Kotlin version it uses.
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.4.21" apply false
    id("com.android.kotlin.multiplatform.library") version "9.5.0" apply false
    id("org.jetbrains.compose") version "1.12.2" apply false
    id("androidx.room") version "2.8.5" apply false
    id("com.google.devtools.ksp") version "2.3.13" apply false
}
