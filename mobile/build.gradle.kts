plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
    // KSP wajib seversi Kotlin (2.0.21) untuk codegen Room.
    id("com.google.devtools.ksp") version "2.0.21-1.0.25" apply false
}
