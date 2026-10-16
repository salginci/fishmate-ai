import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * Backend base URL, resolved at build time.
 *
 * There is deliberately no Gemini API key here: the app never holds one. It
 * authenticates to the FishMate backend, which mints short-lived constrained
 * Live tokens (see ../../docs/SECURITY.md).
 *
 * Order: -PapiBaseUrl=... > local.properties `fishmate.apiBaseUrl` > default.
 * 10.0.2.2 is the host machine as seen from the Android emulator.
 */
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun resolveApiBaseUrl(default: String): String =
    (project.findProperty("apiBaseUrl") as String?)
        ?: localProperties.getProperty("fishmate.apiBaseUrl")
        ?: default

/**
 * Credentials the sign-in form is pre-filled with in **debug builds only**.
 *
 * Kept out of source: set `fishmate.devEmail` / `fishmate.devPassword` in
 * `local.properties` (gitignored) or pass -PdevEmail=… . The release build
 * always gets empty strings, so no credential is ever shipped.
 */
fun devCredential(key: String, prop: String): String =
    (project.findProperty(prop) as String?) ?: localProperties.getProperty(key) ?: ""

android {
    namespace = "com.fishmate.ai"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.fishmate.ai"
        minSdk = 29          // AudioRecord/AudioTrack + CameraX behaviour we rely on
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("en", "tr")
        vectorDrawables.useSupportLibrary = true

        // The echo canceller is the one piece that cannot be written in
        // Kotlin: a hand-rolled adaptive filter reached 89 % cancellation
        // against a synthetic room and would not converge on a handset.
        // SpeexDSP is vendored under src/main/cpp/speexdsp.
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=none")
            }
        }
        ndk {
            // The ABIs real phones ship: 32-bit ARM for older handsets, 64-bit
            // for everything current. x86 is emulator-only and the emulator
            // has no loudspeaker leak to cancel.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            buildConfigField(
                "String",
                "API_BASE_URL",
                "\"${resolveApiBaseUrl("http://10.0.2.2:8099/")}\"",
            )
            // Debug builds talk to a plaintext localhost backend; see
            // network_security_config.xml, which permits cleartext only for
            // loopback/emulator hosts.
            buildConfigField("boolean", "ALLOW_CLEARTEXT", "true")
            // Convenience for testing: the sign-in form starts pre-filled so a
            // reinstall does not mean retyping credentials on a phone keyboard.
            buildConfigField(
                "String",
                "DEV_EMAIL",
                "\"${devCredential("fishmate.devEmail", "devEmail")}\"",
            )
            buildConfigField(
                "String",
                "DEV_PASSWORD",
                "\"${devCredential("fishmate.devPassword", "devPassword")}\"",
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            buildConfigField(
                "String",
                "API_BASE_URL",
                "\"${resolveApiBaseUrl("https://api.fishmate.example/")}\"",
            )
            buildConfigField("boolean", "ALLOW_CLEARTEXT", "false")
            // Never ship a credential.
            buildConfigField("String", "DEV_EMAIL", "\"\"")
            buildConfigField("String", "DEV_PASSWORD", "\"\"")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/LICENSE*",
                "/META-INF/DEPENDENCIES",
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        // Generated/validated separately; keeps `lintDebug` signal high.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "ObsoleteLintCustomCheck")
        checkReleaseBuilds = false
    }
}

ksp {
    // Room needs a schema location to generate migration-safe schemas.
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Persistence
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    // Camera
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Network
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Misc
    implementation(libs.accompanist.permissions)
    implementation(libs.coil.compose)
    implementation(libs.play.services.location)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.room.testing)

    // Instrumented tests
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.mockk.android)
}
