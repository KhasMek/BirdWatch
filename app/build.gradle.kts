import java.time.YearMonth
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// ---------------------------------------------------------------------------------------------
// Versioning: rolling date versions, e.g. 2026.09.1 (year.month.release-in-month).
//
// The release workflow passes the git tag in via BIRDWATCH_VERSION; a local build can pass
// -PbirdwatchVersion=2026.09.1. With neither, the build is "dev" with versionCode 1.
// versionCode is derived so Android sees each release as an upgrade: YYYYMM * 100 + N
//   2026.09.1 -> 20260901, 2026.09.2 -> 20260902, 2026.10.1 -> 20261001   (N must be 1..99)
// A dev build takes the current month's slot 99 (2026-09 -> 20260999): it installs over any
// release from this month, and next month's first release installs over it. Android refuses
// to downgrade a non-debuggable install, so this keeps the dev loop free of uninstalls.
// ---------------------------------------------------------------------------------------------
val dateVersionRegex = Regex("""^(\d{4})\.(0[1-9]|1[0-2])\.([1-9]\d?)$""")
val requestedVersion: String? = (System.getenv("BIRDWATCH_VERSION") ?: project.findProperty("birdwatchVersion")?.toString())
    ?.trim()?.removePrefix("v")?.takeIf { it.isNotEmpty() }
val releaseVersionName: String = requestedVersion ?: "dev"
val releaseVersionCode: Int = requestedVersion?.let { v ->
    val m = dateVersionRegex.matchEntire(v)
        ?: throw GradleException("BIRDWATCH_VERSION '$v' must look like YYYY.MM.N (e.g. 2026.09.1)")
    val (year, month, n) = m.destructured
    (year.toInt() * 100 + month.toInt()) * 100 + n.toInt()
} ?: YearMonth.now().let { (it.year * 100 + it.monthValue) * 100 + 99 }

// ---------------------------------------------------------------------------------------------
// Release signing. CI supplies ANDROID_KEYSTORE_* env vars from repository secrets; a local
// signed build uses the gitignored `keystore.properties` at the repo root (see the .example).
// With neither present, `assembleRelease` falls back to the debug key so you can still test the
// minified build on a device. Such an APK is NOT distributable.
// ---------------------------------------------------------------------------------------------
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signingValue(env: String, prop: String): String? = System.getenv(env) ?: keystoreProps.getProperty(prop)
val releaseStoreFile: String? = signingValue("ANDROID_KEYSTORE_FILE", "storeFile")

// A versioned build is a real release: never let it fall back to the debug key silently.
if (requestedVersion != null && releaseStoreFile == null) {
    throw GradleException(
        "BIRDWATCH_VERSION=$requestedVersion requested but no release keystore is configured " +
            "(set ANDROID_KEYSTORE_FILE/… or add keystore.properties). Refusing to debug-sign a release."
    )
}

android {
    namespace = "com.khasmek.birdwatch"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.khasmek.birdwatch"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = releaseVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (releaseStoreFile != null) {
                storeFile = file(releaseStoreFile)
                storePassword = signingValue("ANDROID_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("ANDROID_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("ANDROID_KEY_PASSWORD", "keyPassword")
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
            signingConfig = if (releaseStoreFile != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
        debug {
            // Same applicationId as release on purpose (one install identity, one Maps key
            // restriction); only the visible version string marks a dev build.
            versionNameSuffix = "-debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true // BuildConfig.VERSION_NAME on the About screen
    }
    lint {
        // Fail CI on real errors, but don't let a deprecation warning block a build.
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = false
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)

    // AndroidX core / lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // ESP32 companion: USB CDC serial + JSON line parsing (JsonElement API only, no compiler plugin)
    implementation(libs.usb.serial.android)
    implementation(libs.kotlinx.serialization.json)

    // Room
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    // Google Play Services: Maps + Location
    implementation(libs.maps.compose)
    implementation(libs.play.services.maps)
    implementation(libs.play.services.location)

    // Encrypted storage for the user-provided Maps API key
    implementation(libs.androidx.security.crypto)

    // Tests
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
