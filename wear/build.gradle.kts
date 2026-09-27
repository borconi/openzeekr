import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Same untracked keystore.properties as the phone app — the watch AAB MUST be signed with the
// same key (same applicationId ⇒ same Play app).
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply { if (keystorePropsFile.exists()) load(FileInputStream(keystorePropsFile)) }

android {
    namespace = "com.openzeekr.wear"
    compileSdk = 34

    defaultConfig {
        // MUST match the phone app's applicationId: the Wear Data Layer only delivers
        // messages/data between phone and watch apps that share the same applicationId
        // (and signing key). The Kotlin packages stay under `namespace` (com.openzeekr.wear),
        // which is independent of the installed package id.
        applicationId = "com.openzeekr.app"
        // Wear OS 3+ (the DK BLE stack + Wear Compose both need modern APIs).
        minSdk = 30
        targetSdk = 34
        // The phone + watch AABs go into ONE Play release (shared applicationId), so each artifact
        // needs a DISTINCT versionCode. Convention here: watch = phone + 1 (bump both each release).
        versionCode = 10
        versionName = "0.1.7"
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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    lint {
        // Same policy in :app, :core and :wear. See "Lint policy" in the root build.gradle.kts.
        abortOnError = true
        warningsAsErrors = false
        baseline = file("lint-baseline.xml")
        disable += "GradleDependency"
    }
    packaging {
        // BouncyCastle (via :core) ships these duplicate metadata entries.
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        resources.excludes += "/META-INF/versions/**/OSGI-INF/**"
        resources.excludes += "/META-INF/*.SF"
        resources.excludes += "/META-INF/*.DSA"
        resources.excludes += "/META-INF/*.RSA"
    }
}

dependencies {
    // Shared BLE / DK / crypto stack (the watch reuses the phone's DkBleManager,
    // RealDkSession, DkLockController, DkIdentity — no cloud, no proximity).
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    // Wear Compose UI.
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Wear Data Layer — receives the cloned DK key from the phone.
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
}
