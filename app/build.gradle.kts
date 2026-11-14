import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// CI / local release signing: reads keystore from environment variables.
// KEYSTORE_BASE64 (base64 encoded .jks/.keystore), KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD
val envKeystoreB64: String? = System.getenv("KEYSTORE_BASE64")
val envKeystorePass: String? = System.getenv("KEYSTORE_PASSWORD")
val envKeyAlias: String? = System.getenv("KEY_ALIAS")
val envKeyPass: String? = System.getenv("KEY_PASSWORD")

// Single source of truth for the release identity; the CI release job
// verifies the pushed tag against baseVersionName.
val baseVersionName = "0.1.0-alpha.7"
val baseVersionCode = 10

android {
    namespace = "io.github.masgzy.nekoport"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.masgzy.nekoport"
        minSdk = 26
        // targetSdk is intentionally kept at 28 (same approach as Termux):
        // it allows executing the bundled/downloaded php-cgi runtime from app data.
        targetSdk = 28
        versionCode = baseVersionCode
        versionName = baseVersionName
        resourceConfigurations += listOf("zh", "en")
    }

    // Per-ABI APKs plus a universal one. Release names use the user-facing
    // ABI labels: NekoPort-<version>-armv8a / armv7 / x86 / x86-64 / universe.apk
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (!envKeystoreB64.isNullOrBlank()) {
            create("ci") {
                val ksFile = rootProject.file("nekoport-release.keystore")
                ksFile.writeBytes(Base64.getDecoder().decode(envKeystoreB64))
                storeFile = ksFile
                storePassword = envKeystorePass
                keyAlias = envKeyAlias
                keyPassword = envKeyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("ci") ?: run {
                // A silent debug-signed "release" APK is easy to mistake for
                // a real release build and distribute; fail loudly instead.
                // (assembleDebug still works locally - only release tasks
                // require the CI keystore environment.)
                val wantsRelease = gradle.startParameter.taskNames.any {
                    it.contains("release", ignoreCase = true) || it.contains("bundle", ignoreCase = true)
                }
                if (wantsRelease) {
                    throw GradleException(
                        "release build requires signing env vars: KEYSTORE_BASE64 / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD " +
                            "(use assembleDebug for local development)",
                    )
                }
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES", "/META-INF/INDEX.LIST")
        }
    }

    lint {
        // targetSdk 28 is intentional (exec from app data, same as Termux); this
        // app is distributed via GitHub, not Google Play.
        disable += "ExpiredTargetSdkVersion"
    }

    buildFeatures {
        buildConfig = true
    }
}

// APK naming + versionCode scheme through the variant API (AGP 9 removed
// applicationVariants): the universal APK gets the HIGHEST code (base*10 + 9)
// so switching from any single-ABI split to the universal package is always
// an upgrade (the reverse direction would be a downgrade and fail to install
// without uninstalling); splits use base*10 + 1..4.
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            // Split outputs carry exactly one ABI filter; the universal APK
            // has none. Matching on the identifier avoids the FilterType API.
            val abi = output.filters.firstOrNull()?.identifier
            val label = when (abi) {
                "arm64-v8a" -> "armv8a"
                "armeabi-v7a" -> "armv7"
                "x86_64" -> "x86-64"
                "x86" -> "x86"
                null -> "universe"
                else -> abi
            }
            val abiCode = when (abi) {
                "armeabi-v7a" -> 1
                "arm64-v8a" -> 2
                "x86" -> 3
                "x86_64" -> 4
                else -> 9 // universal: highest, so it upgrades any split
            }
            val outputImpl = output as com.android.build.api.variant.impl.VariantOutputImpl
            outputImpl.outputFileName.set("NekoPort-$baseVersionName-$label.apk")
            outputImpl.versionCode.set(baseVersionCode * 10 + abiCode)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.runtime)

    // miuix (https://github.com/compose-miuix-ui/miuix)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.blur)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // PHP environment installer: .deb (ar) + tar.xz / tar.zst extraction
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.zstd)
}
