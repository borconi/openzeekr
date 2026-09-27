import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is read from an UNTRACKED keystore.properties at the repo root (see
// keystore.properties.example). If it's absent, `release` builds unsigned — Studio's
// "Generate Signed Bundle" still works independently.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply { if (keystorePropsFile.exists()) load(FileInputStream(keystorePropsFile)) }

android {
    namespace = "com.openzeekr.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.openzeekr.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "0.1.7"
        // App-global secrets are baked in :core (SecretsConfig + BuildConfig live there).
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                keystoreProps.getProperty("storeType")?.let { storeType = it } // e.g. PKCS12 for a .p12
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropsFile.exists()) signingConfig = signingConfigs.getByName("release")
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
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    lint {
        // Same policy in :app, :core and :wear. See "Lint policy" in the root build.gradle.kts.
        abortOnError = true
        warningsAsErrors = false
        baseline = file("lint-baseline.xml")
        disable += "GradleDependency"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // BouncyCastle bcprov + bcpkix (pulled in transitively via :core) ship these.
        resources.excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        resources.excludes += "/META-INF/versions/**/OSGI-INF/**"
        resources.excludes += "/META-INF/*.SF"
        resources.excludes += "/META-INF/*.DSA"
        resources.excludes += "/META-INF/*.RSA"
    }
}

dependencies {
    // Shared BLE/DK/crypto/cloud logic (also carries okhttp/retrofit/serialization/
    // security-crypto/bouncycastle transitively via `api`).
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.1")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Map (free, no API key): MapLibre GL + OpenFreeMap tiles. Used for the parked-car
    // location; turn-by-turn navigation is handed off to the phone's nav app via deeplink.
    implementation("org.maplibre.gl:android-sdk:11.13.5")

    // Wear Data Layer — clones the digital key to the paired watch on request.
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
}
