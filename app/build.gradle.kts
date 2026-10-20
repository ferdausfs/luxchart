plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---- Single source of truth for the user-visible version name ----
val APP_VERSION_NAME = "1.9.1"

// CI sets GITHUB_RUN_NUMBER; local builds fall back to 1.
val CI_RUN_NUMBER = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

// Release signing material comes ONLY from environment variables.
val keystoreFile: String? = System.getenv("KEYSTORE_FILE")
val keystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")
val keyAliasName: String? = System.getenv("KEY_ALIAS")
val keyPasswordValue: String? = System.getenv("KEY_PASSWORD")
val haveReleaseSigning =
    listOf(keystoreFile, keystorePassword, keyAliasName, keyPasswordValue).all { !it.isNullOrBlank() }

android {
    namespace = "com.ferdausfs.luxchart"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ferdausfs.luxchart" // permanent — never change
        minSdk = 24
        targetSdk = 35
        versionCode = CI_RUN_NUMBER
        versionName = APP_VERSION_NAME
    }

    signingConfigs {
        if (haveReleaseSigning) {
            create("release") {
                storeFile = file(keystoreFile!!)
                storePassword = keystorePassword
                storeType = "PKCS12"
                keyAlias = keyAliasName
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (haveReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            // NOTE: if the signing env vars are missing, the task-graph check
            // below fails the build with a clear message. There is NO
            // debug-signing fallback by design.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Kept deliberately minimal (lightweight app):
    implementation("androidx.core:core-ktx:1.15.0")               // edge-to-edge / insets compat
    implementation("androidx.activity:activity-ktx:1.9.3")        // OnBackPressedCallback, result APIs
    implementation("androidx.core:core-splashscreen:1.0.1")       // splash screen (feature 8)
    // Phase 8 G4 (pinned): WebViewCompat.addDocumentStartJavaScript —
    // document-start injection of the canvas lock, the same API the bridge
    // origin work builds on. No other heavy dependency.
    implementation("androidx.webkit:webkit:1.12.1")

    // JVM unit tests for pure logic (Policy: UA builder / allow-list / cache rules)
    testImplementation("junit:junit:4.13.2")
}

// Fail fast (before any task executes) when an APK/AAB is requested but the
// signing secrets are missing — clear message, no silent fallback.
// Phase 9 F4: the guard now matches ONLY the tasks that actually sign
// (assembleRelease / packageRelease / bundleRelease). The old
// "name contains Release" rule also blocked tasks that never sign anything —
// lintRelease, testReleaseUnitTest, bundle — locally and in CI. CI's
// assembleRelease path still fails fast exactly as before.
gradle.taskGraph.whenReady {
    val requestsSignedArtifact = allTasks.any {
        it.name == "assembleRelease" || it.name == "packageRelease" || it.name == "bundleRelease"
    }
    if (requestsSignedArtifact && !haveReleaseSigning) {
        throw GradleException(
            "Release build requested but signing environment is missing. " +
                "Set KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD " +
                "(GitHub Actions secrets provide these in CI). " +
                "Debug-signing fallback is intentionally not allowed."
        )
    }
}
