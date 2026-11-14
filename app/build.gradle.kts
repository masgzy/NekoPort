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

android {
    namespace = "io.github.masgzy.nekoport"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.masgzy.nekoport"
        minSdk = 26
        // targetSdk is intentionally kept at 28 (same approach as Termux):
        // it allows executing the bundled/downloaded php-cgi runtime from app data.
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"
        resourceConfigurations += listOf("zh", "en")
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
            signingConfig = signingConfigs.findByName("ci") ?: signingConfigs.getByName("debug")
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
