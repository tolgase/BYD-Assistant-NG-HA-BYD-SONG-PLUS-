import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// "owner/repo" of the GitHub project whose releases this build checks for updates. Not part of the source:
// pass -Passistant.updateRepo=owner/repo (or set ASSISTANT_UPDATE_REPO) when building. Empty = no update checks.
val updateRepo: String = (findProperty("assistant.updateRepo") as String?) ?: System.getenv("ASSISTANT_UPDATE_REPO") ?: ""

val sourceUrl: String = if (updateRepo.isBlank()) "" else "https://github.com/$updateRepo"

// Song PLUS edition: update from your own server instead of GitHub. Pass
//   -Passistant.updateUrl=https://your-server/byd/api/app/voice/releases
// and, when that server asks for one, -Passistant.updateToken=<token>. Empty = use updateRepo above.
val updateUrl: String = (findProperty("assistant.updateUrl") as String?) ?: System.getenv("ASSISTANT_UPDATE_URL") ?: ""
val updateToken: String = (findProperty("assistant.updateToken") as String?) ?: System.getenv("ASSISTANT_UPDATE_TOKEN") ?: ""

// The version of the next release: raise it (major.minor.patch, each 0-99) before publishing a new stable build.
// Betas of that version are numbered automatically (1.1.0-beta.1, 1.1.0-beta.2, ...) and sort below it.
val appVersionName = "1.6.9"

// versionCode is computed from the version, so a newer version always has a higher code and Android never
// refuses an update as a downgrade. AppVersion.code in the app uses the same formula (a unit test guards it).
val versionParts = appVersionName.split(".").map { it.toInt() }
require(versionParts.size == 3 && versionParts.all { it in 0..99 }) { "appVersionName must be major.minor.patch, each 0-99" }
fun versionCodeFor(beta: Int?): Int {
    require(beta == null || beta in 1..998) { "beta number out of range: $beta" }
    return versionParts[0] * 10_000_000 + versionParts[1] * 100_000 + versionParts[2] * 1_000 + (beta ?: 999)
}

val betaTags = providers.provider {
    // Song PLUS edition: built from a source zip without git history, so a missing git means "no beta tags".
    runCatching {
        providers.exec {
            commandLine("git", "tag", "-l", "v${appVersionName}-beta.*")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
    }.getOrDefault("")
}
val betaNumber = betaTags.map { tags ->
    tags.lineSequence()
        .mapNotNull { it.substringAfter("-beta.").toIntOrNull() }
        .maxOrNull()
        ?.plus(1)
        ?: 1
}

android {
    namespace = "com.bydassistantng"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // Song PLUS edition: its own id, so it installs next to (not over) the upstream app and keeps its own settings.
        applicationId = "com.bydassistantng.songplus"
        minSdk = 24
        targetSdk = 37
        versionCode = versionCodeFor(null)
        versionName = appVersionName
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        buildConfigField("String", "SOURCE_URL", "\"$sourceUrl\"")
        buildConfigField("String", "UPDATE_URL", "\"$updateUrl\"")
        buildConfigField("String", "UPDATE_TOKEN", "\"$updateToken\"")
    }

    signingConfigs {
        if (file("signing.properties").exists()) {
            create("release") {
                val properties = Properties().apply {
                    file("signing.properties").inputStream().use { load(it) }
                }

                keyAlias = properties["KEY_ALIAS"] as String
                keyPassword = properties["KEY_PASSWORD"] as String
                storeFile = file(properties["STORE_FILE"] as String)
                storePassword = properties["KEY_PASSWORD"] as String
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        // Local test builds only. The distinct applicationId lets this install *next to* the
        // CI-signed release app: a locally built APK is debug-signed, and Android refuses to upgrade
        // an app over one signed with a different key — while uninstalling the release app to work
        // around that would wipe its saved API key and settings. It's also debuggable, so `run-as`
        // and logcat work on it, unlike the minified release/beta builds.
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }

        release {
            // Song PLUS edition: no code shrinking. The offline speech engine (Vosk) is reached through JNA, which
            // the shrinker would strip or rename; a little larger APK is the safe trade.
            optimization {
                enable = false
                isMinifyEnabled = false
                isShrinkResources = false
            }
            signingConfig = if (file("signing.properties").exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }

        register("beta") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            versionNameSuffix = "-beta.${betaNumber.get()}"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    installation {
        // A plain install over ADB also puts the app into the car's second Android user (999), where its copy asks the car
        // to allow a new ADB key at every boot (see AppUser). Install for the main user only.
        installOptions += listOf("--user", "0")
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            if (variant.buildType == "beta") output.versionCode.set(versionCodeFor(betaNumber.get()))
            val date = SimpleDateFormat("ddMMyyyyHHmmss").format(Date())
            output.outputFileName.set(
                "${rootProject.name}-${output.versionName.get()}(${output.versionCode.get()})-${variant.name}-$date.apk"
            )
        }
    }
}

dependencies {
    // Song PLUS edition: offline speech recognition for instant car commands (Apache-2.0). The @aar forms keep
    // JNA's Android build (with its native library) instead of the desktop jar.
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation(libs.dadb)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
