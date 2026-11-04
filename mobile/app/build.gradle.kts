plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
    id("com.google.devtools.ksp")
}

android {
    namespace = "id.nusachat.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "id.nusachat.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"

        vectorDrawables {
            useSupportLibrary = true
        }

        // Debug build points at the dev machine's LAN address so any phone on
        // the same Wi-Fi network can reach the backend. If the machine moves
        // to another network, update these to its new LAN IP and rebuild.
        // (The 127.0.0.1 + `adb reverse` variant only works on the single
        // USB-connected phone, not on other devices.)
        buildConfigField("String", "API_BASE_URL", "\"http://192.168.70.92:8080/api/v1\"")
        // Google OAuth: Web client ID sebagai audience ID token.
        buildConfigField(
            "String",
            "GOOGLE_SERVER_CLIENT_ID",
            "\"719918438607-u9092qoccu5l7dsksoi1f54ok87gr1ac.apps.googleusercontent.com\"",
        )
        // Realtime hanya via gateway Erlang/OTP (realtime/nusachat_realtime).
        // Gateway PHP sudah dihapus total.
        buildConfigField("String", "WS_URL", "\"ws://192.168.70.92:8091\"")
        // GIF Giphy: default key proyek, bisa dioverride via
        // -PGIF_API_KEY=... atau env GIF_API_KEY. Kosong = picker GIF
        // hanya mendukung tempel tautan manual.
        val gifKey = (findProperty("GIF_API_KEY") as String?)
            ?: System.getenv("GIF_API_KEY")
            ?: "6T4KllU0Z42diGwX7jmOHx9qLora1IB6"
        buildConfigField("String", "GIF_API_KEY", "\"$gifKey\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    configurations.all {
        resolutionStrategy {
            force("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.0.21")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.0.21")
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/ASL2.0",
                "META-INF/*.kotlin_module",
                // BouncyCastle vs jspecify: manifest OSGi duplikat, tak dipakai runtime.
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Push notification (FCM) — manual FirebaseApp init, no google-services.json required for debug.
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")

    // Voice call audio (WebRTC, audio-only). GetStream publishes a maintained
    // fork with the same org.webrtc API (org.webrtc artifact is dead).
    implementation("io.getstream:stream-webrtc-android:1.3.10")

    // Lucide icons (gaya outline konsisten, pengganti Material icons).
    // Paksa stdlib tetap 2.0.21: artifact Lucide menaikkan stdlib transitif
    // ke 2.2.x yang tidak bisa dibaca compiler Kotlin 2.0.21 (AAR Lucide
    // hanya berisi drawable, jadi downgrade stdlib aman).
    implementation("com.composables:icons-lucide-android:2.2.1")

    // E2EE: kunci per-percakapan di EncryptedSharedPreferences.
    implementation("androidx.security:security-crypto:1.1.0")

    // Login Google (Credential Manager + ID token).
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // Pindai QR: CameraX + ML Kit Barcode (model statis, tanpa unduhan).
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:barcode-scanning:17.2.0")
    // Tampilkan QR profil (encode saja, tanpa kamera).
    implementation("com.google.zxing:core:3.5.3")

    // Cache chat lokal: Room + SQLCipher (DB terenkripsi, kunci di Keystore).
    // Buka chat instan + baca offline; server tetap sumber kebenaran.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")

    // Primitif kripto E2EE per device (X25519/Ed25519/HKDF). Protokol
    // (X3DH/ratchet/sender-key) ditulis sendiri di data/crypto, primitif
    // TIDAK PERNAH ditulis sendiri — lihat docs/E2EE-PROTOCOL.md.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // Unit test JVM stok (protokol kripto murni, tanpa Android).
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
