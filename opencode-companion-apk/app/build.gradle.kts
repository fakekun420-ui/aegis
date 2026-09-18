plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.opencode.companion"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.opencode.companion"
        minSdk = 26
        targetSdk = 35
        // Auto-increment versionCode via BUILD_NUMBER (GitHub run_number) — each CI build unique (spec 2)
        versionCode = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("release") {
            // CI injects companion-release.keystore via KEYSTORE_BASE64 secret decode (spec 1)
            val ksFile = file("companion-release.keystore")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: System.getenv("KEY_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // Use release keystore if present (CI), otherwise fallback to debug keystore locally
            signingConfig = signingConfigs.findByName("release")?.takeIf { file("companion-release.keystore").exists() } ?: signingConfigs.getByName("debug")
        }
        debug {
            isDebuggable = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java", "src/main/kotlin")
        }
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
