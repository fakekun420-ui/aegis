plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.aegis.hub"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.aegis.hub"
        minSdk = 26
        targetSdk = 35
        // Auto-increment versionCode via BUILD_NUMBER (GitHub run_number) — each CI build unique (spec 2)
        // F5 (v1.0.0): fallback LOCAL subido 1 -> 2; en CI manda BUILD_NUMBER/GITHUB_RUN_NUMBER
        versionCode = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 2
        versionName = "1.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // MEDIDO 2026-10-03: POCO F3 arm64. Sin filtro el APK trae 4 ABIs de
        // libandroidx.graphics.path.so; solo se ejecuta una.
        ndk { abiFilters += "arm64-v8a" }
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
    buildFeatures {
        compose = true
        // F0: BuildConfig.DEBUG gobierna la instrumentación (TraceoPeticiones,
        // StrictMode, chequeo de hilo en RootShell). Explícito para no depender
        // del valor por defecto del plugin.
        buildConfig = true
        // MEDIDO 2026-10-03: 0 ficheros en res/layout y 0 refs a ViewBinding en todo
        // el codigo. Era verdadero por defecto heredado, no por uso.
        viewBinding = false
    }
    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java", "src/main/kotlin")
        }
    }

    // MEDIDO 2026-10-01: sin esto, los 9 tests de `CredentialsTest` y `NativeOpenCodeApiTest`
    // fallaban todos con `RuntimeException at Log.java:-1`. Los stubs de `android.jar` lanzan
    // "not mocked" en cualquier llamada, y las clases nuevas loguean mucho —que es justo lo que
    // las hace diagnosticables cuando la lectura de la contraseña falla en un movil real.
    //
    // La alternativa era quitar el log de la capa de datos, y esa es la peor de las dos: un
    // fallo mudo en la autenticacion significa volver a los cinco minutos de certificacion.
    //
    // Va DENTRO de `android {` y no junto a las dependencias: CI lo dijo el ciclo siguiente,
    // `Unresolved reference: testOptions`, que es como se ve un bloque en el sitio equivocado.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}
dependencies {
    // Core + legacy views (kept for existing non-Compose code path)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    // MEDIDO 2026-10-03: webkit y coordinatorlayout con 0 referencias en todo el codigo
    // (ni imports ni layouts que los usen). Se quitan del binario, no de la historia.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Compose BOM — Kotlin 2.0.21 compatible (1.5.14 compiler, BOM 2024.10.00)
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    // MEDIDO 2026-10-03: 0 @Preview en el codigo. Solo servia al IDE, no al APK.
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-extended:1.7.3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    // Networking — Retrofit + OkHttp + Gson
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // ---- F4: tests JVM unitarios + suite instrumentada ----
    // Unitarios (src/test): BootstrapViewModelTest (coroutines-test virtualiza el
    // polling de 1s con Dispatchers.setMain + runTest), FriendlyErrorTest y
    // ModelsEnvelopeTest (Gson puro, sin Android). NO se añade mockwebserver:
    // los fakes construyen retrofit2.Response a mano y no hay red real.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1") // misma versión que coroutines-android
    // F10: servidor falso de OpenCode (misma versión que okhttp).
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Instrumentada (src/androidTest): ext junit + espresso + compose ui-test.
    // El BOM se REUTILIZA (el mismo de release, 2024.10.00): no se introduce una
    // segunda versión de compose-bom que pudiera desalinear ui-test y la app.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
